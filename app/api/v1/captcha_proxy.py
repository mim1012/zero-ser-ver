"""
CAPTCHA 프록시 API (v2)
- 기기 → 이미지+질문 전송 → Claude Vision 호출 → 답 반환
- API 키 중앙관리 (1,500대에 키 미배포)
- 호출 통계 (비용 추적)
"""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional
from datetime import datetime, timedelta
import os
import re
import httpx

from app.database.supabase_client import get_supabase

router = APIRouter()

ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")
CAPTCHA_MODEL = os.getenv("CAPTCHA_MODEL", "claude-sonnet-4-20250514")
CAPTCHA_MAX_TOKENS = 200

# 영수증 CAPTCHA 전용 프롬프트
RECEIPT_PROMPT = (
    "영수증을 읽고 답하세요.\n"
    "질문: {question}\n"
    "규칙:\n"
    "- 전화번호 하이픈 무시, 숫자만 카운트\n"
    '- "앞에서 N번째" = 왼쪽에서 N번째\n'
    '- "뒤에서 N번째" = 오른쪽에서 N번째\n'
    '- "가게 위치는 OO로 [?]" = 도로명 뒤 번지수\n'
    "- 숫자만 출력 (다른 텍스트 없이)\n"
    "답:"
)


# ── Models ──────────────────────────────────────────────

class CaptchaSolveRequest(BaseModel):
    device_id: str
    image_base64: str
    question: str
    traffic_id: Optional[int] = None

class CaptchaSolveResponse(BaseModel):
    answer: str
    confidence: str  # high | low


# ── CAPTCHA 해결 ────────────────────────────────────────

@router.post("/captcha/solve", response_model=CaptchaSolveResponse)
async def solve_captcha(req: CaptchaSolveRequest):
    """
    CAPTCHA 해결 프록시

    1. base64 이미지 + 질문 수신
    2. Claude Vision 호출
    3. 숫자만 추출하여 반환
    4. captcha_logs에 기록
    """
    if not ANTHROPIC_API_KEY:
        raise HTTPException(status_code=500, detail="ANTHROPIC_API_KEY not configured")

    try:
        prompt = RECEIPT_PROMPT.format(question=req.question)

        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                "https://api.anthropic.com/v1/messages",
                headers={
                    "Content-Type": "application/json",
                    "x-api-key": ANTHROPIC_API_KEY,
                    "anthropic-version": "2023-06-01",
                },
                json={
                    "model": CAPTCHA_MODEL,
                    "max_tokens": CAPTCHA_MAX_TOKENS,
                    "messages": [{
                        "role": "user",
                        "content": [
                            {
                                "type": "image",
                                "source": {
                                    "type": "base64",
                                    "media_type": "image/png",
                                    "data": req.image_base64,
                                },
                            },
                            {"type": "text", "text": prompt},
                        ],
                    }],
                },
            )

        if resp.status_code != 200:
            raise HTTPException(
                status_code=502,
                detail=f"Anthropic API {resp.status_code}: {resp.text[:200]}"
            )

        data = resp.json()
        raw = (data.get("content", [{}])[0].get("text", "")).strip()

        # 숫자 추출
        nums = re.findall(r'\d+', raw)
        answer = nums[0] if nums else raw
        confidence = "high" if nums and len(answer) <= 10 else "low"

        # 로그 기록 (실패해도 답은 반환)
        _log_captcha(req, answer, confidence)

        return CaptchaSolveResponse(answer=answer, confidence=confidence)

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"CAPTCHA solve failed: {str(e)}")


def _log_captcha(req: CaptchaSolveRequest, answer: str, confidence: str):
    """captcha_logs 테이블에 기록 (fire-and-forget)"""
    try:
        supabase = get_supabase()
        supabase.table('captcha_logs').insert({
            'device_id': req.device_id,
            'traffic_id': req.traffic_id,
            'question': req.question,
            'answer': answer,
            'confidence': confidence,
            'model': CAPTCHA_MODEL,
            'created_at': datetime.now().isoformat(),
        }).execute()
    except Exception:
        pass


# ── 통계 ────────────────────────────────────────────────

@router.get("/captcha/stats")
async def captcha_stats(
    device_id: Optional[str] = None,
    hours: int = 24,
):
    """
    CAPTCHA 해결 통계

    - 총 호출 수, 성공률, 예상 비용
    - device_id로 기기별 필터 가능
    """
    try:
        supabase = get_supabase()
        cutoff = (datetime.now() - timedelta(hours=hours)).isoformat()

        q = supabase.table('captcha_logs') \
            .select('confidence', count='exact') \
            .gte('created_at', cutoff)

        if device_id:
            q = q.eq('device_id', device_id)

        result = q.execute()
        total = result.count or 0
        high = sum(1 for r in (result.data or []) if r.get('confidence') == 'high')

        return {
            "period_hours": hours,
            "device_id": device_id,
            "total_calls": total,
            "high_confidence": high,
            "success_rate": f"{high / total * 100:.1f}%" if total else "N/A",
            "estimated_cost_usd": round(total * 0.004, 2),
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
