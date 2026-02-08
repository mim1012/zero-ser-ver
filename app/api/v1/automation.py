"""
네이버 쇼핑 자동화 스크립트 배포 API

Android 클라이언트에게 JavaScript 자동화 스크립트를 제공합니다.
"""

from fastapi import APIRouter, HTTPException, Query
from pathlib import Path
import hashlib
import json
from datetime import datetime
from typing import Optional

router = APIRouter()

# 스크립트 버전 정보
SCRIPT_VERSIONS = {
    "v1": {
        "version": "v1",
        "file_name": "naver_shopping_v1.js",
        "release_date": "2026-01-19",
        "description": "Initial release with Bezier scroll, 3-strategy MID search, and autocomplete ackey extraction",
        "min_android_version": "1.0.0",
        "changelog": [
            "Bezier curve scroll animation for natural behavior",
            "3-strategy MID search (URL param, URL path, ID attribute)",
            "Autocomplete click for ackey/sm parameter acquisition",
            "Human-like typing with random delays",
            "CAPTCHA and IP block detection",
            "Android bridge integration"
        ]
    }
}

# 최신 버전
LATEST_VERSION = "v1"
MIN_REQUIRED_VERSION = "v1"


@router.get("/automation/version")
async def get_version_info():
    """
    자동화 스크립트 버전 정보 조회

    Returns:
        - latest_version: 최신 버전
        - min_required_version: 최소 필수 버전
        - versions: 사용 가능한 모든 버전 정보
    """
    return {
        "latest_version": LATEST_VERSION,
        "min_required_version": MIN_REQUIRED_VERSION,
        "updated_at": datetime.now().isoformat(),
        "versions": SCRIPT_VERSIONS
    }


@router.get("/automation/script")
async def get_automation_script(
    version: str = Query(default="latest", description="스크립트 버전 (latest 또는 v1, v2, ...)"),
    platform: str = Query(default="android", description="플랫폼 (android, ios)"),
    check_only: bool = Query(default=False, description="True면 스크립트 내용 없이 메타데이터만 반환")
):
    """
    네이버 쇼핑 자동화 JavaScript 스크립트 다운로드

    Args:
        version: 스크립트 버전 (기본값: latest)
        platform: 플랫폼 (기본값: android)
        check_only: True면 메타데이터만 반환 (기본값: False)

    Returns:
        - version: 버전 정보
        - platform: 플랫폼
        - script: JavaScript 코드 (check_only=False일 때만)
        - checksum: MD5 체크섬
        - size_bytes: 파일 크기
        - metadata: 버전 메타데이터

    Raises:
        404: 스크립트 파일을 찾을 수 없음
        400: 잘못된 버전 또는 플랫폼
    """
    # 버전 확인
    if version == "latest":
        version = LATEST_VERSION

    if version not in SCRIPT_VERSIONS:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid version: {version}. Available versions: {list(SCRIPT_VERSIONS.keys())}"
        )

    # 플랫폼 확인 (현재는 android만 지원)
    if platform not in ["android"]:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid platform: {platform}. Supported platforms: android"
        )

    # 스크립트 파일 경로
    script_dir = Path(__file__).parent.parent.parent / "automation_scripts"
    script_file = script_dir / SCRIPT_VERSIONS[version]["file_name"]

    if not script_file.exists():
        raise HTTPException(
            status_code=404,
            detail=f"Script file not found: {script_file.name}"
        )

    # 파일 읽기
    try:
        with open(script_file, 'r', encoding='utf-8') as f:
            script_code = f.read()
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to read script file: {str(e)}"
        )

    # 체크섬 계산
    checksum = hashlib.md5(script_code.encode('utf-8')).hexdigest()
    size_bytes = len(script_code.encode('utf-8'))

    # 응답 구성
    response = {
        "version": version,
        "platform": platform,
        "checksum": checksum,
        "size_bytes": size_bytes,
        "metadata": SCRIPT_VERSIONS[version],
        "downloaded_at": datetime.now().isoformat()
    }

    # check_only가 False일 때만 스크립트 코드 포함
    if not check_only:
        response["script"] = script_code

    return response


@router.get("/automation/changelog")
async def get_changelog(version: Optional[str] = None):
    """
    버전별 변경 내역 조회

    Args:
        version: 특정 버전 (None이면 모든 버전)

    Returns:
        버전별 변경 내역
    """
    if version:
        if version not in SCRIPT_VERSIONS:
            raise HTTPException(
                status_code=404,
                detail=f"Version not found: {version}"
            )
        return {
            "version": version,
            "changelog": SCRIPT_VERSIONS[version]
        }
    else:
        return {
            "versions": SCRIPT_VERSIONS
        }


@router.get("/automation/compatibility")
async def check_compatibility(
    android_version: str = Query(..., description="Android 앱 버전 (예: 1.0.0)")
):
    """
    Android 앱 버전과 스크립트 호환성 확인

    Args:
        android_version: Android 앱 버전

    Returns:
        - compatible: 호환 여부
        - recommended_script_version: 권장 스크립트 버전
        - min_required_script_version: 최소 필수 스크립트 버전
    """
    # 간단한 버전 비교 (실제로는 더 정교한 로직 필요)
    try:
        version_parts = [int(x) for x in android_version.split('.')]
        major, minor, patch = version_parts[0], version_parts[1] if len(version_parts) > 1 else 0, version_parts[2] if len(version_parts) > 2 else 0

        # 버전 1.0.0 이상이면 호환
        compatible = major >= 1

        return {
            "android_version": android_version,
            "compatible": compatible,
            "recommended_script_version": LATEST_VERSION,
            "min_required_script_version": MIN_REQUIRED_VERSION,
            "message": "Compatible" if compatible else "Please update your app to version 1.0.0 or higher"
        }

    except Exception as e:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid version format: {android_version}. Expected format: X.Y.Z"
        )


@router.get("/automation/health")
async def automation_health_check():
    """
    자동화 시스템 헬스 체크

    Returns:
        시스템 상태 및 사용 가능한 스크립트 목록
    """
    script_dir = Path(__file__).parent.parent.parent / "automation_scripts"

    # 모든 스크립트 파일 확인
    available_scripts = []
    missing_scripts = []

    for version, info in SCRIPT_VERSIONS.items():
        script_file = script_dir / info["file_name"]
        if script_file.exists():
            available_scripts.append({
                "version": version,
                "file_name": info["file_name"],
                "size_bytes": script_file.stat().st_size,
                "exists": True
            })
        else:
            missing_scripts.append({
                "version": version,
                "file_name": info["file_name"],
                "exists": False
            })

    return {
        "status": "healthy" if len(missing_scripts) == 0 else "degraded",
        "latest_version": LATEST_VERSION,
        "available_scripts": available_scripts,
        "missing_scripts": missing_scripts,
        "total_versions": len(SCRIPT_VERSIONS),
        "checked_at": datetime.now().isoformat()
    }
