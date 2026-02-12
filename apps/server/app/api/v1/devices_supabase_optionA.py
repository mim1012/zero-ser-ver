"""
기기 및 그룹 관리 API (Supabase 기반) - 옵션 A: 별도 APK 방식

클라이언트가 자신의 역할(leader/follower)을 전송하면 서버는 그대로 저장합니다.
"""
from fastapi import APIRouter, HTTPException, Body
from pydantic import BaseModel
from typing import Optional
from datetime import datetime
from app.database.supabase_client import get_supabase

router = APIRouter()

# ============================================================
# Request/Response 모델
# ============================================================

class RegisterDeviceRequest(BaseModel):
    device_id: str
    role: str  # "leader" 또는 "follower" (클라이언트가 전송)
    group_name: str = "Group 1"
    current_ip: Optional[str] = None

class RegisterDeviceResponse(BaseModel):
    device_id: str
    group_id: int
    group_name: str
    role: str
    message: str

class HeartbeatRequest(BaseModel):
    device_id: str
    current_ip: Optional[str] = None

class GroupInfoResponse(BaseModel):
    group_id: int
    group_name: str
    leader_device_id: Optional[str]
    total_devices: int
    active_devices: int

# ============================================================
# API 엔드포인트
# ============================================================

@router.post("/register", response_model=RegisterDeviceResponse)
async def register_device(request: RegisterDeviceRequest):
    """
    기기 등록 (옵션 A: 클라이언트가 보낸 역할을 그대로 저장)
    
    로직:
    1. 클라이언트가 자신의 역할(leader/follower)을 전송
    2. 서버는 해당 역할을 그대로 저장
    3. 그룹이 없으면 자동 생성
    """
    try:
        supabase = get_supabase()
        
        # 1. 그룹 확인 또는 생성
        group_result = supabase.table('device_groups') \
            .select('*') \
            .eq('name', request.group_name) \
            .execute()
        
        if not group_result.data:
            # 그룹 생성
            new_group = supabase.table('device_groups').insert({
                'name': request.group_name,
                'status': 'active'
            }).execute()
            group_id = new_group.data[0]['id']
            group_name = new_group.data[0]['name']
        else:
            group_id = group_result.data[0]['id']
            group_name = group_result.data[0]['name']
        
        # 2. 기존 기기 확인
        existing_device = supabase.table('devices') \
            .select('*') \
            .eq('id', request.device_id) \
            .execute()
        
        if existing_device.data:
            # 기존 기기 - 정보 업데이트
            supabase.table('devices') \
                .update({
                    'group_id': group_id,
                    'role': request.role,  # 클라이언트가 보낸 역할 사용
                    'last_heartbeat': datetime.now().isoformat(),
                    'current_ip': request.current_ip,
                    'status': 'active'
                }) \
                .eq('id', request.device_id) \
                .execute()
            
            message = "기존 기기 정보 업데이트됨"
        else:
            # 신규 기기 - 등록
            supabase.table('devices').insert({
                'id': request.device_id,
                'group_id': group_id,
                'role': request.role,  # 클라이언트가 보낸 역할 사용
                'status': 'active',
                'last_heartbeat': datetime.now().isoformat(),
                'current_ip': request.current_ip,
                'tasks_completed': 0
            }).execute()
            
            message = f"신규 기기 등록 완료 ({request.role})"
        
        # 3. 대장봇인 경우 그룹의 leader_device_id 업데이트
        if request.role == 'leader':
            supabase.table('device_groups') \
                .update({'leader_device_id': request.device_id}) \
                .eq('id', group_id) \
                .execute()
        
        return RegisterDeviceResponse(
            device_id=request.device_id,
            group_id=group_id,
            group_name=group_name,
            role=request.role,
            message=message
        )
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"기기 등록 실패: {str(e)}")


@router.post("/heartbeat")
async def heartbeat(request: HeartbeatRequest):
    """
    하트비트 - 기기 상태 업데이트
    """
    try:
        supabase = get_supabase()
        
        supabase.table('devices') \
            .update({
                'last_heartbeat': datetime.now().isoformat(),
                'current_ip': request.current_ip,
                'status': 'active'
            }) \
            .eq('id', request.device_id) \
            .execute()
        
        return {"status": "success", "message": "하트비트 업데이트됨"}
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"하트비트 실패: {str(e)}")


@router.get("/groups/{group_id}", response_model=GroupInfoResponse)
async def get_group_info(group_id: int):
    """
    그룹 정보 조회
    """
    try:
        supabase = get_supabase()
        
        # 그룹 정보 조회
        group_result = supabase.table('device_groups') \
            .select('*') \
            .eq('id', group_id) \
            .execute()
        
        if not group_result.data:
            raise HTTPException(status_code=404, detail="그룹을 찾을 수 없습니다")
        
        group = group_result.data[0]
        
        # 그룹의 기기 수 조회
        devices_result = supabase.table('devices') \
            .select('id, status') \
            .eq('group_id', group_id) \
            .execute()
        
        total_devices = len(devices_result.data)
        active_devices = len([d for d in devices_result.data if d['status'] == 'active'])
        
        return GroupInfoResponse(
            group_id=group['id'],
            group_name=group['name'],
            leader_device_id=group.get('leader_device_id'),
            total_devices=total_devices,
            active_devices=active_devices
        )
        
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"그룹 정보 조회 실패: {str(e)}")
