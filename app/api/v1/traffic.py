"""
트래픽 작업 관리 API
- 작업 가져오기 (claim-work)
- 작업 완료 보고 (complete)
- 작업 실패 보고 (fail)
"""
from fastapi import APIRouter, HTTPException, Body
from pydantic import BaseModel
from typing import Optional, Dict, Any
from datetime import datetime
from app.database.supabase_client import get_supabase

router = APIRouter()

# ============================================================
# Request/Response 모델
# ============================================================

class ClaimWorkRequest(BaseModel):
    device_id: str
    
class ClaimWorkResponse(BaseModel):
    traffic_id: int
    slot_id: int
    product_name: str
    nv_mid: str
    short_keyword: Optional[str]
    target_url: Optional[str]

class CompleteWorkRequest(BaseModel):
    traffic_id: int
    device_id: str
    metadata: Optional[Dict[str, Any]] = None  # ackey, scroll_count, dwell_time 등

class FailWorkRequest(BaseModel):
    traffic_id: int
    device_id: str
    error_message: str
    metadata: Optional[Dict[str, Any]] = None

# ============================================================
# API 엔드포인트
# ============================================================

@router.post("/claim-work", response_model=ClaimWorkResponse)
async def claim_work(request: ClaimWorkRequest):
    """
    작업 가져오기 (독립적 작업 할당 방식)
    
    - status='pending'인 작업을 id 오름차순으로 1개 가져옴
    - 작업 상태를 'claimed'로 변경
    - claimed_by, claimed_at 업데이트
    - task_logs에 'claim' 액션 기록
    """
    try:
        supabase = get_supabase()
        
        # 1. pending 상태의 작업 1개 조회 (id 오름차순)
        traffic_result = supabase.table('traffic_navershopping') \
            .select('*, slot_naver(*)') \
            .eq('status', 'pending') \
            .order('id', desc=False) \
            .limit(1) \
            .execute()
        
        if not traffic_result.data:
            raise HTTPException(status_code=404, detail="사용 가능한 작업이 없습니다")
        
        traffic = traffic_result.data[0]
        traffic_id = traffic['id']
        slot = traffic['slot_naver']
        
        # 2. 작업 상태 업데이트 (claimed)
        supabase.table('traffic_navershopping') \
            .update({
                'status': 'claimed',
                'claimed_by': request.device_id,
                'claimed_at': datetime.now().isoformat(),
                'updated_at': datetime.now().isoformat()
            }) \
            .eq('id', traffic_id) \
            .execute()
        
        # 3. task_logs에 기록
        supabase.table('task_logs').insert({
            'traffic_id': traffic_id,
            'device_id': request.device_id,
            'action': 'claim',
            'message': f'작업 할당: {slot["product_name"]}'
        }).execute()
        
        # 4. 응답 반환
        return ClaimWorkResponse(
            traffic_id=traffic_id,
            slot_id=slot['id'],
            product_name=slot['product_name'],
            nv_mid=slot['nv_mid'],
            short_keyword=slot.get('short_keyword'),
            target_url=slot.get('target_url')
        )
        
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 할당 실패: {str(e)}")


@router.post("/complete")
async def complete_work(request: CompleteWorkRequest):
    """
    작업 완료 보고
    
    - 작업 상태를 'completed'로 변경
    - completed_at 업데이트
    - devices 테이블의 tasks_completed 증가
    - task_logs에 'complete' 액션 기록
    """
    try:
        supabase = get_supabase()
        
        # 1. 작업 상태 업데이트 (completed)
        supabase.table('traffic_navershopping') \
            .update({
                'status': 'completed',
                'completed_at': datetime.now().isoformat(),
                'updated_at': datetime.now().isoformat()
            }) \
            .eq('id', request.traffic_id) \
            .execute()
        
        # 2. devices 테이블의 tasks_completed 증가
        device_result = supabase.table('devices') \
            .select('tasks_completed') \
            .eq('id', request.device_id) \
            .execute()
        
        if device_result.data:
            current_count = device_result.data[0]['tasks_completed']
            supabase.table('devices') \
                .update({'tasks_completed': current_count + 1}) \
                .eq('id', request.device_id) \
                .execute()
        
        # 3. task_logs에 기록
        supabase.table('task_logs').insert({
            'traffic_id': request.traffic_id,
            'device_id': request.device_id,
            'action': 'complete',
            'message': '작업 완료',
            'metadata': request.metadata
        }).execute()
        
        return {"status": "success", "message": "작업 완료 처리됨"}
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 완료 처리 실패: {str(e)}")


@router.post("/fail")
async def fail_work(request: FailWorkRequest):
    """
    작업 실패 보고
    
    - 작업 상태를 'failed'로 변경
    - error_message 저장
    - devices 테이블의 tasks_failed 증가
    - task_logs에 'fail' 액션 기록
    """
    try:
        supabase = get_supabase()
        
        # 1. 작업 상태 업데이트 (failed)
        supabase.table('traffic_navershopping') \
            .update({
                'status': 'failed',
                'error_message': request.error_message,
                'updated_at': datetime.now().isoformat()
            }) \
            .eq('id', request.traffic_id) \
            .execute()
        
        # 2. devices 테이블의 tasks_failed 증가
        device_result = supabase.table('devices') \
            .select('tasks_failed') \
            .eq('id', request.device_id) \
            .execute()
        
        if device_result.data:
            current_count = device_result.data[0]['tasks_failed']
            supabase.table('devices') \
                .update({'tasks_failed': current_count + 1}) \
                .eq('id', request.device_id) \
                .execute()
        
        # 3. task_logs에 기록
        supabase.table('task_logs').insert({
            'traffic_id': request.traffic_id,
            'device_id': request.device_id,
            'action': 'fail',
            'message': f'작업 실패: {request.error_message}',
            'metadata': request.metadata
        }).execute()
        
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
    """
    작업 중 액션 로그 기록
    
    - action: search, scroll, click 등
    - metadata: ackey, scroll_count, dwell_time 등
    """
    try:
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
