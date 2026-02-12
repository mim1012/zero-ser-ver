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
# API 엔드포인트
# ============================================================

@router.get("/mobile", response_model=MobileHeaderResponse)
async def get_mobile_header():
    """
    랜덤 모바일 헤더 가져오기
    
    - is_active=true인 헤더 중 랜덤 선택
    - 실제 모바일 기기의 User-Agent 및 Client Hints 제공
    """
    try:
        supabase = get_supabase()
        
        # is_active=true인 모바일 헤더 모두 조회
        result = supabase.table('mobile_headers') \
            .select('*') \
            .eq('is_active', True) \
            .execute()
        
        if not result.data:
            raise HTTPException(status_code=404, detail="사용 가능한 모바일 헤더가 없습니다")
        
        # 랜덤 선택
        header = random.choice(result.data)
        
        return MobileHeaderResponse(
            user_agent=header['user_agent'],
            sec_ch_ua=header.get('sec_ch_ua'),
            sec_ch_ua_mobile=header.get('sec_ch_ua_mobile'),
            sec_ch_ua_platform=header.get('sec_ch_ua_platform'),
            accept_language=header.get('accept_language', 'ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7')
        )
        
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"모바일 헤더 조회 실패: {str(e)}")
