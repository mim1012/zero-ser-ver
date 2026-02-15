"""
자동 학습 루프 — 주기적으로 418 로그 분석 + config 자동 수정

BackgroundTasks 또는 startup event로 실행.
"""
import asyncio
import logging
import os
from datetime import datetime, timedelta
from typing import Optional

logger = logging.getLogger(__name__)

ANALYTICS_AUTO_APPLY = os.getenv("ANALYTICS_AUTO_APPLY", "false").lower() == "true"
ANALYTICS_CONFIDENCE_THRESHOLD = float(os.getenv("ANALYTICS_CONFIDENCE_THRESHOLD", "0.8"))
ANALYTICS_LOOP_INTERVAL = int(os.getenv("ANALYTICS_LOOP_INTERVAL", "1800"))  # 30분
MIN_SAMPLES = 5  # 최소 샘플 수


async def auto_learning_loop():
    """
    자동 학습 루프 (매 30분).

    1. 최근 30분 device_fingerprints에서 418 로그 수집
    2. 같은 시간대 200 로그 수집
    3. 충분한 샘플(각 5건+)이면 ML 분석 트리거
    4. SHAP 차이점 추출
    5. Claude에게 수정 제안 요청
    6. confidence > threshold이면 자동 적용
    7. analysis_results 테이블에 결과 저장
    """
    logger.info(f"학습 루프 시작 (interval={ANALYTICS_LOOP_INTERVAL}s, auto_apply={ANALYTICS_AUTO_APPLY})")

    while True:
        try:
            await _run_analysis_cycle()
        except Exception as e:
            logger.error(f"학습 루프 오류: {e}")

        await asyncio.sleep(ANALYTICS_LOOP_INTERVAL)


async def _run_analysis_cycle():
    """단일 분석 사이클 실행"""
    from app.database.supabase_client import get_supabase
    from app.analytics.model import train_and_compare
    from app.analytics.llm_analyzer import suggest_fix
    from app.analytics.config_patcher import apply_config_patch, get_current_configs

    supabase = get_supabase()
    since = (datetime.utcnow() - timedelta(seconds=ANALYTICS_LOOP_INTERVAL)).isoformat()

    # 1. 최근 차단(418/403) fingerprints 수집
    blocked_result = supabase.table("device_fingerprints") \
        .select("*") \
        .in_("http_status_code", [418, 403, 429]) \
        .gte("created_at", since) \
        .order("created_at", desc=True) \
        .limit(100) \
        .execute()

    blocked_data = blocked_result.data if blocked_result.data else []

    # 2. 최근 성공(200) fingerprints 수집
    passed_result = supabase.table("device_fingerprints") \
        .select("*") \
        .eq("http_status_code", 200) \
        .gte("created_at", since) \
        .order("created_at", desc=True) \
        .limit(100) \
        .execute()

    passed_data = passed_result.data if passed_result.data else []

    # 3. 충분한 샘플 확인
    if len(blocked_data) < MIN_SAMPLES or len(passed_data) < MIN_SAMPLES:
        logger.info(
            f"분석 스킵: 샘플 부족 (passed={len(passed_data)}, blocked={len(blocked_data)}, min={MIN_SAMPLES})"
        )
        return

    logger.info(f"분석 시작: passed={len(passed_data)}, blocked={len(blocked_data)}")

    # 4. ML 분석 (RF + SHAP)
    shap_results = train_and_compare(passed_data, blocked_data)
    logger.info(f"ML 분석 완료: accuracy={shap_results['model_accuracy']}")

    # 5. Claude 수정 제안
    current_config = get_current_configs()
    llm_result = await suggest_fix(
        passed_features=shap_results.get("passed_means", {}),
        blocked_features=shap_results.get("blocked_means", {}),
        shap_results=shap_results,
        current_config=current_config,
    )

    confidence = llm_result.get("confidence", 0)
    logger.info(f"LLM 제안 완료: confidence={confidence}, patches={len(llm_result.get('config_patches', []))}")

    # 6. analysis_results 저장
    analysis_record = {
        "analysis_type": "auto_learning",
        "source_model": _get_dominant_model(passed_data),
        "target_model": _get_dominant_model(blocked_data),
        "shap_features": shap_results,
        "llm_suggestion": llm_result,
        "applied": False,
    }

    try:
        insert_result = supabase.table("analysis_results").insert(analysis_record).execute()
        analysis_id = insert_result.data[0]["id"] if insert_result.data else None
    except Exception as e:
        logger.error(f"analysis_results 저장 실패: {e}")
        analysis_id = None

    # 7. 자동 적용 판단
    patches = llm_result.get("config_patches", [])
    if patches and ANALYTICS_AUTO_APPLY and confidence >= ANALYTICS_CONFIDENCE_THRESHOLD:
        logger.info(f"자동 적용 시작 (confidence={confidence} >= {ANALYTICS_CONFIDENCE_THRESHOLD})")
        patch_result = apply_config_patch(patches, dry_run=False)

        # config_patches 테이블에 이력 저장
        if analysis_id:
            for applied in patch_result.get("applied", []):
                try:
                    supabase.table("config_patches").insert({
                        "analysis_id": analysis_id,
                        "config_file": applied["file"],
                        "patch_type": "update",
                        "before_value": {"value": applied["before"]},
                        "after_value": {"value": applied["after"]},
                        "status": "applied",
                    }).execute()
                except Exception as e:
                    logger.error(f"config_patches 저장 실패: {e}")

            # analysis_results applied=True 업데이트
            try:
                supabase.table("analysis_results") \
                    .update({"applied": True, "applied_at": datetime.utcnow().isoformat()}) \
                    .eq("id", analysis_id) \
                    .execute()
            except Exception as e:
                logger.error(f"analysis_results 업데이트 실패: {e}")

        logger.info(f"자동 적용 완료: {len(patch_result.get('applied', []))}건 적용")
    elif patches:
        logger.info(
            f"수동 승인 대기: confidence={confidence} < {ANALYTICS_CONFIDENCE_THRESHOLD} "
            f"또는 auto_apply={ANALYTICS_AUTO_APPLY}"
        )


def _get_dominant_model(fingerprints: list) -> str:
    """가장 많은 디바이스 모델 반환"""
    models = {}
    for fp in fingerprints:
        model = fp.get("device_model") or (fp.get("features", {}) or {}).get("device_model", "unknown")
        models[model] = models.get(model, 0) + 1
    if not models:
        return "unknown"
    return max(models, key=models.get)
