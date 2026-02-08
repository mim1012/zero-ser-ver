"""
Admin API - 작업/기기 CRUD 관리
"""
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from typing import Optional, List
from app.database.supabase_client import get_supabase

router = APIRouter()


# ============================================================
# Pydantic 모델
# ============================================================

class CreateTaskRequest(BaseModel):
    productName: str
    nvMid: str
    keyword: str
    productUrl: Optional[str] = None
    priority: int = 1
    repeat_count: int = 1


class UpdateTaskRequest(BaseModel):
    productName: Optional[str] = None
    nvMid: Optional[str] = None
    keyword: Optional[str] = None
    productUrl: Optional[str] = None
    priority: Optional[int] = None
    status: Optional[str] = None


class BulkActionRequest(BaseModel):
    action: str  # reset_failed, delete_completed, pause_all, resume_all, delete_selected, reset_selected
    task_ids: Optional[List[int]] = None


# ============================================================
# 작업 관리 엔드포인트
# ============================================================

@router.post("/tasks")
async def create_task(request: CreateTaskRequest):
    """단일 작업 생성 (repeat_count로 N개 동시 생성 가능)"""
    try:
        supabase = get_supabase()
        rows = []
        for _ in range(min(request.repeat_count, 100)):
            rows.append({
                "productName": request.productName,
                "nvMid": request.nvMid,
                "keyword": request.keyword,
                "productUrl": request.productUrl,
                "priority": request.priority,
                "status": "pending",
                "taskType": "production",
                "targetNodeType": "worker",
            })

        result = supabase.table("distributedTasks").insert(rows).execute()
        return {"status": "success", "created": len(result.data)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 생성 실패: {str(e)}")


class BatchRequest(BaseModel):
    tsv: str


@router.post("/tasks/batch")
async def create_tasks_batch(request: BatchRequest):
    """TSV 문자열로 배치 작업 생성"""
    try:
        supabase = get_supabase()
        rows = []
        lines = request.tsv.strip().split("\n")
        errors = []

        for i, line in enumerate(lines):
            line = line.strip()
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                errors.append(f"Line {i+1}: 최소 3개 컬럼 필요 (상품명, MID, 키워드)")
                continue
            rows.append({
                "productName": parts[0].strip(),
                "nvMid": parts[1].strip(),
                "keyword": parts[2].strip(),
                "productUrl": parts[3].strip() if len(parts) > 3 else None,
                "priority": 1,
                "status": "pending",
                "taskType": "production",
                "targetNodeType": "worker",
            })

        if not rows:
            raise HTTPException(status_code=400, detail="유효한 작업이 없습니다")

        result = supabase.table("distributedTasks").insert(rows).execute()
        return {
            "status": "success",
            "created": len(result.data),
            "errors": errors,
        }

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"배치 생성 실패: {str(e)}")


@router.get("/tasks")
async def list_tasks(
    status: Optional[str] = None,
    search: Optional[str] = None,
    page: int = Query(1, ge=1),
    limit: int = Query(50, ge=1, le=200),
):
    """작업 목록 (필터/검색/페이지네이션)"""
    try:
        supabase = get_supabase()
        offset = (page - 1) * limit

        query = supabase.table("distributedTasks").select("*", count="exact")

        if status:
            query = query.eq("status", status)
        if search:
            query = query.ilike("keyword", f"%{search}%")

        query = query.order("id", desc=True)
        query = query.range(offset, offset + limit - 1)

        result = query.execute()
        total = result.count if result.count is not None else 0
        total_pages = max(1, -(-total // limit))  # ceil division

        return {
            "tasks": result.data,
            "total": total,
            "page": page,
            "limit": limit,
            "total_pages": total_pages,
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 조회 실패: {str(e)}")


@router.put("/tasks/{task_id}")
async def update_task(task_id: int, request: UpdateTaskRequest):
    """작업 수정"""
    try:
        supabase = get_supabase()
        update_data = {k: v for k, v in request.model_dump().items() if v is not None}

        if not update_data:
            raise HTTPException(status_code=400, detail="수정할 항목이 없습니다")

        result = supabase.table("distributedTasks") \
            .update(update_data) \
            .eq("id", task_id) \
            .execute()

        if not result.data:
            raise HTTPException(status_code=404, detail="작업을 찾을 수 없습니다")

        return {"status": "success", "task": result.data[0]}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 수정 실패: {str(e)}")


@router.delete("/tasks/{task_id}")
async def delete_task(task_id: int):
    """작업 삭제"""
    try:
        supabase = get_supabase()
        result = supabase.table("distributedTasks") \
            .delete() \
            .eq("id", task_id) \
            .execute()

        if not result.data:
            raise HTTPException(status_code=404, detail="작업을 찾을 수 없습니다")

        return {"status": "success", "message": "작업 삭제됨"}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 삭제 실패: {str(e)}")


@router.post("/tasks/bulk-action")
async def bulk_action(request: BulkActionRequest):
    """일괄 작업 실행"""
    try:
        supabase = get_supabase()
        action = request.action
        count = 0

        if action == "reset_failed":
            result = supabase.table("distributedTasks") \
                .update({"status": "pending", "assignedNodeId": None, "assignedAt": None, "result": None}) \
                .eq("status", "failed") \
                .execute()
            count = len(result.data)

        elif action == "delete_completed":
            result = supabase.table("distributedTasks") \
                .delete() \
                .eq("status", "completed") \
                .execute()
            count = len(result.data)

        elif action == "pause_all":
            result = supabase.table("distributedTasks") \
                .update({"status": "paused"}) \
                .eq("status", "pending") \
                .execute()
            count = len(result.data)

        elif action == "resume_all":
            result = supabase.table("distributedTasks") \
                .update({"status": "pending"}) \
                .eq("status", "paused") \
                .execute()
            count = len(result.data)

        elif action == "delete_selected":
            if not request.task_ids:
                raise HTTPException(status_code=400, detail="task_ids 필요")
            for tid in request.task_ids:
                supabase.table("distributedTasks").delete().eq("id", tid).execute()
                count += 1

        elif action == "reset_selected":
            if not request.task_ids:
                raise HTTPException(status_code=400, detail="task_ids 필요")
            for tid in request.task_ids:
                supabase.table("distributedTasks") \
                    .update({"status": "pending", "assignedNodeId": None, "assignedAt": None, "result": None}) \
                    .eq("id", tid) \
                    .execute()
                count += 1

        else:
            raise HTTPException(status_code=400, detail=f"알 수 없는 액션: {action}")

        return {"status": "success", "action": action, "affected": count}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"일괄 작업 실패: {str(e)}")


# ============================================================
# 기기 관리 엔드포인트
# ============================================================

@router.get("/devices")
async def list_devices():
    """기기 목록 (group_name 포함)"""
    try:
        supabase = get_supabase()
        result = supabase.table("devices") \
            .select("*, device_groups(name)") \
            .execute()
        return {"devices": result.data}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"기기 조회 실패: {str(e)}")


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
