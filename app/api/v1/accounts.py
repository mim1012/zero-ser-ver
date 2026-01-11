from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from typing import List
from app.database.database import get_db
from app.models.models import Account
from app.schemas.schemas import AccountCreate, AccountResponse, AccountUpdate, MessageResponse

router = APIRouter(prefix="/accounts", tags=["accounts"])


@router.post("/", response_model=AccountResponse, status_code=status.HTTP_201_CREATED)
def create_account(account: AccountCreate, db: Session = Depends(get_db)):
    """계정 생성"""
    # 중복 확인
    existing_account = db.query(Account).filter(
        Account.platform == account.platform,
        Account.login_id == account.login_id
    ).first()
    
    if existing_account:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Account already exists"
        )
    
    # 계정 생성
    db_account = Account(**account.model_dump())
    db.add(db_account)
    db.commit()
    db.refresh(db_account)
    return db_account


@router.get("/", response_model=List[AccountResponse])
def list_accounts(skip: int = 0, limit: int = 100, platform: str = None, db: Session = Depends(get_db)):
    """계정 목록 조회"""
    query = db.query(Account)
    if platform:
        query = query.filter(Account.platform == platform)
    
    accounts = query.offset(skip).limit(limit).all()
    return accounts


@router.get("/{account_id}", response_model=AccountResponse)
def get_account(account_id: int, db: Session = Depends(get_db)):
    """특정 계정 조회"""
    account = db.query(Account).filter(Account.id == account_id).first()
    if not account:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Account not found"
        )
    return account


@router.put("/{account_id}", response_model=AccountResponse)
def update_account(account_id: int, account_update: AccountUpdate, db: Session = Depends(get_db)):
    """계정 정보 업데이트"""
    account = db.query(Account).filter(Account.id == account_id).first()
    if not account:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Account not found"
        )
    
    # 업데이트
    for key, value in account_update.model_dump(exclude_unset=True).items():
        setattr(account, key, value)
    
    db.commit()
    db.refresh(account)
    return account


@router.delete("/{account_id}", response_model=MessageResponse)
def delete_account(account_id: int, db: Session = Depends(get_db)):
    """계정 삭제"""
    account = db.query(Account).filter(Account.id == account_id).first()
    if not account:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Account not found"
        )
    
    db.delete(account)
    db.commit()
    return MessageResponse(message="Account deleted successfully")


@router.get("/next", response_model=AccountResponse)
def get_next_account(platform: str, db: Session = Depends(get_db)):
    """
    로테이션을 위한 다음 사용 가능한 계정 가져오기
    
    - 가장 오래 사용되지 않은 계정을 반환
    - last_used가 NULL인 계정을 우선 반환
    """
    from sqlalchemy import or_
    from datetime import datetime
    
    # 활성 상태이고 해당 플랫폼의 계정 중 가장 오래 사용되지 않은 계정 찾기
    account = db.query(Account).filter(
        Account.platform == platform,
        Account.status == "active"
    ).order_by(
        Account.last_used.asc().nullsfirst()
    ).first()
    
    if not account:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No available account for platform: {platform}"
        )
    
    # last_used 업데이트
    account.last_used = datetime.utcnow()
    db.commit()
    db.refresh(account)
    
    return account


@router.post("/{account_id}/complete", response_model=MessageResponse)
def complete_account_task(account_id: int, tasks_completed: int = 100, success: bool = True, db: Session = Depends(get_db)):
    """
    계정 작업 완료 보고
    
    - 작업이 완료되면 호출
    - 실패한 경우 status를 inactive로 변경 가능
    """
    from datetime import datetime
    
    account = db.query(Account).filter(Account.id == account_id).first()
    if not account:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Account not found"
        )
    
    # 작업 완료 시간 업데이트
    account.last_used = datetime.utcnow()
    
    # 실패한 경우 상태 변경
    if not success:
        account.status = "inactive"
    
    db.commit()
    
    return MessageResponse(message=f"Account {account_id} task completed. Tasks: {tasks_completed}, Success: {success}")
