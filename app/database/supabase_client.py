"""
Supabase 클라이언트 설정 및 연결 관리
- Control DB: 기기/그룹 관리 (hdtjkaieulphqwmcjhcx)
- Production DB: 슬롯/트래픽 큐 (cwsdvgkjptuvbdtxcejt)
"""
import os
from supabase import create_client, Client
from typing import Optional

# Control DB 설정
SUPABASE_URL = os.getenv("SUPABASE_URL", "https://hdtjkaieulphqwmcjhcx.supabase.co")
SUPABASE_KEY = os.getenv("SUPABASE_KEY", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhkdGprYWlldWxwaHF3bWNqaGN4Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc2Mzg3OTMzNSwiZXhwIjoyMDc5NDU1MzM1fQ.Jn6RiB8H-_pEZ9BW9x9Mqt4fW-XTj0M3gEAShWDjOtE")

# Production DB 설정
SUPABASE_PROD_URL = os.getenv("SUPABASE_PRODUCTION_URL", "https://cwsdvgkjptuvbdtxcejt.supabase.co")
SUPABASE_PROD_KEY = os.getenv("SUPABASE_PRODUCTION_KEY", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImN3c2R2Z2tqcHR1dmJkdHhjZWp0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTYzOTQ0MzksImV4cCI6MjA3MTk3MDQzOX0.kSKAYjtFWoxHn0PNq6mAZ2OEngeGR7i_FW3V75Hrby8")

# 전역 Supabase 클라이언트
_supabase_client: Optional[Client] = None
_supabase_prod_client: Optional[Client] = None

def get_supabase() -> Client:
    """Control DB 클라이언트 (기기/그룹 관리)"""
    global _supabase_client
    if _supabase_client is None:
        _supabase_client = create_client(SUPABASE_URL, SUPABASE_KEY)
    return _supabase_client

def get_supabase_production() -> Client:
    """Production DB 클라이언트 (슬롯/트래픽 큐)"""
    global _supabase_prod_client
    if _supabase_prod_client is None:
        _supabase_prod_client = create_client(SUPABASE_PROD_URL, SUPABASE_PROD_KEY)
    return _supabase_prod_client

def test_connection() -> bool:
    """Control DB 연결 테스트"""
    try:
        client = get_supabase()
        client.table('devices').select('id').limit(1).execute()
        return True
    except Exception as e:
        print(f"Control DB 연결 실패: {str(e)}")
        return False

def test_production_connection() -> bool:
    """Production DB 연결 테스트"""
    try:
        client = get_supabase_production()
        client.table('slot_navertest').select('id').limit(1).execute()
        return True
    except Exception as e:
        print(f"Production DB 연결 실패: {str(e)}")
        return False
