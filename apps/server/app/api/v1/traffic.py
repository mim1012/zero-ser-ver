"""
트래픽 작업 관리 API (Production DB 기반)
- claim-work: traffic-navershopping-app에서 1건 꺼내고 DELETE (소비형 큐)
- complete: slot_naverapp.success_count 증가 + history INSERT
- fail: slot_naverapp.fail_count 증가 + history INSERT
"""
from fastapi import APIRouter, HTTPException, Body
from pydantic import BaseModel
from typing import Optional, Dict, Any
from datetime import datetime
import os
import string
import random
from app.database.supabase_client import get_supabase_production

LANDING_DOMAIN = os.getenv("LANDING_DOMAIN", "adpangshopping.co.kr")

router = APIRouter()

# ============================================================
# Request/Response 모델
# ============================================================

class ClaimWorkRequest(BaseModel):
    device_id: str

class ClaimWorkBatchRequest(BaseModel):
    device_id: str
    batch_size: int = 10  # 기본값 10, 최대 50

class ClaimWorkResponse(BaseModel):
    traffic_id: int          # traffic-navershopping-app.id
    slot_id: Optional[int] = 0  # slot_naverapp.id
    product_name: str        # slot_naverapp.product_name
    nv_mid: str              # slot_naverapp.mid
    short_keyword: str       # traffic.keyword (풀네임)
    target_url: Optional[str] = None  # traffic.link_url

class ClaimWorkBatchResponse(BaseModel):
    tasks: list[ClaimWorkResponse]
    total_claimed: int

class CompleteWorkRequest(BaseModel):
    traffic_id: int
    slot_id: Optional[int] = None
    device_id: str
    metadata: Optional[Dict[str, Any]] = None

class FailWorkRequest(BaseModel):
    traffic_id: int
    slot_id: Optional[int] = None
    device_id: str
    error_message: str
    metadata: Optional[Dict[str, Any]] = None

# ============================================================
# 내부 헬퍼
# ============================================================

def _generate_slug(length: int = 6) -> str:
    """6자리 랜덤 slug 생성 (영소문자+숫자)"""
    chars = string.ascii_lowercase + string.digits
    return ''.join(random.choices(chars, k=length))


def _generate_ackey(length: int = 8) -> str:
    """네이버 자동완성 ackey 생성 (unified-runner 동일)"""
    chars = string.ascii_lowercase + string.digits
    return ''.join(random.choices(chars, k=length))


def _pick_query_words(keyword: str, product_name: str) -> str:
    """keyword + product_name에서 랜덤 3단어 추출 (unified-runner pickQueryWords 포팅)
    keyword는 반드시 첫 번째로 포함"""
    import re
    # product_name에서 추가 단어 풀 만들기
    pn_text = re.sub(r'[\[\](){}]', ' ', product_name)
    pn_text = re.sub(r'[^\w\sㄱ-ㅎㅏ-ㅣ가-힣]', ' ', pn_text)
    pn_words = [w for w in pn_text.split() if len(w) >= 2 and w != keyword]

    tails = ["추천", "할인", "후기", "인기", "베스트", "구매", "쇼핑", "특가", "세일", "가성비", "최저가", "정품"]

    # keyword 반드시 포함 + product_name에서 1~2단어 추가
    selected = [keyword]
    random.shuffle(pn_words)
    for w in pn_words:
        if len(selected) >= 3:
            break
        if w not in selected:
            selected.append(w)

    # 부족하면 꼬리 키워드 추가
    while len(selected) < 3:
        avail = [t for t in tails if t not in selected]
        if not avail:
            break
        selected.append(random.choice(avail))

    return ' '.join(selected[:3])


def _build_landing_target_url(keyword: str, product_name: str = '') -> str:
    """unified-runner 동일 형식: m.search.naver.com 통합검색 URL
    sm=mtp_sug.top, where=m, ackey 포함
    query는 keyword 그대로 사용 (MID 상품이 검색결과에 나오도록)"""
    from urllib.parse import urlencode
    query = keyword  # keyword 그대로 사용 — MID 매칭 보장
    params = {
        'sm': 'mtp_sug.top',
        'where': 'm',
        'query': query,
        'ackey': _generate_ackey(),
        'acq': query,
        'acr': str(random.randint(1, 9)),
        'qdt': '0',
    }
    return f"https://m.search.naver.com/search.naver?{urlencode(params)}"


def _get_or_create_landing_slug(prod, slot_id: int, keyword: str, product_name: str, link_url: str) -> Optional[str]:
    """landing_redirects에서 slug 조회, 없으면 생성. 랜딩 URL 반환."""
    if not keyword:
        return None

    # unified-runner 동일: m.search.naver.com 통합검색 (매번 query/ackey 랜덤)
    target_url = _build_landing_target_url(keyword, product_name)

    try:
        # 기존 slug 조회 (같은 keyword 조합)
        existing = prod.table('landing_redirects') \
            .select('slug, target_url') \
            .eq('keyword', keyword) \
            .eq('active', True) \
            .limit(1) \
            .execute()

        if existing.data:
            slug = existing.data[0]['slug']
            # 매 요청마다 query/ackey가 랜덤이므로 항상 갱신 + product_name도 동기화
            prod.table('landing_redirects') \
                .update({'target_url': target_url, 'product_name': product_name or ''}) \
                .eq('slug', slug) \
                .execute()
            return f"https://{LANDING_DOMAIN}/r/{slug}"

        # 새 slug 생성
        for _ in range(5):  # 충돌 방지 최대 5회 시도
            slug = _generate_slug()
            check = prod.table('landing_redirects') \
                .select('id') \
                .eq('slug', slug) \
                .limit(1) \
                .execute()
            if not check.data:
                break

        prod.table('landing_redirects').insert({
            'slug': slug,
            'keyword': keyword,
            'target_url': target_url,
            'product_name': product_name or '',
            'redirect_count': 0,
            'active': True,
        }).execute()

        return f"https://{LANDING_DOMAIN}/r/{slug}"

    except Exception:
        # 랜딩 생성 실패 시 쇼핑 검색 URL 직접 반환
        return target_url


def _claim_one(prod) -> Optional[ClaimWorkResponse]:
    """traffic-navershopping-app에서 1건 꺼내고 DELETE. slot_naverapp에서 mid/product_name 조회."""
    # 1) 가장 오래된 1건 SELECT
    row_result = prod.table('traffic-navershopping-app') \
        .select('*') \
        .order('id', desc=False) \
        .limit(1) \
        .execute()

    if not row_result.data:
        return None

    row = row_result.data[0]
    traffic_id = row['id']
    slot_id = row.get('slot_id')
    keyword = row.get('keyword', '')
    link_url = row.get('link_url', '')

    # 2) slot_naverapp에서 mid, product_name 조회
    mid = ''
    product_name = ''
    if slot_id:
        slot_result = prod.table('slot_naverapp') \
            .select('mid, product_name') \
            .eq('id', slot_id) \
            .limit(1) \
            .execute()
        if slot_result.data:
            mid = slot_result.data[0].get('mid') or ''
            product_name = slot_result.data[0].get('product_name') or ''

    # 3) mid/product_name 없으면 이 작업은 건너뛰기 (DELETE 후 None 반환)
    if not mid or not product_name:
        prod.table('traffic-navershopping-app') \
            .delete() \
            .eq('id', traffic_id) \
            .execute()
        return None

    # 4) DELETE (소비형 큐)
    prod.table('traffic-navershopping-app') \
        .delete() \
        .eq('id', traffic_id) \
        .execute()

    # 5) 랜딩 URL 조합
    landing_url = _get_or_create_landing_slug(prod, slot_id or 0, keyword, product_name, link_url)

    return ClaimWorkResponse(
        traffic_id=traffic_id,
        slot_id=slot_id or 0,
        product_name=product_name,
        nv_mid=mid,
        short_keyword=keyword,
        target_url=landing_url
    )

# ============================================================
# API 엔드포인트
# ============================================================

@router.post("/claim-work", response_model=ClaimWorkResponse)
async def claim_work(request: ClaimWorkRequest):
    """
    작업 1건 가져오기 (소비형 큐)
    traffic-navershopping-app에서 가장 오래된 1건 SELECT → slot_naverapp 조회 → DELETE
    """
    try:
        prod = get_supabase_production()

        # 불완전 작업(mid/product_name 없음)은 건너뛰고 최대 5건까지 시도
        for _ in range(5):
            result = _claim_one(prod)
            if result is not None:
                return result

        raise HTTPException(status_code=404, detail="사용 가능한 작업이 없습니다")

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 할당 실패: {str(e)}")


@router.post("/claim-work-batch", response_model=ClaimWorkBatchResponse)
async def claim_work_batch(request: ClaimWorkBatchRequest):
    """
    배치 작업 가져오기 (최대 50건)
    """
    try:
        prod = get_supabase_production()
        batch_size = min(request.batch_size, 50)
        claimed_tasks = []

        for _ in range(batch_size):
            result = _claim_one(prod)
            if result is None:
                break
            claimed_tasks.append(result)

        return ClaimWorkBatchResponse(
            tasks=claimed_tasks,
            total_claimed=len(claimed_tasks)
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"배치 작업 할당 실패: {str(e)}")


@router.post("/complete")
async def complete_work(request: CompleteWorkRequest):
    """
    작업 완료 보고
    - slot_naverapp.success_count += 1
    - slot_rank_naverapp_history에 이력 INSERT
    """
    try:
        prod = get_supabase_production()

        # slot_naverapp success_count 증가
        if request.slot_id:
            slot_result = prod.table('slot_naverapp') \
                .select('success_count') \
                .eq('id', request.slot_id) \
                .limit(1) \
                .execute()

            current_count = 0
            if slot_result.data:
                current_count = slot_result.data[0].get('success_count', 0) or 0

            prod.table('slot_naverapp') \
                .update({'success_count': current_count + 1}) \
                .eq('id', request.slot_id) \
                .execute()

        # history INSERT
        try:
            prod.table('slot_rank_naverapp_history').insert({
                'slot_id': request.slot_id,
                'device_id': request.device_id,
                'traffic_id': request.traffic_id,
                'action': 'complete',
                'metadata': request.metadata,
                'created_at': datetime.now().isoformat()
            }).execute()
        except Exception:
            pass  # history 실패해도 성공 응답

        return {"status": "success", "message": "작업 완료 처리됨"}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 완료 처리 실패: {str(e)}")


@router.post("/fail")
async def fail_work(request: FailWorkRequest):
    """
    작업 실패 보고
    - slot_naverapp.fail_count += 1
    - slot_rank_naverapp_history에 이력 INSERT
    """
    try:
        prod = get_supabase_production()

        # slot_naverapp fail_count 증가
        if request.slot_id:
            slot_result = prod.table('slot_naverapp') \
                .select('fail_count') \
                .eq('id', request.slot_id) \
                .limit(1) \
                .execute()

            current_count = 0
            if slot_result.data:
                current_count = slot_result.data[0].get('fail_count', 0) or 0

            prod.table('slot_naverapp') \
                .update({'fail_count': current_count + 1}) \
                .eq('id', request.slot_id) \
                .execute()

        # history INSERT
        try:
            prod.table('slot_rank_naverapp_history').insert({
                'slot_id': request.slot_id,
                'device_id': request.device_id,
                'traffic_id': request.traffic_id,
                'action': 'fail',
                'fail_reason': request.error_message,
                'metadata': request.metadata,
                'created_at': datetime.now().isoformat()
            }).execute()
        except Exception:
            pass

        return {"status": "success", "message": "작업 실패 처리됨"}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 실패 처리 실패: {str(e)}")


@router.post("/log")
async def log_action(
    traffic_id: int = Body(...),
    device_id: str = Body(...),
    action: str = Body(...),
    message: str = Body(...),
    metadata: Optional[Dict[str, Any]] = Body(None)
):
    """작업 중 액션 로그 기록"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        supabase.table('task_logs').insert({
            'traffic_id': traffic_id,
            'device_id': device_id,
            'action': action,
            'message': message,
            'metadata': metadata
        }).execute()

        return {"status": "success", "message": "로그 기록됨"}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"로그 기록 실패: {str(e)}")
