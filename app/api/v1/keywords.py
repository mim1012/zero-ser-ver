from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from typing import List, Optional
from app.database.database import get_db
from app.models.models import Keyword, Device
from app.schemas.schemas import KeywordCreate, KeywordResponse, KeywordUpdate, MessageResponse

router = APIRouter(prefix="/keywords", tags=["keywords"])


@router.post("/", response_model=KeywordResponse, status_code=status.HTTP_201_CREATED)
def create_keyword(keyword: KeywordCreate, db: Session = Depends(get_db)):
    """작업(키워드) 생성"""
    # 기기 존재 확인
    device = db.query(Device).filter(Device.id == keyword.device_id).first()
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    # 작업 생성
    db_keyword = Keyword(**keyword.model_dump())
    db.add(db_keyword)
    db.commit()
    db.refresh(db_keyword)
    return db_keyword


@router.get("/", response_model=List[KeywordResponse])
def list_keywords(
    skip: int = 0,
    limit: int = 100,
    device_id: Optional[int] = None,
    status: Optional[str] = None,
    db: Session = Depends(get_db)
):
    """작업 목록 조회"""
    query = db.query(Keyword)
    
    if device_id:
        query = query.filter(Keyword.device_id == device_id)
    if status:
        query = query.filter(Keyword.status == status)
    
    keywords = query.offset(skip).limit(limit).all()
    return keywords


@router.get("/{keyword_id}", response_model=KeywordResponse)
def get_keyword(keyword_id: int, db: Session = Depends(get_db)):
    """특정 작업 조회"""
    keyword = db.query(Keyword).filter(Keyword.id == keyword_id).first()
    if not keyword:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Keyword not found"
        )
    return keyword


@router.put("/{keyword_id}", response_model=KeywordResponse)
def update_keyword(keyword_id: int, keyword_update: KeywordUpdate, db: Session = Depends(get_db)):
    """작업 정보 업데이트"""
    keyword = db.query(Keyword).filter(Keyword.id == keyword_id).first()
    if not keyword:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Keyword not found"
        )
    
    # 업데이트
    for key, value in keyword_update.model_dump(exclude_unset=True).items():
        setattr(keyword, key, value)
    
    db.commit()
    db.refresh(keyword)
    return keyword


@router.delete("/{keyword_id}", response_model=MessageResponse)
def delete_keyword(keyword_id: int, db: Session = Depends(get_db)):
    """작업 삭제"""
    keyword = db.query(Keyword).filter(Keyword.id == keyword_id).first()
    if not keyword:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Keyword not found"
        )
    
    db.delete(keyword)
    db.commit()
    return MessageResponse(message="Keyword deleted successfully")


@router.get("/device/{device_id}/pending", response_model=List[KeywordResponse])
def get_pending_keywords_for_device(device_id: int, limit: int = 10, db: Session = Depends(get_db)):
    """특정 기기에 할당된 대기 중인 작업 조회"""
    keywords = db.query(Keyword).filter(
        Keyword.device_id == device_id,
        Keyword.status == "pending"
    ).order_by(Keyword.priority.desc(), Keyword.created_at.asc()).limit(limit).all()
    
    return keywords


@router.post("/{keyword_id}/complete", response_model=MessageResponse)
def complete_keyword(keyword_id: int, db: Session = Depends(get_db)):
    """작업 완료 처리"""
    keyword = db.query(Keyword).filter(Keyword.id == keyword_id).first()
    if not keyword:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Keyword not found"
        )
    
    keyword.status = "completed"
    db.commit()
    return MessageResponse(message="Keyword marked as completed")
