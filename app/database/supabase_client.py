"""
Supabase 클라이언트 설정 및 연결 관리
"""
import os
from supabase import create_client, Client
from typing import Optional

# Supabase 설정
SUPABASE_URL = os.getenv("SUPABASE_URL", "https://hdtjkaieulphqwmcjhcx.supabase.co")
SUPABASE_KEY = os.getenv("SUPABASE_KEY", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhkdGprYWlldWxwaHF3bWNqaGN4Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc2Mzg3OTMzNSwiZXhwIjoyMDc5NDU1MzM1fQ.Jn6RiB8H-_pEZ9BW9x9Mqt4fW-XTj0M3gEAShWDjOtE")

# 전역 Supabase 클라이언트
_supabase_client: Optional[Client] = None

def get_supabase() -> Client:
    """
    Supabase 클라이언트 인스턴스를 반환합니다.
    싱글톤 패턴으로 구현되어 있습니다.
    """
    global _supabase_client
    
    if _supabase_client is None:
        _supabase_client = create_client(SUPABASE_URL, SUPABASE_KEY)
    
    return _supabase_client

def test_connection() -> bool:
    """
    Supabase 연결을 테스트합니다.
    """
    try:
        client = get_supabase()
        # mobile_headers 테이블에서 1개 레코드 조회 (연결 테스트)
        result = client.table('mobile_headers').select('id').limit(1).execute()
        return True
    except Exception as e:
        print(f"Supabase 연결 실패: {str(e)}")
        return False
