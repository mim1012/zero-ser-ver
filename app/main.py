from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.database.database import engine, Base
from app.api.v1 import devices, keywords, accounts

# 데이터베이스 테이블 생성
Base.metadata.create_all(bind=engine)

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
    return {"status": "healthy"}


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
