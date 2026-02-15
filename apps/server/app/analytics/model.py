"""
ML 분석 모델 — RandomForest + SHAP 기반 피처 중요도 분석

성공(200) vs 차단(418) 디바이스 피처를 비교하여
차단 원인이 되는 주요 피처를 식별.
"""
import logging
from typing import List, Dict, Any, Tuple

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.model_selection import cross_val_score
import shap

from app.analytics.feature_extractor import extract_batch, get_feature_names, get_model_version

logger = logging.getLogger(__name__)


def train_and_compare(
    passed_features: List[Dict[str, Any]],
    blocked_features: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """
    성공/차단 피처를 비교 분석.

    1. 피처 추출 + 라벨링 (1=passed, 0=blocked)
    2. RandomForestClassifier 학습
    3. SHAP TreeExplainer로 피처 중요도 추출
    4. Top-N 차이점 반환

    Args:
        passed_features: 200 응답 fingerprint 레코드 목록
        blocked_features: 418/403 응답 fingerprint 레코드 목록

    Returns:
        {
            "feature_importance": [...],  # SHAP 기반 중요도 순위
            "top_differences": [...],     # 상위 10개 차이점
            "model_accuracy": float,
            "sample_counts": {"passed": N, "blocked": N},
            "passed_means": {...},
            "blocked_means": {...},
        }
    """
    # 1. 피처 추출
    passed_extracted = extract_batch(passed_features)
    blocked_extracted = extract_batch(blocked_features)

    feature_names = get_feature_names()

    # DataFrame 구성
    all_features = passed_extracted + blocked_extracted
    df = pd.DataFrame(all_features, columns=feature_names)

    # NaN 처리
    df = df.fillna(0)

    # 라벨: 1=passed, 0=blocked
    labels = np.array([1] * len(passed_extracted) + [0] * len(blocked_extracted))

    logger.info(f"ML 분석 시작: passed={len(passed_extracted)}, blocked={len(blocked_extracted)}")

    # 2. 스케일링 + RF 학습
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(df.values)

    rf = RandomForestClassifier(
        n_estimators=100,
        max_depth=10,
        random_state=42,
        class_weight="balanced",
    )
    rf.fit(X_scaled, labels)

    # 교차 검증 정확도
    try:
        cv_scores = cross_val_score(rf, X_scaled, labels, cv=min(3, len(labels) // 2), scoring="accuracy")
        accuracy = float(np.mean(cv_scores))
    except Exception:
        accuracy = float(rf.score(X_scaled, labels))

    logger.info(f"ML 모델 정확도: {accuracy:.3f}")

    # 3. SHAP 분석
    explainer = shap.TreeExplainer(rf)
    shap_values = explainer.shap_values(X_scaled)

    # SHAP values 형태 정규화 — 다양한 sklearn/shap 버전 호환
    # Case 1: list of [class_0_shap, class_1_shap] — 각 (n_samples, n_features)
    if isinstance(shap_values, list) and len(shap_values) == 2:
        shap_vals = np.abs(np.array(shap_values[1])).mean(axis=0)
    else:
        shap_array = np.array(shap_values)
        if shap_array.ndim == 3:
            # Case 2: (n_classes, n_samples, n_features) — class axis first
            if shap_array.shape[0] == 2:
                shap_vals = np.abs(shap_array[1]).mean(axis=0)
            # Case 3: (n_samples, n_features, n_classes) — class axis last
            elif shap_array.shape[2] == 2:
                shap_vals = np.abs(shap_array[:, :, 1]).mean(axis=0)
            else:
                shap_vals = np.abs(shap_array).mean(axis=(0, 2))
        else:
            # Case 4: (n_samples, n_features) — single output
            shap_vals = np.abs(shap_array).mean(axis=0)

    # 안전 검증: shap_vals 크기가 피처 수와 일치하는지 확인
    if shap_vals.shape[0] != len(feature_names):
        logger.error(
            f"SHAP shape mismatch: shap_vals={shap_vals.shape[0]}, features={len(feature_names)}"
        )
        # RF importance로 폴백
        shap_vals = rf.feature_importances_

    # 피처 중요도 순위
    feature_importance = []
    for i, name in enumerate(feature_names):
        importance_val = float(shap_vals[i].item()) if hasattr(shap_vals[i], 'item') else float(shap_vals[i])
        feature_importance.append({
            "feature": name,
            "shap_importance": importance_val,
            "rf_importance": float(rf.feature_importances_[i]),
        })
    feature_importance.sort(key=lambda x: x["shap_importance"], reverse=True)

    # 4. 통과/차단 평균값 비교
    passed_df = df.iloc[:len(passed_extracted)]
    blocked_df = df.iloc[len(passed_extracted):]
    passed_means = passed_df.mean().to_dict()
    blocked_means = blocked_df.mean().to_dict()

    # Top 차이점
    top_differences = []
    for item in feature_importance[:10]:
        name = item["feature"]
        p_mean = passed_means.get(name, 0)
        b_mean = blocked_means.get(name, 0)
        diff = p_mean - b_mean
        top_differences.append({
            "feature": name,
            "passed_mean": round(p_mean, 4),
            "blocked_mean": round(b_mean, 4),
            "difference": round(diff, 4),
            "shap_importance": item["shap_importance"],
            "direction": "passed_higher" if diff > 0 else "blocked_higher",
        })

    return {
        "feature_importance": feature_importance,
        "top_differences": top_differences,
        "model_accuracy": round(accuracy, 4),
        "model_version": get_model_version(),
        "sample_counts": {
            "passed": len(passed_extracted),
            "blocked": len(blocked_extracted),
        },
        "passed_means": {k: round(v, 4) for k, v in passed_means.items()},
        "blocked_means": {k: round(v, 4) for k, v in blocked_means.items()},
    }
