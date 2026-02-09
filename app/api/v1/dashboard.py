from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional
from datetime import datetime, timedelta
from app.database.supabase_client import get_supabase

router = APIRouter()

@router.get("/logs")
async def get_logs(
    device_id: Optional[str] = None,
    traffic_id: Optional[int] = None,
    action: Optional[str] = None,
    limit: int = Query(100, le=1000),
    offset: int = 0
):
    """
    작업 로그 조회
    
    - device_id: 특정 기기의 로그만 조회
    - traffic_id: 특정 작업의 로그만 조회
    - action: 특정 액션의 로그만 조회 (search, scroll, click, complete, fail)
    - limit: 최대 조회 개수 (기본 100, 최대 1000)
    - offset: 페이지네이션 오프셋
    """
    try:
        supabase = get_supabase()
        query = supabase.table("task_logs").select("*")
        
        if device_id:
            query = query.eq("device_id", device_id)
        if traffic_id:
            query = query.eq("traffic_id", traffic_id)
        if action:
            query = query.eq("action", action)
        
        # 최신순 정렬
        query = query.order("created_at", desc=True)
        query = query.range(offset, offset + limit - 1)
        
        response = query.execute()
        
        return {
            "logs": response.data,
            "count": len(response.data),
            "offset": offset,
            "limit": limit
        }
        
    except Exception:
        return {"logs": [], "count": 0, "offset": offset, "limit": limit}


@router.get("/stats/overview")
async def get_stats_overview():
    """
    전체 통계 개요
    
    - 총 작업 수
    - 완료/실패/대기 중 작업 수
    - 총 기기 수
    - 활성 기기 수
    - 총 그룹 수
    """
    try:
        supabase = get_supabase()
        
        # 작업 통계 (distributedTasks 테이블)
        all_tasks = supabase.table("distributedTasks").select("status", count="exact").execute()
        total_tasks = all_tasks.count if all_tasks.count is not None else len(all_tasks.data)

        completed_tasks = supabase.table("distributedTasks").select("id", count="exact").eq("status", "completed").execute()
        failed_tasks = supabase.table("distributedTasks").select("id", count="exact").eq("status", "failed").execute()
        pending_tasks = supabase.table("distributedTasks").select("id", count="exact").eq("status", "pending").execute()

        completed_count = completed_tasks.count if completed_tasks.count is not None else len(completed_tasks.data)
        failed_count = failed_tasks.count if failed_tasks.count is not None else len(failed_tasks.data)
        pending_count = pending_tasks.count if pending_tasks.count is not None else len(pending_tasks.data)

        # 기기 통계
        total_devices = 0
        active_count = 0
        groups_count = 0
        try:
            devices = supabase.table("devices").select("id", count="exact").execute()
            total_devices = devices.count if devices.count is not None else len(devices.data)

            five_minutes_ago = (datetime.utcnow() - timedelta(minutes=5)).isoformat()
            active_devices = supabase.table("devices").select("id", count="exact").gte("last_heartbeat", five_minutes_ago).execute()
            active_count = active_devices.count if active_devices.count is not None else len(active_devices.data)

            groups = supabase.table("device_groups").select("id", count="exact").execute()
            groups_count = groups.count if groups.count is not None else len(groups.data)
        except Exception:
            pass

        return {
            "tasks": {
                "total": total_tasks,
                "completed": completed_count,
                "failed": failed_count,
                "pending": pending_count,
                "in_progress": total_tasks - completed_count - failed_count - pending_count
            },
            "devices": {
                "total": total_devices,
                "active": active_count,
                "inactive": total_devices - active_count
            },
            "groups": {
                "total": groups_count
            }
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"통계 조회 실패: {str(e)}")


@router.get("/stats/devices")
async def get_device_stats():
    """
    기기별 통계
    
    - 기기 ID
    - 역할 (leader/follower)
    - 그룹 이름
    - 완료한 작업 수
    - 현재 IP
    - 마지막 하트비트 시간
    """
    try:
        supabase = get_supabase()
        try:
            devices = supabase.table("devices").select("*, device_groups(name)").execute()
            return {"devices": devices.data}
        except Exception:
            pass
        try:
            devices = supabase.table("devices").select("*").execute()
            return {"devices": devices.data}
        except Exception:
            return {"devices": []}

    except Exception as e:
        return {"devices": []}


@router.get("/stats/groups")
async def get_group_stats():
    """
    그룹별 통계
    
    - 그룹 ID
    - 그룹 이름
    - 대장봇 ID
    - 총 기기 수
    - 활성 기기 수
    - 그룹 상태
    """
    try:
        supabase = get_supabase()
        groups = supabase.table("device_groups").select("*").execute()

        group_stats = []
        for group in groups.data:
            try:
                devices = supabase.table("devices").select("id", count="exact").eq("group_id", group["id"]).execute()
                total_devices = devices.count if devices.count is not None else len(devices.data)

                five_minutes_ago = (datetime.utcnow() - timedelta(minutes=5)).isoformat()
                active_devices = supabase.table("devices").select("id", count="exact").eq("group_id", group["id"]).gte("last_heartbeat", five_minutes_ago).execute()
                active_count = active_devices.count if active_devices.count is not None else len(active_devices.data)
            except Exception:
                total_devices = 0
                active_count = 0

            group_stats.append({
                "id": group["id"],
                "name": group["name"],
                "leader_device_id": group.get("leader_device_id"),
                "status": group.get("status", "active"),
                "total_devices": total_devices,
                "active_devices": active_count,
                "created_at": group.get("created_at")
            })

        return {"groups": group_stats}

    except Exception as e:
        return {"groups": []}


@router.get("/stats/tasks")
async def get_task_stats(
    start_date: Optional[str] = None,
    end_date: Optional[str] = None
):
    """
    작업 통계 (기간별)
    
    - start_date: 시작 날짜 (YYYY-MM-DD)
    - end_date: 종료 날짜 (YYYY-MM-DD)
    
    반환:
    - 날짜별 완료/실패 작업 수
    - 평균 작업 시간
    - 성공률
    """
    try:
        supabase = get_supabase()
        
        query = supabase.table("distributedTasks").select("*")
        
        if start_date:
            query = query.gte("created_at", start_date)
        if end_date:
            query = query.lte("created_at", end_date)
        
        tasks = query.execute()
        
        # 통계 계산
        total = len(tasks.data)
        completed = len([t for t in tasks.data if t["status"] == "completed"])
        failed = len([t for t in tasks.data if t["status"] == "failed"])
        
        success_rate = (completed / total * 100) if total > 0 else 0
        
        return {
            "period": {
                "start_date": start_date or "전체",
                "end_date": end_date or "전체"
            },
            "total_tasks": total,
            "completed": completed,
            "failed": failed,
            "success_rate": round(success_rate, 2)
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 통계 조회 실패: {str(e)}")
