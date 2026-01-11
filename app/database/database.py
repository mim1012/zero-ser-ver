import os
from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker

# Railway 환경 변수 또는 로컬 개발 환경 변수 사용
DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "mysql+pymysql://mim1012:password@localhost:3306/zero_db"
)

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
