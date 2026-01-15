"""
JSON 기반 동적 설정 관리 API
Option C 구현: 서버에서 헤더, User-Agent, WebView 설정을 JSON으로 관리
"""
from fastapi import APIRouter, HTTPException
from typing import Dict, Any, Optional
import json
import os
from pathlib import Path

router = APIRouter()

# 설정 파일 경로
CONFIG_DIR = Path(__file__).parent.parent.parent / "config"
HEADERS_CONFIG_FILE = CONFIG_DIR / "headers.json"
USERAGENTS_CONFIG_FILE = CONFIG_DIR / "user_agents.json"
WEBVIEW_CONFIG_FILE = CONFIG_DIR / "webview_settings.json"

# 설정 디렉토리 생성
CONFIG_DIR.mkdir(exist_ok=True)


def load_json_config(file_path: Path) -> Dict[str, Any]:
    """JSON 설정 파일 로드"""
    if not file_path.exists():
        return {}
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception as e:
        print(f"Error loading {file_path}: {e}")
        return {}


def save_json_config(file_path: Path, data: Dict[str, Any]):
    """JSON 설정 파일 저장"""
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"Error saving {file_path}: {e}")
        raise


@router.get("/config/headers")
async def get_headers_config(
    profile: Optional[str] = None
):
    """
    헤더 설정 조회
    
    Args:
        profile: 특정 프로필 (예: "chrome_143", "chrome_144")
                None이면 전체 설정 반환
    
    Returns:
        헤더 설정 JSON
    """
    config = load_json_config(HEADERS_CONFIG_FILE)
    
    if profile:
        if profile not in config:
            raise HTTPException(status_code=404, detail=f"Profile '{profile}' not found")
        return {profile: config[profile]}
    
    return config


@router.get("/config/user-agents")
async def get_user_agents_config(
    device_model: Optional[str] = None
):
    """
    User-Agent 설정 조회
    
    Args:
        device_model: 특정 기기 모델 (예: "SM-G998N", "SM-S918B")
                     None이면 전체 설정 반환
    
    Returns:
        User-Agent 설정 JSON
    """
    config = load_json_config(USERAGENTS_CONFIG_FILE)
    
    if device_model:
        if device_model not in config:
            raise HTTPException(status_code=404, detail=f"Device model '{device_model}' not found")
        return {device_model: config[device_model]}
    
    return config


@router.get("/config/webview")
async def get_webview_config():
    """
    WebView 설정 조회
    
    Returns:
        WebView 설정 JSON
    """
    return load_json_config(WEBVIEW_CONFIG_FILE)


@router.get("/config/full")
async def get_full_config(
    device_model: Optional[str] = None,
    chrome_version: Optional[str] = None
):
    """
    전체 설정 조회 (헤더 + User-Agent + WebView)
    Android 앱이 한 번의 요청으로 모든 설정을 받을 수 있도록 함
    
    Args:
        device_model: 기기 모델 (예: "SM-G998N")
        chrome_version: Chrome 버전 (예: "143")
    
    Returns:
        통합 설정 JSON
    """
    headers_config = load_json_config(HEADERS_CONFIG_FILE)
    ua_config = load_json_config(USERAGENTS_CONFIG_FILE)
    webview_config = load_json_config(WEBVIEW_CONFIG_FILE)
    
    # 기본 프로필 설정
    profile_key = f"chrome_{chrome_version}" if chrome_version else "chrome_143"
    
    # 기기 모델별 User-Agent 선택
    user_agent = None
    if device_model and device_model in ua_config:
        ua_list = ua_config[device_model]
        if chrome_version:
            # 특정 Chrome 버전의 UA 찾기
            for ua_item in ua_list:
                if ua_item.get("chrome_version") == chrome_version:
                    user_agent = ua_item.get("user_agent")
                    break
        if not user_agent and ua_list:
            # 첫 번째 UA 사용
            user_agent = ua_list[0].get("user_agent")
    
    # 헤더 프로필 선택
    headers = headers_config.get(profile_key, {})
    
    return {
        "profile": profile_key,
        "device_model": device_model,
        "user_agent": user_agent,
        "headers": headers,
        "webview_settings": webview_config
    }


@router.post("/config/headers")
async def update_headers_config(config: Dict[str, Any]):
    """
    헤더 설정 업데이트 (관리자용)
    
    Args:
        config: 새로운 헤더 설정 JSON
    
    Returns:
        성공 메시지
    """
    save_json_config(HEADERS_CONFIG_FILE, config)
    return {"message": "Headers config updated successfully"}


@router.post("/config/user-agents")
async def update_user_agents_config(config: Dict[str, Any]):
    """
    User-Agent 설정 업데이트 (관리자용)
    
    Args:
        config: 새로운 User-Agent 설정 JSON
    
    Returns:
        성공 메시지
    """
    save_json_config(USERAGENTS_CONFIG_FILE, config)
    return {"message": "User-Agent config updated successfully"}


@router.post("/config/webview")
async def update_webview_config(config: Dict[str, Any]):
    """
    WebView 설정 업데이트 (관리자용)
    
    Args:
        config: 새로운 WebView 설정 JSON
    
    Returns:
        성공 메시지
    """
    save_json_config(WEBVIEW_CONFIG_FILE, config)
    return {"message": "WebView config updated successfully"}
