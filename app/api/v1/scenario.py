"""
시나리오 관리 API (v2)
- 기기별 활성 시나리오 조회 (가중치 기반 경로 분배)
- 시나리오 CRUD
- 기기-시나리오 매핑 (단일/일괄)
"""
from fastapi import APIRouter, HTTPException, Body
from pydantic import BaseModel
from typing import Optional, Dict, Any, List
from datetime import datetime
from app.database.supabase_client import get_supabase

router = APIRouter()


# ── Models ──────────────────────────────────────────────

class ScenarioCreate(BaseModel):
    id: str
    name: str
    scenario_json: Dict[str, Any]
    enabled: bool = True

class ScenarioUpdate(BaseModel):
    name: Optional[str] = None
    scenario_json: Optional[Dict[str, Any]] = None
    enabled: Optional[bool] = None

class DeviceScenarioMapping(BaseModel):
    device_id: str
    scenario_id: str
    weight: int = 1

class BulkAssignRequest(BaseModel):
    device_ids: List[str]
    scenario_id: str
    weight: int = 1


# ── 시나리오 조회 ───────────────────────────────────────

@router.get("/scenario/active")
async def get_active_scenarios(device_id: str):
    """
    기기에 할당된 활성 시나리오 목록

    - device_scenarios 매핑이 있으면 → 매핑된 것만
    - 없으면 → enabled=true 전체 (weight=1)
    - APK는 version으로 캐시 비교
    """
    try:
        supabase = get_supabase()

        mapping = supabase.table('device_scenarios') \
            .select('scenario_id, weight') \
            .eq('device_id', device_id) \
            .execute()

        if mapping.data:
            ids = [m['scenario_id'] for m in mapping.data]
            weights = {m['scenario_id']: m['weight'] for m in mapping.data}

            scenarios = supabase.table('scenarios') \
                .select('id, name, version') \
                .in_('id', ids) \
                .eq('enabled', True) \
                .execute()

            return {"scenarios": [
                {**s, "weight": weights.get(s['id'], 1)}
                for s in scenarios.data
            ]}

        # 매핑 없으면 전체
        scenarios = supabase.table('scenarios') \
            .select('id, name, version') \
            .eq('enabled', True) \
            .execute()

        return {"scenarios": [
            {**s, "weight": 1} for s in scenarios.data
        ]}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/scenario/{scenario_id}")
async def get_scenario(scenario_id: str):
    """시나리오 상세 (JSON DSL 전체)"""
    try:
        supabase = get_supabase()
        result = supabase.table('scenarios') \
            .select('scenario_json, version') \
            .eq('id', scenario_id) \
            .execute()

        if not result.data:
            raise HTTPException(status_code=404, detail=f"Scenario '{scenario_id}' not found")

        return result.data[0]['scenario_json']

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ── 시나리오 CRUD ───────────────────────────────────────

@router.post("/scenario")
async def create_scenario(body: ScenarioCreate):
    """시나리오 생성"""
    try:
        supabase = get_supabase()
        now = datetime.now().isoformat()

        supabase.table('scenarios').insert({
            'id': body.id,
            'name': body.name,
            'version': body.scenario_json.get('version', 1),
            'scenario_json': body.scenario_json,
            'enabled': body.enabled,
            'created_at': now,
            'updated_at': now,
        }).execute()

        return {"status": "success", "id": body.id}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.put("/scenario/{scenario_id}")
async def update_scenario(scenario_id: str, body: ScenarioUpdate):
    """시나리오 수정 (version 자동 증가)"""
    try:
        supabase = get_supabase()
        patch: Dict[str, Any] = {'updated_at': datetime.now().isoformat()}

        if body.name is not None:
            patch['name'] = body.name
        if body.enabled is not None:
            patch['enabled'] = body.enabled
        if body.scenario_json is not None:
            # 기존 version 조회 후 +1
            cur = supabase.table('scenarios') \
                .select('version') \
                .eq('id', scenario_id) \
                .execute()
            if not cur.data:
                raise HTTPException(status_code=404, detail="Not found")
            new_ver = cur.data[0]['version'] + 1
            body.scenario_json['version'] = new_ver
            patch['scenario_json'] = body.scenario_json
            patch['version'] = new_ver

        supabase.table('scenarios') \
            .update(patch) \
            .eq('id', scenario_id) \
            .execute()

        return {"status": "success", "id": scenario_id, "version": patch.get('version')}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/scenario/{scenario_id}")
async def delete_scenario(scenario_id: str):
    """시나리오 삭제 (매핑도 함께)"""
    try:
        supabase = get_supabase()
        supabase.table('device_scenarios').delete().eq('scenario_id', scenario_id).execute()
        supabase.table('scenarios').delete().eq('id', scenario_id).execute()
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ── 기기-시나리오 매핑 ──────────────────────────────────

@router.post("/scenario/assign")
async def assign_scenario(body: DeviceScenarioMapping):
    """기기에 시나리오 할당 (upsert)"""
    try:
        supabase = get_supabase()
        supabase.table('device_scenarios').upsert({
            'device_id': body.device_id,
            'scenario_id': body.scenario_id,
            'weight': body.weight,
        }).execute()
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/scenario/assign-bulk")
async def assign_scenario_bulk(body: BulkAssignRequest):
    """여러 기기에 시나리오 일괄 할당"""
    try:
        supabase = get_supabase()
        rows = [
            {'device_id': did, 'scenario_id': body.scenario_id, 'weight': body.weight}
            for did in body.device_ids
        ]
        supabase.table('device_scenarios').upsert(rows).execute()
        return {"status": "success", "assigned": len(body.device_ids)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/scenario/list")
async def list_scenarios(enabled_only: bool = False):
    """전체 시나리오 목록 (관리자)"""
    try:
        supabase = get_supabase()
        q = supabase.table('scenarios').select('id, name, version, enabled, updated_at')
        if enabled_only:
            q = q.eq('enabled', True)
        result = q.order('updated_at', desc=True).execute()
        return {"scenarios": result.data}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
