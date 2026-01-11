from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.database.database import engine, Base
from app.api.v1 import devices, keywords, accounts
import logging

# 로깅 설정
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# 데이터베이스 테이블 생성 (오류 발생 시 경고만 출력)
try:
    Base.metadata.create_all(bind=engine)
    logger.info("Database tables created successfully")
except Exception as e:
    logger.warning(f"Could not create database tables: {e}")
    logger.warning("Server will start without database connection. Please check DATABASE_URL environment variable.")

app = FastAPI(
    title="Zero Server API",
    description="Zero 모바일 자동화 서버 API",
    version="1.0.0"
)

# CORS 설정
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 프로덕션에서는 특정 도메인으로 제한
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API 라우터 등록
app.include_router(devices.router, prefix="/zero/api/v1")
app.include_router(keywords.router, prefix="/zero/api/v1")
app.include_router(accounts.router, prefix="/zero/api/v1")


@app.get("/")
def read_root():
    """루트 엔드포인트"""
    return {
        "message": "Zero Server API is running",
        "version": "1.0.0",
        "docs": "/docs"
    }


@app.get("/health")
def health_check():
    """헬스 체크 엔드포인트"""
    try:
        # 데이터베이스 연결 테스트
        from app.database.database import SessionLocal
        db = SessionLocal()
        db.execute("SELECT 1")
        db.close()
        db_status = "connected"
    except Exception as e:
        db_status = f"disconnected: {str(e)}"
    
    return {
        "status": "healthy",
        "database": db_status
    }


@app.get("/zero/api/")
def zero_api_root():
    """Zero API 루트 엔드포인트 (클라이언트 호환성)"""
    return {
        "message": "Zero API is running",
        "version": "1.0.0",
        "endpoints": {
            "devices": "/zero/api/v1/devices",
            "keywords": "/zero/api/v1/keywords",
            "accounts": "/zero/api/v1/accounts"
        }
    }
