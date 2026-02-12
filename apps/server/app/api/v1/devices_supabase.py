"""
기기 및 그룹 관리 API (Supabase 기반)
- 기기 등록 (자동 그룹 할당 및 역할 결정)
- 하트비트
- 그룹 정보 조회
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
    current_ip: Optional[str] = None

class RegisterDeviceResponse(BaseModel):
    device_id: str
    group_id: int
    group_name: str
    role: str  # leader or follower
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
    기기 등록 (자동 그룹 할당 및 역할 결정)
    
    로직:
    1. 기존 기기인 경우: 정보 업데이트
    2. 신규 기기인 경우:
       - 활성 그룹 중 기기 수가 8개 미만인 그룹 찾기
       - 없으면 새 그룹 생성
       - 그룹의 첫 번째 기기 = 대장봇 (leader)
       - 나머지 기기 = 쫄병봇 (follower)
    """
    try:
        supabase = get_supabase()
        
        # 1. 기존 기기 확인
        existing_device = supabase.table('devices') \
            .select('*') \
            .eq('id', request.device_id) \
            .execute()
        
        if existing_device.data:
            # 기존 기기 - 정보 업데이트
            device = existing_device.data[0]
            supabase.table('devices') \
                .update({
                    'last_heartbeat': datetime.now().isoformat(),
                    'current_ip': request.current_ip,
                    'status': 'active'
                }) \
                .eq('id', request.device_id) \
                .execute()
            
            # 그룹 정보 조회
            group = supabase.table('device_groups') \
                .select('*') \
                .eq('id', device['group_id']) \
                .execute()
            
            return RegisterDeviceResponse(
                device_id=request.device_id,
                group_id=device['group_id'],
                group_name=group.data[0]['name'] if group.data else 'Unknown',
                role=device['role'],
                message="기존 기기 정보 업데이트됨"
            )
        
        # 2. 신규 기기 - 그룹 할당
        # 활성 그룹 중 기기 수가 8개 미만인 그룹 찾기
        groups = supabase.table('device_groups') \
            .select('*, devices(count)') \
            .eq('status', 'active') \
            .execute()
        
        target_group = None
        for group in groups.data:
            device_count = len(supabase.table('devices').select('id').eq('group_id', group['id']).execute().data)
            if device_count < 8:
                target_group = group
                break
        
        # 그룹이 없으면 새로 생성
        if not target_group:
            new_group = supabase.table('device_groups').insert({
                'name': f'Group {len(groups.data) + 1}',
                'status': 'active'
            }).execute()
            target_group = new_group.data[0]
        
        # 3. 역할 결정 (첫 번째 기기 = leader, 나머지 = follower)
        group_devices = supabase.table('devices') \
            .select('id') \
            .eq('group_id', target_group['id']) \
            .execute()
        
        role = 'leader' if len(group_devices.data) == 0 else 'follower'
        
        # 4. 기기 등록
        supabase.table('devices').insert({
            'id': request.device_id,
            'group_id': target_group['id'],
            'role': role,
            'status': 'active',
            'last_heartbeat': datetime.now().isoformat(),
            'current_ip': request.current_ip
        }).execute()
        
        # 5. 대장봇인 경우 그룹의 leader_device_id 업데이트
        if role == 'leader':
            supabase.table('device_groups') \
                .update({'leader_device_id': request.device_id}) \
                .eq('id', target_group['id']) \
                .execute()
        
        return RegisterDeviceResponse(
            device_id=request.device_id,
            group_id=target_group['id'],
            group_name=target_group['name'],
            role=role,
            message=f"신규 기기 등록 완료 ({role})"
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
