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

class ClaimWorkBatchRequest(BaseModel):
    device_id: str
    batch_size: int = 10  # 기본값 10, 최대 50

class ClaimWorkResponse(BaseModel):
    traffic_id: int
    slot_id: Optional[int] = 0
    product_name: str
    nv_mid: str
    short_keyword: Optional[str] = None
    target_url: Optional[str] = None

class ClaimWorkBatchResponse(BaseModel):
    tasks: list[ClaimWorkResponse]
    total_claimed: int

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
        
        # 1. pending 상태의 작업 1개 조회 (priority 높은 순서)
        task_result = supabase.table('distributedTasks') \
            .select('*') \
            .eq('status', 'pending') \
            .order('priority', desc=True) \
            .order('id', desc=False) \
            .limit(1) \
            .execute()

        if not task_result.data:
            raise HTTPException(status_code=404, detail="사용 가능한 작업이 없습니다")

        task = task_result.data[0]
        task_id = task['id']

        # 2. 작업 상태 업데이트 (assigned)
        supabase.table('distributedTasks') \
            .update({
                'status': 'assigned',
                'assignedNodeId': request.device_id,
                'assignedAt': datetime.now().isoformat()
            }) \
            .eq('id', task_id) \
            .execute()

        # 3. 응답 반환
        return ClaimWorkResponse(
            traffic_id=task_id,
            slot_id=task.get('productId') or 0,
            product_name=task.get('productName') or '',
            nv_mid=task.get('nvMid') or '',
            short_keyword=task.get('keyword') or '',
            target_url=task.get('productUrl') or ''
        )
        
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 할당 실패: {str(e)}")


@router.post("/claim-work-batch", response_model=ClaimWorkBatchResponse)
async def claim_work_batch(request: ClaimWorkBatchRequest):
    """
    배치 작업 가져오기

    - status='pending'인 작업을 id 오름차순으로 batch_size개 가져옴
    - 각 작업의 상태를 'claimed'로 변경 (원자적 처리)
    - task_logs에 기록
    - 최대 50개까지 가져올 수 있음

    Returns:
        - tasks: 할당된 작업 목록
        - total_claimed: 실제 할당된 작업 개수
    """
    try:
        supabase = get_supabase()

        # batch_size 제한 (최대 50)
        batch_size = min(request.batch_size, 50)
        claimed_tasks = []

        # 배치로 작업 가져오기 (루프 방식으로 원자적 처리)
        for i in range(batch_size):
            # 1. pending 상태의 작업 1개 조회 (priority 높은 순서)
            task_result = supabase.table('distributedTasks') \
                .select('*') \
                .eq('status', 'pending') \
                .order('priority', desc=True) \
                .order('id', desc=False) \
                .limit(1) \
                .execute()

            if not task_result.data:
                # 더 이상 작업 없음
                break

            task = task_result.data[0]
            task_id = task['id']

            # 2. 작업 상태 업데이트 (assigned) - 원자적 업데이트
            update_result = supabase.table('distributedTasks') \
                .update({
                    'status': 'assigned',
                    'assignedNodeId': request.device_id,
                    'assignedAt': datetime.now().isoformat()
                }) \
                .eq('id', task_id) \
                .eq('status', 'pending') \
                .execute()

            # 3. 업데이트 성공한 경우만 리스트에 추가
            if update_result.data:
                claimed_tasks.append(ClaimWorkResponse(
                    traffic_id=task_id,
                    slot_id=task.get('productId') or 0,
                    product_name=task.get('productName') or '',
                    nv_mid=task.get('nvMid') or '',
                    short_keyword=task.get('keyword') or '',
                    target_url=task.get('productUrl') or ''
                ))

        # 5. 결과 반환
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
    
    - 작업 상태를 'completed'로 변경
    - completed_at 업데이트
    - devices 테이블의 tasks_completed 증가
    - task_logs에 'complete' 액션 기록
    """
    try:
        supabase = get_supabase()
        
        # 1. 작업 상태 업데이트 (completed)
        supabase.table('distributedTasks') \
            .update({
                'status': 'completed',
                'completedAt': datetime.now().isoformat(),
                'result': str(request.metadata) if request.metadata else None
            }) \
            .eq('id', request.traffic_id) \
            .execute()
        
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
        supabase.table('distributedTasks') \
            .update({
                'status': 'failed',
                'result': f'ERROR: {request.error_message}'
            }) \
            .eq('id', request.traffic_id) \
            .execute()
        
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
