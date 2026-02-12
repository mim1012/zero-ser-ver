from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any
from datetime import datetime


# Device Schemas
class DeviceBase(BaseModel):
    device_id: str
    device_name: Optional[str] = None
    device_model: Optional[str] = None
    os_version: Optional[str] = None
    app_version: Optional[str] = None


class DeviceCreate(DeviceBase):
    pass


class DeviceUpdate(BaseModel):
    device_name: Optional[str] = None
    status: Optional[str] = None
    last_heartbeat: Optional[datetime] = None


class DeviceResponse(DeviceBase):
    id: int
    status: str
    last_heartbeat: datetime
    created_at: datetime

    class Config:
        from_attributes = True


# Keyword Schemas
class KeywordBase(BaseModel):
    keyword: str
    nv_mid: Optional[str] = None
    target_url: Optional[str] = None
    work_type: Optional[str] = "search"
    max_traffic_count: Optional[int] = 100
    variables: Optional[Dict[str, Any]] = None


class KeywordCreate(KeywordBase):
    device_id: int


class KeywordUpdate(BaseModel):
    status: Optional[str] = None
    current_traffic_count: Optional[int] = None
    variables: Optional[Dict[str, Any]] = None


class KeywordResponse(KeywordBase):
    id: int
    device_id: int
    status: str
    priority: int
    current_traffic_count: int
    created_at: datetime

    class Config:
        from_attributes = True


# Account Schemas
class AccountBase(BaseModel):
    platform: str
    login_id: str


class AccountCreate(AccountBase):
    password: Optional[str] = None
    cookies: Optional[Dict[str, Any]] = None


class AccountUpdate(BaseModel):
    password: Optional[str] = None
    cookies: Optional[Dict[str, Any]] = None
    status: Optional[str] = None


class AccountResponse(AccountBase):
    id: int
    status: str
    last_used: Optional[datetime] = None
    created_at: datetime

    class Config:
        from_attributes = True


# Cookie Schemas
class CookieBase(BaseModel):
    domain: str
    name: str
    value: str
    path: Optional[str] = "/"
    secure: Optional[bool] = False
    http_only: Optional[bool] = False


class CookieCreate(CookieBase):
    device_id: int
    expires: Optional[datetime] = None


class CookieResponse(CookieBase):
    id: int
    device_id: int
    expires: Optional[datetime] = None
    created_at: datetime

    class Config:
        from_attributes = True


# Rank History Schemas
class RankHistoryCreate(BaseModel):
    keyword_id: int
    keyword: str
    nv_mid: Optional[str] = None
    rank: Optional[int] = None
    page: Optional[int] = None


class RankHistoryResponse(RankHistoryCreate):
    id: int
    checked_at: datetime
    created_at: datetime

    class Config:
        from_attributes = True


# Generic Response
class MessageResponse(BaseModel):
    message: str
    data: Optional[Any] = None
