from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from typing import List
from app.database.database import get_db
from app.models.models import Device
from app.schemas.schemas import DeviceCreate, DeviceResponse, DeviceUpdate, MessageResponse

router = APIRouter(prefix="/devices", tags=["devices"])


@router.post("/", response_model=DeviceResponse, status_code=status.HTTP_201_CREATED)
def register_device(device: DeviceCreate, db: Session = Depends(get_db)):
    """기기 등록"""
    # 기존 기기 확인
    existing_device = db.query(Device).filter(Device.device_id == device.device_id).first()
    if existing_device:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Device already registered"
        )
    
    # 새 기기 생성
    db_device = Device(**device.model_dump())
    db.add(db_device)
    db.commit()
    db.refresh(db_device)
    return db_device


@router.get("/", response_model=List[DeviceResponse])
def list_devices(skip: int = 0, limit: int = 100, db: Session = Depends(get_db)):
    """기기 목록 조회"""
    devices = db.query(Device).offset(skip).limit(limit).all()
    return devices


@router.get("/{device_id}", response_model=DeviceResponse)
def get_device(device_id: str, db: Session = Depends(get_db)):
    """특정 기기 조회"""
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    return device


@router.put("/{device_id}", response_model=DeviceResponse)
def update_device(device_id: str, device_update: DeviceUpdate, db: Session = Depends(get_db)):
    """기기 정보 업데이트"""
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    # 업데이트
    for key, value in device_update.model_dump(exclude_unset=True).items():
        setattr(device, key, value)
    
    db.commit()
    db.refresh(device)
    return device


@router.delete("/{device_id}", response_model=MessageResponse)
def delete_device(device_id: str, db: Session = Depends(get_db)):
    """기기 삭제"""
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    db.delete(device)
    db.commit()
    return MessageResponse(message="Device deleted successfully")


@router.post("/{device_id}/heartbeat", response_model=MessageResponse)
def device_heartbeat(device_id: str, db: Session = Depends(get_db)):
    """기기 하트비트 (생존 신호)"""
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    # last_heartbeat는 자동으로 업데이트됨 (onupdate=func.now())
    db.commit()
    db.refresh(device)
    return MessageResponse(message="Heartbeat received", data={"last_heartbeat": device.last_heartbeat})
