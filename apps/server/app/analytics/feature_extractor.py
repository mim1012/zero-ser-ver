"""
피처 추출 모듈 — device_fingerprints에서 ML용 수치 피처 추출

v1.1.0: ua_chrome_sec_ch_ua_mismatch, header_order_hash,
         time_since_last_request_ms, requests_per_session, config_version_hash 추가
"""
import hashlib
import re
from typing import List, Dict, Any, Optional

# 모델 버전 — 피처셋 변경 시 업데이트
MODEL_VERSION = "1.1.0"

# ML에 사용할 수치 피처 목록
NUMERICAL_FEATURES = [
    "chrome_major_version",
    "android_api_level",
    "has_sec_ch_ua",
    "has_priority_header",
    "ua_contains_android_13_plus",
    "tls_version_numeric",
    "screen_dpi",
    "navigation_timing_ms",
    "touch_interval_variance",
    "accept_encoding_has_zstd",
    "sec_ch_ua_platform_version_numeric",
    "screen_width",
    "screen_height",
    # v1.1.0 new features
    "ua_chrome_sec_ch_ua_mismatch",
    "header_order_hash",
    "time_since_last_request_ms",
    "requests_per_session",
    "config_version_hash",
]


def extract_chrome_major(chrome_version: str) -> int:
    """Chrome 버전 문자열에서 major 버전 추출"""
    if not chrome_version:
        return 0
    match = re.match(r"(\d+)", str(chrome_version))
    return int(match.group(1)) if match else 0


def extract_android_version_from_ua(ua: str) -> float:
    """UA 문자열에서 Android 버전 추출"""
    if not ua:
        return 0.0
    match = re.search(r"Android\s+([\d.]+)", ua)
    if match:
        try:
            return float(match.group(1).split(".")[0])
        except (ValueError, IndexError):
            return 0.0
    return 0.0


def extract_features(fingerprint: Dict[str, Any]) -> Dict[str, float]:
    """
    단일 fingerprint 레코드에서 수치 피처 추출.

    Args:
        fingerprint: device_fingerprints 테이블의 1행 (features JSONB 포함)

    Returns:
        ML 모델에 입력할 수치 피처 딕셔너리
    """
    features = fingerprint.get("features") or {}
    result = {}

    # Chrome major version
    chrome_ver = features.get("chrome_version", "") or fingerprint.get("chrome_version", "")
    result["chrome_major_version"] = extract_chrome_major(chrome_ver)

    # Android API level
    result["android_api_level"] = int(features.get("sdk_int", 0) or 0)

    # User-Agent 분석
    ua = features.get("user_agent", "") or fingerprint.get("user_agent", "")

    # Android 13+ 여부
    android_ver = extract_android_version_from_ua(ua)
    result["ua_contains_android_13_plus"] = 1.0 if android_ver >= 13 else 0.0

    # TLS 버전 (1.2=12, 1.3=13)
    tls = features.get("tls_version", "1.2")
    result["tls_version_numeric"] = 13.0 if "1.3" in str(tls) else 12.0

    # 화면 정보
    result["screen_dpi"] = float(features.get("screen_dpi", 0) or 0)
    result["screen_width"] = float(features.get("screen_width", 0) or 0)
    result["screen_height"] = float(features.get("screen_height", 0) or 0)

    # 네비게이션 타이밍
    result["navigation_timing_ms"] = float(features.get("navigation_timing_ms", 0) or 0)

    # 터치 패턴 분산
    result["touch_interval_variance"] = float(features.get("touch_pattern_variance", 0) or 0)

    # 응답 헤더 기반 피처 (서버 config에서 유추)
    response_headers = features.get("response_headers", {}) or {}

    # sec-ch-ua 존재 여부 (config headers에서)
    result["has_sec_ch_ua"] = 1.0 if features.get("sec_ch_ua_present") else 0.0

    # priority 헤더 존재 여부
    result["has_priority_header"] = 1.0 if "priority" in str(response_headers).lower() else 0.0

    # accept-encoding에 zstd 포함 여부
    accept_encoding = str(features.get("accept_encoding", ""))
    result["accept_encoding_has_zstd"] = 1.0 if "zstd" in accept_encoding else 0.0

    # sec-ch-ua-platform-version 수치
    platform_ver = features.get("sec_ch_ua_platform_version", "0")
    try:
        result["sec_ch_ua_platform_version_numeric"] = float(str(platform_ver).replace('"', '').split('.')[0])
    except (ValueError, IndexError):
        result["sec_ch_ua_platform_version_numeric"] = 0.0

    # ── v1.1.0 new features ──

    # 1. UA Chrome version vs sec-ch-ua version mismatch
    #    UA에 Chrome/131 인데 sec-ch-ua에 "131" 아닌 경우 → 봇 탐지 트리거
    ua_chrome = extract_chrome_major(chrome_ver)
    sec_ch_ua_raw = str(features.get("sec_ch_ua", "") or "")
    sec_ch_ua_ver = 0
    sec_ch_match = re.search(r'"Chromium";v="(\d+)"', sec_ch_ua_raw)
    if not sec_ch_match:
        sec_ch_match = re.search(r'(\d+)', sec_ch_ua_raw)
    if sec_ch_match:
        try:
            sec_ch_ua_ver = int(sec_ch_match.group(1))
        except (ValueError, IndexError):
            pass
    result["ua_chrome_sec_ch_ua_mismatch"] = 1.0 if (ua_chrome > 0 and sec_ch_ua_ver > 0 and ua_chrome != sec_ch_ua_ver) else 0.0

    # 2. Header order hash — response headers 키 순서의 해시 (일관성 체크)
    header_keys = list((response_headers or {}).keys())
    header_str = ",".join(header_keys) if header_keys else ""
    result["header_order_hash"] = float(int(hashlib.md5(header_str.encode()).hexdigest()[:8], 16) % 10000) if header_str else 0.0

    # 3. Time since last request (ms) — 요청 간격 패턴
    result["time_since_last_request_ms"] = float(features.get("time_since_last_request_ms", 0) or 0)

    # 4. Requests per session — 세션 내 총 요청 수
    result["requests_per_session"] = float(features.get("requests_per_session", 0) or fingerprint.get("touch_sample_count", 0) or 0)

    # 5. Config version hash — 디바이스가 사용 중인 config 버전
    config_ver = str(features.get("config_version", "") or "")
    result["config_version_hash"] = float(int(hashlib.md5(config_ver.encode()).hexdigest()[:8], 16) % 10000) if config_ver else 0.0

    return result


def extract_batch(fingerprints: List[Dict[str, Any]]) -> List[Dict[str, float]]:
    """여러 fingerprint에서 피처 일괄 추출"""
    return [extract_features(fp) for fp in fingerprints]


def get_feature_names() -> List[str]:
    """ML 모델에 사용되는 피처 이름 목록"""
    return NUMERICAL_FEATURES.copy()


def get_model_version() -> str:
    """현재 ML 피처셋 버전"""
    return MODEL_VERSION
