from sqlalchemy import Column, Integer, String, Text, DateTime, Boolean, ForeignKey, JSON
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
from app.database.database import Base


class Device(Base):
    """기기 정보 테이블"""
    __tablename__ = "devices"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String(255), unique=True, index=True, nullable=False)
    device_name = Column(String(255))
    device_model = Column(String(255))
    os_version = Column(String(100))
    app_version = Column(String(100))
    status = Column(String(50), default="active")  # active, inactive, banned
    last_heartbeat = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    # 관계
    keywords = relationship("Keyword", back_populates="device")


class Keyword(Base):
    """작업(키워드) 정보 테이블"""
    __tablename__ = "keywords"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(Integer, ForeignKey("devices.id"), nullable=False)
    keyword = Column(String(500), nullable=False)
    nv_mid = Column(String(255))  # 네이버 상품 ID
    target_url = Column(Text)
    work_type = Column(String(100), default="search")  # search, click, detail_view
    status = Column(String(50), default="pending")  # pending, in_progress, completed, failed
    priority = Column(Integer, default=0)
    max_traffic_count = Column(Integer, default=100)
    current_traffic_count = Column(Integer, default=0)
    variables = Column(JSON)  # User-Agent, 쿠키, 헤더 등 변수 저장
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    # 관계
    device = relationship("Device", back_populates="keywords")


class Account(Base):
    """계정 정보 테이블"""
    __tablename__ = "accounts"

    id = Column(Integer, primary_key=True, index=True)
    platform = Column(String(100), nullable=False)  # naver, coupang
    login_id = Column(String(255), nullable=False)
    password = Column(String(255))  # 암호화 필요
    cookies = Column(JSON)  # 쿠키 정보 저장
    status = Column(String(50), default="active")  # active, inactive, banned
    last_used = Column(DateTime(timezone=True))
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class Cookie(Base):
    """쿠키 정보 테이블"""
    __tablename__ = "cookies"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(Integer, ForeignKey("devices.id"))
    domain = Column(String(255), nullable=False)
    name = Column(String(255), nullable=False)
    value = Column(Text, nullable=False)
    path = Column(String(255), default="/")
    expires = Column(DateTime(timezone=True))
    secure = Column(Boolean, default=False)
    http_only = Column(Boolean, default=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class RankHistory(Base):
    """순위 변동 이력 테이블"""
    __tablename__ = "rank_history"

    id = Column(Integer, primary_key=True, index=True)
    keyword_id = Column(Integer, ForeignKey("keywords.id"), nullable=False)
    keyword = Column(String(500), nullable=False)
    nv_mid = Column(String(255))
    rank = Column(Integer)
    page = Column(Integer)
    checked_at = Column(DateTime(timezone=True), server_default=func.now())
    created_at = Column(DateTime(timezone=True), server_default=func.now())
