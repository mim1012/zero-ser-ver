"""
모바일 헤더 제공 API
- 랜덤 모바일 헤더 가져오기
"""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional
import random
from app.database.supabase_client import get_supabase

router = APIRouter()

# ============================================================
# Response 모델
# ============================================================

class MobileHeaderResponse(BaseModel):
    user_agent: str
    sec_ch_ua: Optional[str]
    sec_ch_ua_mobile: Optional[str]
    sec_ch_ua_platform: Optional[str]
    accept_language: str

# ============================================================
# 폴백 헤더 풀 (DB 비어있을 때 사용)
# Samsung Galaxy + Chrome 최신 버전 (2026 기준)
# ============================================================

_FALLBACK_HEADERS = [
    {
        "user_agent": "Mozilla/5.0 (Linux; Android 14; SM-S928N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.98 Mobile Safari/537.36",
        "sec_ch_ua": '"Not A(Brand";v="99", "Google Chrome";v="133", "Chromium";v="133"',
        "sec_ch_ua_mobile": "?1",
        "sec_ch_ua_platform": '"Android"',
        "accept_language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    },
    {
        "user_agent": "Mozilla/5.0 (Linux; Android 14; SM-G991N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.98 Mobile Safari/537.36",
        "sec_ch_ua": '"Not A(Brand";v="99", "Google Chrome";v="133", "Chromium";v="133"',
        "sec_ch_ua_mobile": "?1",
        "sec_ch_ua_platform": '"Android"',
        "accept_language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    },
    {
        "user_agent": "Mozilla/5.0 (Linux; Android 14; SM-A546N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.98 Mobile Safari/537.36",
        "sec_ch_ua": '"Not A(Brand";v="99", "Google Chrome";v="133", "Chromium";v="133"',
        "sec_ch_ua_mobile": "?1",
        "sec_ch_ua_platform": '"Android"',
        "accept_language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    },
    {
        "user_agent": "Mozilla/5.0 (Linux; Android 13; SM-G977N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.98 Mobile Safari/537.36",
        "sec_ch_ua": '"Not A(Brand";v="99", "Google Chrome";v="133", "Chromium";v="133"',
        "sec_ch_ua_mobile": "?1",
        "sec_ch_ua_platform": '"Android"',
        "accept_language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    },
]

# ============================================================
# API 엔드포인트
# ============================================================

@router.get("/mobile", response_model=MobileHeaderResponse)
async def get_mobile_header():
    """
    랜덤 모바일 헤더 가져오기

    - is_active=true인 헤더 중 랜덤 선택
    - DB 비어있으면 폴백 풀에서 반환
    - 실제 모바일 기기의 User-Agent 및 Client Hints 제공
    """
    try:
        supabase = get_supabase()

        result = supabase.table('mobile_headers') \
            .select('*') \
            .eq('is_active', True) \
            .execute()

        if result.data:
            header = random.choice(result.data)
        else:
            # DB 비어있음 → 폴백 사용
            header = random.choice(_FALLBACK_HEADERS)

        return MobileHeaderResponse(
            user_agent=header['user_agent'],
            sec_ch_ua=header.get('sec_ch_ua'),
            sec_ch_ua_mobile=header.get('sec_ch_ua_mobile'),
            sec_ch_ua_platform=header.get('sec_ch_ua_platform'),
            accept_language=header.get('accept_language', 'ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7')
        )

    except Exception as e:
        # 테이블 없음 포함 모든 에러 → 폴백
        header = random.choice(_FALLBACK_HEADERS)
        return MobileHeaderResponse(
            user_agent=header['user_agent'],
            sec_ch_ua=header.get('sec_ch_ua'),
            sec_ch_ua_mobile=header.get('sec_ch_ua_mobile'),
            sec_ch_ua_platform=header.get('sec_ch_ua_platform'),
            accept_language=header.get('accept_language', 'ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7')
        )
