"""
Admin API - 슬롯/대기열/기기 관리
- Slots: slot_naverapp CRUD (Production DB)
- Queue: traffic-navershopping-app 대기열 관리 (Production DB)
- Devices: 기기 관리 (Control DB)
"""
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from typing import Optional, List
from app.database.supabase_client import get_supabase, get_supabase_production

router = APIRouter()


# ============================================================
# Pydantic 모델
# ============================================================

class CreateSlotRequest(BaseModel):
    keyword: str
    mid: str
    product_name: str
    daily_target: Optional[int] = 100
    status: Optional[str] = 'active'

class UpdateSlotRequest(BaseModel):
    keyword: Optional[str] = None
    mid: Optional[str] = None
    product_name: Optional[str] = None
    daily_target: Optional[int] = None
    status: Optional[str] = None

class SlotBulkActionRequest(BaseModel):
    action: str  # reset_counts, unlock_all, delete_selected
    slot_ids: Optional[List[int]] = None

class EnqueueRequest(BaseModel):
    slot_id: int
    keyword: str
    link_url: Optional[str] = None
    repeat_count: int = 1


# ============================================================
# Slots 관리 (slot_naverapp — Production DB)
# ============================================================

@router.get("/slots")
async def list_slots(
    status: Optional[str] = None,
    search: Optional[str] = None,
    page: int = Query(1, ge=1),
    limit: int = Query(50, ge=1, le=200),
):
    """슬롯 목록 조회"""
    try:
        prod = get_supabase_production()
        offset = (page - 1) * limit

        query = prod.table("slot_naverapp").select("*", count="exact")

        if status:
            query = query.eq("status", status)
        if search:
            query = query.or_(f"keyword.ilike.%{search}%,product_name.ilike.%{search}%,mid.ilike.%{search}%")

        query = query.order("id", desc=True)
        query = query.range(offset, offset + limit - 1)

        result = query.execute()
        total = result.count if result.count is not None else 0
        total_pages = max(1, -(-total // limit))

        return {
            "slots": result.data,
            "total": total,
            "page": page,
            "limit": limit,
            "total_pages": total_pages,
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"슬롯 조회 실패: {str(e)}")


@router.post("/slots")
async def create_slot(request: CreateSlotRequest):
    """슬롯 생성"""
    try:
        prod = get_supabase_production()
        result = prod.table("slot_naverapp").insert({
            "keyword": request.keyword,
            "mid": request.mid,
            "product_name": request.product_name,
            "daily_target": request.daily_target,
            "status": request.status,
            "success_count": 0,
            "fail_count": 0,
        }).execute()

        return {"status": "success", "slot": result.data[0] if result.data else None}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"슬롯 생성 실패: {str(e)}")


@router.put("/slots/{slot_id}")
async def update_slot(slot_id: int, request: UpdateSlotRequest):
    """슬롯 수정"""
    try:
        prod = get_supabase_production()
        update_data = {k: v for k, v in request.model_dump().items() if v is not None}

        if not update_data:
            raise HTTPException(status_code=400, detail="수정할 항목이 없습니다")

        result = prod.table("slot_naverapp") \
            .update(update_data) \
            .eq("id", slot_id) \
            .execute()

        if not result.data:
            raise HTTPException(status_code=404, detail="슬롯을 찾을 수 없습니다")

        return {"status": "success", "slot": result.data[0]}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"슬롯 수정 실패: {str(e)}")


@router.delete("/slots/{slot_id}")
async def delete_slot(slot_id: int):
    """슬롯 삭제"""
    try:
        prod = get_supabase_production()
        result = prod.table("slot_naverapp") \
            .delete() \
            .eq("id", slot_id) \
            .execute()

        if not result.data:
            raise HTTPException(status_code=404, detail="슬롯을 찾을 수 없습니다")

        return {"status": "success", "message": "슬롯 삭제됨"}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"슬롯 삭제 실패: {str(e)}")


@router.post("/slots/bulk-action")
async def slot_bulk_action(request: SlotBulkActionRequest):
    """슬롯 일괄 작업"""
    try:
        prod = get_supabase_production()
        action = request.action
        count = 0

        if action == "reset_counts":
            # 모든 슬롯의 success_count, fail_count 초기화
            result = prod.table("slot_naverapp") \
                .update({"success_count": 0, "fail_count": 0}) \
                .gte("id", 0) \
                .execute()
            count = len(result.data)

        elif action == "unlock_all":
            # 모든 워커 잠금 해제
            result = prod.table("slot_naverapp") \
                .update({"worker_lock": None, "locked_at": None}) \
                .not_.is_("worker_lock", "null") \
                .execute()
            count = len(result.data)

        elif action == "delete_selected":
            if not request.slot_ids:
                raise HTTPException(status_code=400, detail="slot_ids 필요")
            for sid in request.slot_ids:
                prod.table("slot_naverapp").delete().eq("id", sid).execute()
                count += 1

        else:
            raise HTTPException(status_code=400, detail=f"알 수 없는 액션: {action}")

        return {"status": "success", "action": action, "affected": count}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"일괄 작업 실패: {str(e)}")


# ============================================================
# Queue 관리 (traffic-navershopping-app — Production DB)
# ============================================================

@router.get("/queue")
async def get_queue(
    page: int = Query(1, ge=1),
    limit: int = Query(50, ge=1, le=200),
):
    """대기열 조회"""
    try:
        prod = get_supabase_production()
        offset = (page - 1) * limit

        # count
        count_result = prod.table("traffic-navershopping-app") \
            .select("id", count="exact") \
            .execute()
        total = count_result.count if count_result.count is not None else 0

        # 목록
        list_result = prod.table("traffic-navershopping-app") \
            .select("*") \
            .order("id", desc=False) \
            .range(offset, offset + limit - 1) \
            .execute()

        total_pages = max(1, -(-total // limit))

        return {
            "queue": list_result.data,
            "total": total,
            "page": page,
            "limit": limit,
            "total_pages": total_pages,
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"대기열 조회 실패: {str(e)}")


@router.post("/queue")
async def enqueue_tasks(request: EnqueueRequest):
    """대기열에 작업 추가"""
    try:
        prod = get_supabase_production()
        rows = []
        for _ in range(min(request.repeat_count, 500)):
            rows.append({
                "slot_id": request.slot_id,
                "keyword": request.keyword,
                "link_url": request.link_url,
            })

        result = prod.table("traffic-navershopping-app").insert(rows).execute()
        return {"status": "success", "created": len(result.data)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"대기열 추가 실패: {str(e)}")


@router.delete("/queue/clear")
async def clear_queue():
    """대기열 전체 비우기"""
    try:
        prod = get_supabase_production()
        result = prod.table("traffic-navershopping-app") \
            .delete() \
            .gte("id", 0) \
            .execute()
        count = len(result.data) if result.data else 0

        return {"status": "success", "deleted": count}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"대기열 비우기 실패: {str(e)}")


# ============================================================
# 기기 관리 엔드포인트 (Control DB — 기존 유지)
# ============================================================

@router.get("/devices")
async def list_devices():
    """기기 목록 (group_name 포함)"""
    try:
        supabase = get_supabase()
        try:
            result = supabase.table("devices").select("*, device_groups(name)").execute()
            return {"devices": result.data}
        except Exception:
            pass
        try:
            result = supabase.table("devices").select("*").execute()
            return {"devices": result.data}
        except Exception:
            return {"devices": []}
    except Exception:
        return {"devices": []}


@router.delete("/devices/{device_id}")
async def remove_device(device_id: str):
    """기기 제거"""
    try:
        supabase = get_supabase()
        result = supabase.table("devices") \
            .delete() \
            .eq("id", device_id) \
            .execute()

        if not result.data:
            raise HTTPException(status_code=404, detail="기기를 찾을 수 없습니다")

        return {"status": "success", "message": f"기기 {device_id} 제거됨"}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"기기 제거 실패: {str(e)}")
