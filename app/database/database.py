import os
from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
import logging

logger = logging.getLogger(__name__)

# Railway 환경 변수 처리
# Railway는 MYSQL로 시작하는 환경 변수를 제공합니다
MYSQL_HOST = os.getenv("MYSQLHOST", "localhost")
MYSQL_PORT = os.getenv("MYSQLPORT", "3306")
MYSQL_USER = os.getenv("MYSQLUSER", "mim1012")
MYSQL_PASSWORD = os.getenv("MYSQLPASSWORD", "password")
MYSQL_DATABASE = os.getenv("MYSQLDATABASE", "zero_db")

# DATABASE_URL 우선 사용, 없으면 개별 환경 변수로 구성
DATABASE_URL = os.getenv("DATABASE_URL")

if not DATABASE_URL:
    # Railway의 개별 환경 변수로 DATABASE_URL 구성
    DATABASE_URL = f"mysql+pymysql://{MYSQL_USER}:{MYSQL_PASSWORD}@{MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DATABASE}"
    logger.info(f"Constructed DATABASE_URL from individual env vars: mysql+pymysql://{MYSQL_USER}:***@{MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DATABASE}")
else:
    logger.info(f"Using DATABASE_URL from environment: {DATABASE_URL[:20]}...")

# Railway의 DATABASE_URL이 postgres://로 시작하는 경우 postgresql://로 변경
if DATABASE_URL.startswith("postgres://"):
    DATABASE_URL = DATABASE_URL.replace("postgres://", "postgresql://", 1)

engine = create_engine(
    DATABASE_URL,
    pool_pre_ping=True,
    pool_recycle=3600,
    echo=False
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db():
    """데이터베이스 세션 의존성"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
