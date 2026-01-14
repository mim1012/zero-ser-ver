from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional
from datetime import datetime, timedelta
from app.database.supabase_client import get_supabase_client

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
        supabase = get_supabase_client()
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
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"로그 조회 실패: {str(e)}")


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
        supabase = get_supabase_client()
        
        # 작업 통계
        traffic_stats = supabase.table("traffic_navershopping").select("status", count="exact").execute()
        total_tasks = len(traffic_stats.data)
        
        completed_tasks = supabase.table("traffic_navershopping").select("*", count="exact").eq("status", "completed").execute()
        failed_tasks = supabase.table("traffic_navershopping").select("*", count="exact").eq("status", "failed").execute()
        pending_tasks = supabase.table("traffic_navershopping").select("*", count="exact").eq("status", "pending").execute()
        
        # 기기 통계
        devices = supabase.table("devices").select("*", count="exact").execute()
        total_devices = len(devices.data)
        
        # 활성 기기 (최근 5분 이내 하트비트)
        five_minutes_ago = (datetime.utcnow() - timedelta(minutes=5)).isoformat()
        active_devices = supabase.table("devices").select("*", count="exact").gte("last_heartbeat", five_minutes_ago).execute()
        
        # 그룹 통계
        groups = supabase.table("device_groups").select("*", count="exact").execute()
        
        return {
            "tasks": {
                "total": total_tasks,
                "completed": len(completed_tasks.data),
                "failed": len(failed_tasks.data),
                "pending": len(pending_tasks.data),
                "in_progress": total_tasks - len(completed_tasks.data) - len(failed_tasks.data) - len(pending_tasks.data)
            },
            "devices": {
                "total": total_devices,
                "active": len(active_devices.data),
                "inactive": total_devices - len(active_devices.data)
            },
            "groups": {
                "total": len(groups.data)
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
        supabase = get_supabase_client()
        
        # 기기 정보 조회 (그룹 정보 포함)
        devices = supabase.table("devices").select("*, device_groups(name)").execute()
        
        return {
            "devices": devices.data
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"기기 통계 조회 실패: {str(e)}")


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
        supabase = get_supabase_client()
        
        # 그룹 정보 조회
        groups = supabase.table("device_groups").select("*").execute()
        
        group_stats = []
        for group in groups.data:
            # 그룹에 속한 기기 수
            devices = supabase.table("devices").select("*", count="exact").eq("group_id", group["id"]).execute()
            total_devices = len(devices.data)
            
            # 활성 기기 수 (최근 5분 이내 하트비트)
            five_minutes_ago = (datetime.utcnow() - timedelta(minutes=5)).isoformat()
            active_devices = supabase.table("devices").select("*", count="exact").eq("group_id", group["id"]).gte("last_heartbeat", five_minutes_ago).execute()
            
            group_stats.append({
                "id": group["id"],
                "name": group["name"],
                "leader_device_id": group["leader_device_id"],
                "status": group["status"],
                "total_devices": total_devices,
                "active_devices": len(active_devices.data),
                "created_at": group["created_at"]
            })
        
        return {
            "groups": group_stats
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"그룹 통계 조회 실패: {str(e)}")


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
        supabase = get_supabase_client()
        
        query = supabase.table("traffic_navershopping").select("*")
        
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
