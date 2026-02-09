"""
Dashboard API - Production DB 기반 통계
- overview: slot_navertest + traffic_navershopping-test 통계
- devices/groups: Control DB 기존 유지
- logs: Control DB task_logs 기존 유지
"""
from fastapi import APIRouter, HTTPException, Query
from typing import Optional
from datetime import datetime, timedelta
from app.database.supabase_client import get_supabase, get_supabase_production

router = APIRouter()

@router.get("/logs")
async def get_logs(
    device_id: Optional[str] = None,
    traffic_id: Optional[int] = None,
    action: Optional[str] = None,
    limit: int = Query(100, le=1000),
    offset: int = 0
):
    """작업 로그 조회 (Control DB)"""
    try:
        supabase = get_supabase()
        query = supabase.table("task_logs").select("*")

        if device_id:
            query = query.eq("device_id", device_id)
        if traffic_id:
            query = query.eq("traffic_id", traffic_id)
        if action:
            query = query.eq("action", action)

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
    전체 통계 (Production DB 기반)
    - 대기열 수: traffic_navershopping-test COUNT
    - 슬롯 수/성공/실패: slot_navertest에서 집계
    """
    try:
        prod = get_supabase_production()

        # 대기열 수
        queue_result = prod.table("traffic_navershopping-test") \
            .select("id", count="exact") \
            .execute()
        queue_count = queue_result.count if queue_result.count is not None else 0

        # 슬롯 통계
        slots_result = prod.table("slot_navertest") \
            .select("id, success_count, fail_count, status") \
            .execute()

        total_slots = len(slots_result.data)
        active_slots = len([s for s in slots_result.data if s.get('status') == 'active'])
        total_success = sum((s.get('success_count') or 0) for s in slots_result.data)
        total_fail = sum((s.get('fail_count') or 0) for s in slots_result.data)
        total_done = total_success + total_fail
        success_rate = (total_success / total_done * 100) if total_done > 0 else 0

        # 기기 통계 (Control DB)
        total_devices = 0
        active_device_count = 0
        try:
            supabase = get_supabase()
            devices = supabase.table("devices").select("id", count="exact").execute()
            total_devices = devices.count if devices.count is not None else len(devices.data)

            five_minutes_ago = (datetime.utcnow() - timedelta(minutes=5)).isoformat()
            active_devices = supabase.table("devices").select("id", count="exact") \
                .gte("last_heartbeat", five_minutes_ago).execute()
            active_device_count = active_devices.count if active_devices.count is not None else len(active_devices.data)
        except Exception:
            pass

        return {
            "tasks": {
                "queue": queue_count,
                "total_slots": total_slots,
                "active_slots": active_slots,
                "completed": total_success,
                "failed": total_fail,
                "success_rate": round(success_rate, 1)
            },
            "devices": {
                "total": total_devices,
                "active": active_device_count,
                "inactive": total_devices - active_device_count
            }
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"통계 조회 실패: {str(e)}")


@router.get("/stats/devices")
async def get_device_stats():
    """기기별 통계 (Control DB)"""
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
    except Exception:
        return {"devices": []}


@router.get("/stats/groups")
async def get_group_stats():
    """그룹별 통계 (Control DB)"""
    try:
        supabase = get_supabase()
        groups = supabase.table("device_groups").select("*").execute()

        group_stats = []
        for group in groups.data:
            try:
                devices = supabase.table("devices").select("id", count="exact").eq("group_id", group["id"]).execute()
                total_devices = devices.count if devices.count is not None else len(devices.data)

                five_minutes_ago = (datetime.utcnow() - timedelta(minutes=5)).isoformat()
                active_devices = supabase.table("devices").select("id", count="exact") \
                    .eq("group_id", group["id"]).gte("last_heartbeat", five_minutes_ago).execute()
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

    except Exception:
        return {"groups": []}


@router.get("/stats/tasks")
async def get_task_stats():
    """슬롯별 통계 (Production DB)"""
    try:
        prod = get_supabase_production()
        slots = prod.table("slot_navertest").select("*").execute()

        total_success = sum((s.get('success_count') or 0) for s in slots.data)
        total_fail = sum((s.get('fail_count') or 0) for s in slots.data)
        total = total_success + total_fail
        success_rate = (total_success / total * 100) if total > 0 else 0

        return {
            "total_tasks": total,
            "completed": total_success,
            "failed": total_fail,
            "success_rate": round(success_rate, 2),
            "slots": [{
                "id": s.get("id"),
                "keyword": s.get("keyword"),
                "product_name": s.get("product_name"),
                "mid": s.get("mid"),
                "success_count": s.get("success_count", 0),
                "fail_count": s.get("fail_count", 0),
                "status": s.get("status"),
            } for s in slots.data]
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"작업 통계 조회 실패: {str(e)}")
