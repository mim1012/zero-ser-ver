from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from app.api.v1 import traffic, headers, devices_supabase, dashboard, config, automation, admin, scenario, analytics, captcha
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Zero Server API",
    description="Zero 모바일 자동화 서버 API",
    version="1.1.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API 라우터 등록 (Supabase 기반만)
app.include_router(traffic.router, prefix="/zero/api/v1/traffic", tags=["traffic"])
app.include_router(headers.router, prefix="/zero/api/v1/headers", tags=["headers"])
app.include_router(devices_supabase.router, prefix="/zero/api/v1/devices", tags=["devices"])
app.include_router(dashboard.router, prefix="/zero/api/v1/dashboard", tags=["Dashboard"])
app.include_router(config.router, prefix="/zero/api/v1", tags=["config"])
app.include_router(automation.router, prefix="/zero/api/v1", tags=["automation"])
app.include_router(admin.router, prefix="/zero/api/v1/admin", tags=["admin"])
app.include_router(scenario.router, prefix="/zero/api/v1", tags=["scenario"])
app.include_router(analytics.router, prefix="/zero/api/v1/analytics", tags=["analytics"])
app.include_router(captcha.router, prefix="/zero/api/v1/captcha", tags=["captcha"])


@app.get("/dashboard", response_class=HTMLResponse)
async def dashboard_page():
    """웹 대시보드 페이지"""
    with open("app/templates/dashboard.html", "r", encoding="utf-8") as f:
        html_content = f.read()
    return HTMLResponse(content=html_content)


@app.on_event("startup")
async def startup_event():
    """서버 시작 시 학습 루프 실행"""
    import asyncio
    import os
    if os.getenv("ANALYTICS_LOOP_ENABLED", "false").lower() == "true":
        from app.analytics.learning_loop import auto_learning_loop
        asyncio.create_task(auto_learning_loop())
        logger.info("Analytics learning loop started")


@app.get("/")
def read_root():
    return {
        "message": "Zero Server API is running",
        "version": "1.1.0",
        "docs": "/docs"
    }


@app.get("/health")
def health_check():
    """헬스 체크"""
    try:
        from app.database.supabase_client import test_connection
        supabase_status = "connected" if test_connection() else "disconnected"
    except Exception as e:
        supabase_status = f"disconnected: {str(e)}"

    try:
        from app.database.supabase_client import test_production_connection
        supabase_prod_status = "connected" if test_production_connection() else "disconnected"
    except Exception as e:
        supabase_prod_status = f"disconnected: {str(e)}"

    return {
        "status": "healthy",
        "supabase_control": supabase_status,
        "supabase_production": supabase_prod_status
    }
