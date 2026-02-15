"""
분석 API 엔드포인트 — fingerprint 수집, ML 분석, LLM 제안, config 패치, 실시간 모니터링
"""
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from typing import Optional, Dict, Any, List
from datetime import datetime, timedelta

router = APIRouter()


# ============================================================
# Request/Response 모델
# ============================================================

class FingerprintCollectRequest(BaseModel):
    device_id: str
    traffic_id: Optional[int] = None
    device_model: Optional[str] = None
    android_version: Optional[str] = None
    chrome_version: Optional[str] = None
    webview_version: Optional[str] = None
    user_agent: Optional[str] = None
    http_status_code: Optional[int] = None
    response_headers: Optional[Dict[str, Any]] = None
    features: Optional[Dict[str, Any]] = None


class FingerprintBatchRequest(BaseModel):
    fingerprints: List[FingerprintCollectRequest]


class AnalyzeRequest(BaseModel):
    min_samples: int = 5
    hours_back: int = 24


class ApplyFixRequest(BaseModel):
    analysis_id: Optional[int] = None
    patches: Optional[List[Dict[str, Any]]] = None
    dry_run: bool = True


# ============================================================
# 엔드포인트
# ============================================================

@router.post("/collect")
async def collect_fingerprint(request: FingerprintCollectRequest):
    """단일 fingerprint 수집"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        record = {
            "device_id": request.device_id,
            "traffic_id": request.traffic_id,
            "device_model": request.device_model,
            "android_version": request.android_version,
            "chrome_version": request.chrome_version,
            "webview_version": request.webview_version,
            "user_agent": request.user_agent,
            "http_status_code": request.http_status_code,
            "response_headers": request.response_headers,
            "features": request.features,
        }

        supabase.table("device_fingerprints").insert(record).execute()
        return {"status": "success", "message": "Fingerprint collected"}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"수집 실패: {str(e)}")


@router.post("/collect-batch")
async def collect_fingerprint_batch(request: FingerprintBatchRequest):
    """배치 fingerprint 수집"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        records = []
        for fp in request.fingerprints:
            records.append({
                "device_id": fp.device_id,
                "traffic_id": fp.traffic_id,
                "device_model": fp.device_model,
                "android_version": fp.android_version,
                "chrome_version": fp.chrome_version,
                "webview_version": fp.webview_version,
                "user_agent": fp.user_agent,
                "http_status_code": fp.http_status_code,
                "response_headers": fp.response_headers,
                "features": fp.features,
            })

        if records:
            supabase.table("device_fingerprints").insert(records).execute()

        return {"status": "success", "collected": len(records)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"배치 수집 실패: {str(e)}")


@router.get("/features")
async def get_features(
    device_model: Optional[str] = Query(None),
    status_code: Optional[int] = Query(None),
    limit: int = Query(50, le=200),
):
    """수집된 피처 데이터 조회"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        query = supabase.table("device_fingerprints").select("*")

        if device_model:
            query = query.eq("device_model", device_model)
        if status_code:
            query = query.eq("http_status_code", status_code)

        result = query.order("created_at", desc=True).limit(limit).execute()
        return {"data": result.data, "count": len(result.data)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"조회 실패: {str(e)}")


@router.get("/compare")
async def compare_models(
    source_model: str = Query(..., description="통과 디바이스 모델 (예: SM-S928N)"),
    target_model: str = Query(..., description="차단 디바이스 모델 (예: SM-G973N)"),
    limit: int = Query(50, le=200),
):
    """두 디바이스 모델의 피처 비교"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        source_result = supabase.table("device_fingerprints") \
            .select("*") \
            .eq("device_model", source_model) \
            .order("created_at", desc=True) \
            .limit(limit) \
            .execute()

        target_result = supabase.table("device_fingerprints") \
            .select("*") \
            .eq("device_model", target_model) \
            .order("created_at", desc=True) \
            .limit(limit) \
            .execute()

        return {
            "source": {"model": source_model, "count": len(source_result.data), "data": source_result.data},
            "target": {"model": target_model, "count": len(target_result.data), "data": target_result.data},
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"비교 실패: {str(e)}")


@router.post("/analyze")
async def analyze(request: AnalyzeRequest):
    """ML 분석 트리거 (RF + SHAP)"""
    try:
        import asyncio
        from app.database.supabase_client import get_supabase
        from app.analytics.model import train_and_compare
        from datetime import timedelta

        supabase = get_supabase()
        since = (datetime.utcnow() - timedelta(hours=request.hours_back)).isoformat()

        # 차단 로그 수집
        blocked_result = supabase.table("device_fingerprints") \
            .select("*") \
            .in_("http_status_code", [418, 403, 429]) \
            .gte("created_at", since) \
            .order("created_at", desc=True) \
            .limit(200) \
            .execute()

        # 성공 로그 수집
        passed_result = supabase.table("device_fingerprints") \
            .select("*") \
            .eq("http_status_code", 200) \
            .gte("created_at", since) \
            .order("created_at", desc=True) \
            .limit(200) \
            .execute()

        blocked_data = blocked_result.data if blocked_result.data else []
        passed_data = passed_result.data if passed_result.data else []

        if len(blocked_data) < request.min_samples:
            return {
                "status": "insufficient_data",
                "message": f"차단 샘플 부족: {len(blocked_data)} < {request.min_samples}",
                "blocked_count": len(blocked_data),
                "passed_count": len(passed_data),
            }

        if len(passed_data) < request.min_samples:
            return {
                "status": "insufficient_data",
                "message": f"성공 샘플 부족: {len(passed_data)} < {request.min_samples}",
                "blocked_count": len(blocked_data),
                "passed_count": len(passed_data),
            }

        # ML 분석 (CPU 집약적 → 스레드풀에서 실행)
        loop = asyncio.get_event_loop()
        results = await loop.run_in_executor(None, train_and_compare, passed_data, blocked_data)

        # 결과 저장
        try:
            supabase.table("analysis_results").insert({
                "analysis_type": "manual",
                "shap_features": results,
                "applied": False,
            }).execute()
        except Exception:
            pass

        return {"status": "success", "results": results}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"분석 실패: {str(e)}")


@router.get("/suggestions")
async def get_suggestions(
    limit: int = Query(10, le=50),
    applied: Optional[bool] = Query(None),
):
    """LLM 수정 제안 목록 조회"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        query = supabase.table("analysis_results") \
            .select("*") \
            .order("created_at", desc=True) \
            .limit(limit)

        if applied is not None:
            query = query.eq("applied", applied)

        result = query.execute()
        return {"data": result.data, "count": len(result.data)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"조회 실패: {str(e)}")


@router.post("/suggest-fix")
async def suggest_fix_endpoint(request: AnalyzeRequest):
    """ML 분석 + LLM 수정 제안 (전체 파이프라인)"""
    try:
        import asyncio
        from app.database.supabase_client import get_supabase
        from app.analytics.model import train_and_compare
        from app.analytics.llm_analyzer import suggest_fix
        from app.analytics.config_patcher import get_current_configs
        from datetime import timedelta

        supabase = get_supabase()
        since = (datetime.utcnow() - timedelta(hours=request.hours_back)).isoformat()

        # 데이터 수집
        blocked_result = supabase.table("device_fingerprints") \
            .select("*") \
            .in_("http_status_code", [418, 403, 429]) \
            .gte("created_at", since) \
            .limit(200) \
            .execute()

        passed_result = supabase.table("device_fingerprints") \
            .select("*") \
            .eq("http_status_code", 200) \
            .gte("created_at", since) \
            .limit(200) \
            .execute()

        blocked_data = blocked_result.data or []
        passed_data = passed_result.data or []

        if len(blocked_data) < request.min_samples or len(passed_data) < request.min_samples:
            raise HTTPException(
                status_code=400,
                detail=f"샘플 부족: passed={len(passed_data)}, blocked={len(blocked_data)}"
            )

        # ML 분석 (CPU 집약적 → 스레드풀에서 실행)
        loop = asyncio.get_event_loop()
        shap_results = await loop.run_in_executor(None, train_and_compare, passed_data, blocked_data)

        # LLM 제안
        current_config = get_current_configs()
        llm_result = await suggest_fix(
            passed_features=shap_results.get("passed_means", {}),
            blocked_features=shap_results.get("blocked_means", {}),
            shap_results=shap_results,
            current_config=current_config,
        )

        # 저장
        try:
            supabase.table("analysis_results").insert({
                "analysis_type": "suggest_fix",
                "shap_features": shap_results,
                "llm_suggestion": llm_result,
                "applied": False,
            }).execute()
        except Exception:
            pass

        return {
            "status": "success",
            "shap_analysis": shap_results,
            "llm_suggestion": llm_result,
        }

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"분석 실패: {str(e)}")


@router.post("/apply-fix")
async def apply_fix(request: ApplyFixRequest):
    """수정 제안을 config에 적용"""
    try:
        from app.database.supabase_client import get_supabase
        from app.analytics.config_patcher import apply_config_patch

        patches = request.patches

        # analysis_id로 패치 가져오기
        if not patches and request.analysis_id:
            supabase = get_supabase()
            result = supabase.table("analysis_results") \
                .select("llm_suggestion") \
                .eq("id", request.analysis_id) \
                .limit(1) \
                .execute()

            if not result.data:
                raise HTTPException(status_code=404, detail="분석 결과를 찾을 수 없습니다")

            llm_suggestion = result.data[0].get("llm_suggestion", {})
            patches = llm_suggestion.get("config_patches", [])

        if not patches:
            return {"status": "no_patches", "message": "적용할 패치가 없습니다"}

        # 패치 적용
        patch_result = apply_config_patch(patches, dry_run=request.dry_run)

        # 이력 저장 (실제 적용 시)
        if not request.dry_run and patch_result.get("applied"):
            try:
                supabase = get_supabase()
                for applied in patch_result["applied"]:
                    supabase.table("config_patches").insert({
                        "analysis_id": request.analysis_id,
                        "config_file": applied["file"],
                        "patch_type": "update",
                        "before_value": {"value": applied["before"]},
                        "after_value": {"value": applied["after"]},
                        "status": "applied",
                    }).execute()

                # analysis_results 업데이트
                if request.analysis_id:
                    supabase.table("analysis_results") \
                        .update({"applied": True, "applied_at": datetime.utcnow().isoformat()}) \
                        .eq("id", request.analysis_id) \
                        .execute()
            except Exception:
                pass

        return {"status": "success", "result": patch_result}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"적용 실패: {str(e)}")


@router.get("/history")
async def get_history(
    limit: int = Query(20, le=100),
    status: Optional[str] = Query(None),
):
    """적용 이력 조회"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        query = supabase.table("config_patches") \
            .select("*") \
            .order("created_at", desc=True) \
            .limit(limit)

        if status:
            query = query.eq("status", status)

        result = query.execute()
        return {"data": result.data, "count": len(result.data)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"이력 조회 실패: {str(e)}")


# ============================================================
# Phase 1: Block Rate Timeseries + Stats Summary
# ============================================================

@router.get("/block-rate/timeseries")
async def block_rate_timeseries(
    hours: int = Query(24, le=168),
):
    """시간대별 차단율 타임시리즈 (hourly buckets)"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        since = (datetime.utcnow() - timedelta(hours=hours)).isoformat()

        result = supabase.table("device_fingerprints") \
            .select("http_status_code, created_at") \
            .gte("created_at", since) \
            .order("created_at", desc=False) \
            .limit(5000) \
            .execute()

        # Hourly bucket aggregation
        buckets: Dict[str, Dict[str, int]] = {}
        for row in (result.data or []):
            ts = row.get("created_at", "")
            if not ts:
                continue
            # Truncate to hour: "2026-02-15T14:xx" → "2026-02-15T14:00"
            hour_key = ts[:13] + ":00"
            if hour_key not in buckets:
                buckets[hour_key] = {"passed": 0, "blocked": 0}
            status_code = row.get("http_status_code", 0) or 0
            if status_code == 200:
                buckets[hour_key]["passed"] += 1
            elif status_code in (418, 403, 429):
                buckets[hour_key]["blocked"] += 1

        # Sort by time and build response
        sorted_hours = sorted(buckets.keys())
        timeseries = []
        for h in sorted_hours:
            b = buckets[h]
            total = b["passed"] + b["blocked"]
            timeseries.append({
                "hour": h,
                "passed": b["passed"],
                "blocked": b["blocked"],
                "total": total,
                "block_rate": round(b["blocked"] / total * 100, 1) if total > 0 else 0,
            })

        return {"timeseries": timeseries, "hours": hours}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"타임시리즈 조회 실패: {str(e)}")


@router.get("/stats/summary")
async def stats_summary():
    """24시간 종합 통계 (대시보드 stat cards용)"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        since_24h = (datetime.utcnow() - timedelta(hours=24)).isoformat()

        # Total fingerprints (24h)
        all_fps = supabase.table("device_fingerprints") \
            .select("http_status_code") \
            .gte("created_at", since_24h) \
            .limit(5000) \
            .execute()

        total = len(all_fps.data) if all_fps.data else 0
        passed = sum(1 for r in (all_fps.data or []) if (r.get("http_status_code") or 0) == 200)
        blocked = sum(1 for r in (all_fps.data or []) if (r.get("http_status_code") or 0) in (418, 403, 429))
        block_rate = round(blocked / (passed + blocked) * 100, 1) if (passed + blocked) > 0 else 0

        # Auto-applied patches count
        applied_patches = supabase.table("config_patches") \
            .select("id", count="exact") \
            .eq("status", "applied") \
            .execute()
        applied_count = applied_patches.count if applied_patches.count is not None else len(applied_patches.data or [])

        # Latest model accuracy
        latest_analysis = supabase.table("analysis_results") \
            .select("shap_features") \
            .order("created_at", desc=True) \
            .limit(1) \
            .execute()

        model_accuracy = 0.0
        if latest_analysis.data:
            shap = latest_analysis.data[0].get("shap_features") or {}
            model_accuracy = shap.get("model_accuracy", 0.0)

        # Unique devices reporting (24h)
        device_fps = supabase.table("device_fingerprints") \
            .select("device_id") \
            .gte("created_at", since_24h) \
            .limit(5000) \
            .execute()
        unique_devices = len(set(r.get("device_id", "") for r in (device_fps.data or []) if r.get("device_id")))

        return {
            "total_fingerprints": total,
            "passed": passed,
            "blocked": blocked,
            "block_rate": block_rate,
            "applied_patches": applied_count,
            "model_accuracy": round(model_accuracy, 4),
            "unique_devices_reporting": unique_devices,
            "period_hours": 24,
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"통계 조회 실패: {str(e)}")


# ============================================================
# Phase 2: Device Health Metrics
# ============================================================

@router.get("/device-health")
async def device_health(
    limit: int = Query(100, le=500),
):
    """디바이스별 건강 지표"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        since_24h = (datetime.utcnow() - timedelta(hours=24)).isoformat()

        # Get all fingerprints in last 24h
        fps = supabase.table("device_fingerprints") \
            .select("device_id, device_model, http_status_code, created_at, features") \
            .gte("created_at", since_24h) \
            .order("created_at", desc=True) \
            .limit(5000) \
            .execute()

        # Aggregate per device
        devices: Dict[str, Dict[str, Any]] = {}
        for row in (fps.data or []):
            did = row.get("device_id", "")
            if not did:
                continue
            if did not in devices:
                devices[did] = {
                    "device_id": did,
                    "device_model": row.get("device_model", ""),
                    "total": 0,
                    "passed": 0,
                    "blocked": 0,
                    "nav_times": [],
                    "last_blocked_at": None,
                    "last_seen": row.get("created_at"),
                }
            d = devices[did]
            d["total"] += 1
            status_code = row.get("http_status_code", 0) or 0
            if status_code == 200:
                d["passed"] += 1
            elif status_code in (418, 403, 429):
                d["blocked"] += 1
                if not d["last_blocked_at"]:
                    d["last_blocked_at"] = row.get("created_at")

            # Navigation timing
            features = row.get("features") or {}
            nav_ms = features.get("navigation_timing_ms")
            if nav_ms and isinstance(nav_ms, (int, float)) and nav_ms > 0:
                d["nav_times"].append(nav_ms)

        # Build response
        result = []
        for d in devices.values():
            total_decisive = d["passed"] + d["blocked"]
            success_rate = round(d["passed"] / total_decisive * 100, 1) if total_decisive > 0 else 0
            avg_nav = round(sum(d["nav_times"]) / len(d["nav_times"]), 0) if d["nav_times"] else 0
            result.append({
                "device_id": d["device_id"],
                "device_model": d["device_model"],
                "total_tasks": d["total"],
                "success_count": d["passed"],
                "block_count": d["blocked"],
                "success_rate": success_rate,
                "avg_navigation_ms": avg_nav,
                "last_blocked_at": d["last_blocked_at"],
                "last_seen": d["last_seen"],
            })

        # Sort by success_rate ascending (worst first)
        result.sort(key=lambda x: x["success_rate"])

        return {"devices": result[:limit], "count": len(result)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"디바이스 건강 조회 실패: {str(e)}")


@router.get("/device-health/summary")
async def device_health_summary():
    """전체 디바이스 건강 요약"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        since_24h = (datetime.utcnow() - timedelta(hours=24)).isoformat()

        fps = supabase.table("device_fingerprints") \
            .select("device_id, http_status_code") \
            .gte("created_at", since_24h) \
            .limit(5000) \
            .execute()

        # Per-device aggregation
        devices: Dict[str, Dict[str, int]] = {}
        for row in (fps.data or []):
            did = row.get("device_id", "")
            if not did:
                continue
            if did not in devices:
                devices[did] = {"passed": 0, "blocked": 0}
            status_code = row.get("http_status_code", 0) or 0
            if status_code == 200:
                devices[did]["passed"] += 1
            elif status_code in (418, 403, 429):
                devices[did]["blocked"] += 1

        # Calculate per-device success rates
        device_rates = []
        for did, counts in devices.items():
            total = counts["passed"] + counts["blocked"]
            rate = counts["passed"] / total * 100 if total > 0 else 0
            device_rates.append({"device_id": did, "success_rate": round(rate, 1), "total": total})

        device_rates.sort(key=lambda x: x["success_rate"])
        avg_rate = round(sum(d["success_rate"] for d in device_rates) / len(device_rates), 1) if device_rates else 0

        return {
            "total_devices_reporting": len(device_rates),
            "avg_success_rate": avg_rate,
            "worst_performing": device_rates[:10],
            "best_performing": list(reversed(device_rates[-10:])),
            "period_hours": 24,
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"건강 요약 조회 실패: {str(e)}")


@router.get("/device-health/{device_id}/history")
async def device_health_history(
    device_id: str,
    days: int = Query(7, le=30),
):
    """특정 디바이스의 일별 건강 이력"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        since = (datetime.utcnow() - timedelta(days=days)).isoformat()

        fps = supabase.table("device_fingerprints") \
            .select("http_status_code, created_at, features") \
            .eq("device_id", device_id) \
            .gte("created_at", since) \
            .order("created_at", desc=False) \
            .limit(2000) \
            .execute()

        # Daily aggregation
        daily: Dict[str, Dict[str, Any]] = {}
        for row in (fps.data or []):
            ts = row.get("created_at", "")
            if not ts:
                continue
            day_key = ts[:10]  # "2026-02-15"
            if day_key not in daily:
                daily[day_key] = {"passed": 0, "blocked": 0, "nav_times": []}
            status_code = row.get("http_status_code", 0) or 0
            if status_code == 200:
                daily[day_key]["passed"] += 1
            elif status_code in (418, 403, 429):
                daily[day_key]["blocked"] += 1
            features = row.get("features") or {}
            nav_ms = features.get("navigation_timing_ms")
            if nav_ms and isinstance(nav_ms, (int, float)) and nav_ms > 0:
                daily[day_key]["nav_times"].append(nav_ms)

        history = []
        for day in sorted(daily.keys()):
            d = daily[day]
            total = d["passed"] + d["blocked"]
            history.append({
                "date": day,
                "passed": d["passed"],
                "blocked": d["blocked"],
                "total": total,
                "success_rate": round(d["passed"] / total * 100, 1) if total > 0 else 0,
                "avg_navigation_ms": round(sum(d["nav_times"]) / len(d["nav_times"]), 0) if d["nav_times"] else 0,
            })

        return {"device_id": device_id, "history": history, "days": days}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"디바이스 이력 조회 실패: {str(e)}")


# ============================================================
# Phase 3: Model Performance + Config Impact
# ============================================================

@router.get("/model-performance")
async def model_performance(
    limit: int = Query(20, le=100),
):
    """ML 모델 성능 추이 (analysis_results에서 accuracy 추출)"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        results = supabase.table("analysis_results") \
            .select("id, analysis_type, shap_features, created_at") \
            .order("created_at", desc=True) \
            .limit(limit) \
            .execute()

        performance = []
        for row in (results.data or []):
            shap = row.get("shap_features") or {}
            accuracy = shap.get("model_accuracy", 0)
            sample_counts = shap.get("sample_counts", {})
            model_version = shap.get("model_version", "1.0.0")
            performance.append({
                "id": row.get("id"),
                "analysis_type": row.get("analysis_type"),
                "model_accuracy": accuracy,
                "model_version": model_version,
                "sample_passed": sample_counts.get("passed", 0),
                "sample_blocked": sample_counts.get("blocked", 0),
                "created_at": row.get("created_at"),
            })

        # Reverse for chronological order
        performance.reverse()

        return {"performance": performance, "count": len(performance)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"모델 성능 조회 실패: {str(e)}")


@router.get("/config-impact")
async def config_impact(
    limit: int = Query(10, le=50),
):
    """Config 패치 적용 전후 차단율 비교"""
    try:
        from app.database.supabase_client import get_supabase
        supabase = get_supabase()

        # Get applied patches with timestamps
        patches = supabase.table("config_patches") \
            .select("id, config_file, before_value, after_value, created_at, analysis_id") \
            .eq("status", "applied") \
            .order("created_at", desc=True) \
            .limit(limit) \
            .execute()

        impacts = []
        for patch in (patches.data or []):
            patch_time = patch.get("created_at", "")
            if not patch_time:
                continue

            # Parse patch timestamp
            try:
                patch_dt = datetime.fromisoformat(patch_time.replace("Z", "+00:00").replace("+00:00", ""))
            except (ValueError, TypeError):
                continue

            # 24h before patch
            before_start = (patch_dt - timedelta(hours=24)).isoformat()
            before_end = patch_dt.isoformat()

            # 24h after patch
            after_start = patch_dt.isoformat()
            after_end = (patch_dt + timedelta(hours=24)).isoformat()

            # Block rate BEFORE
            before_fps = supabase.table("device_fingerprints") \
                .select("http_status_code") \
                .gte("created_at", before_start) \
                .lte("created_at", before_end) \
                .limit(2000) \
                .execute()

            before_passed = sum(1 for r in (before_fps.data or []) if (r.get("http_status_code") or 0) == 200)
            before_blocked = sum(1 for r in (before_fps.data or []) if (r.get("http_status_code") or 0) in (418, 403, 429))
            before_total = before_passed + before_blocked
            before_rate = round(before_blocked / before_total * 100, 1) if before_total > 0 else 0

            # Block rate AFTER
            after_fps = supabase.table("device_fingerprints") \
                .select("http_status_code") \
                .gte("created_at", after_start) \
                .lte("created_at", after_end) \
                .limit(2000) \
                .execute()

            after_passed = sum(1 for r in (after_fps.data or []) if (r.get("http_status_code") or 0) == 200)
            after_blocked = sum(1 for r in (after_fps.data or []) if (r.get("http_status_code") or 0) in (418, 403, 429))
            after_total = after_passed + after_blocked
            after_rate = round(after_blocked / after_total * 100, 1) if after_total > 0 else 0

            delta = round(after_rate - before_rate, 1)
            verdict = "improved" if delta < -2 else ("degraded" if delta > 2 else "neutral")

            impacts.append({
                "patch_id": patch.get("id"),
                "config_file": patch.get("config_file"),
                "applied_at": patch_time,
                "before_value": patch.get("before_value"),
                "after_value": patch.get("after_value"),
                "block_rate_before": before_rate,
                "block_rate_after": after_rate,
                "delta": delta,
                "verdict": verdict,
                "samples_before": before_total,
                "samples_after": after_total,
            })

        return {"impacts": impacts, "count": len(impacts)}

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"config 영향 분석 실패: {str(e)}")
