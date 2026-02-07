"""
JS 스크립트 관리 API (v2)
- APK가 주기적으로 버전 체크 → 변경 시 다운로드
- 관리자가 스크립트 CRUD
"""
from fastapi import APIRouter, HTTPException
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel
from datetime import datetime
import hashlib
from app.database.supabase_client import get_supabase

router = APIRouter()


class ScriptCreate(BaseModel):
    name: str
    content: str

class ScriptUpdate(BaseModel):
    content: str


@router.get("/script/version")
async def check_script_versions():
    """
    전체 스크립트 버전/해시 목록
    APK가 1시간마다 호출 → 로컬 캐시와 비교 → 변경분만 다운로드
    """
    try:
        supabase = get_supabase()
        result = supabase.table('scripts') \
            .select('name, version, hash, updated_at') \
            .execute()

        scripts = {}
        for s in result.data:
            scripts[s['name']] = {
                'version': s['version'],
                'hash': s['hash'],
                'updated_at': s['updated_at'],
            }
        return {"scripts": scripts}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/script/{name}")
async def get_script(name: str):
    """
    JS 스크립트 다운로드
    → text/javascript 반환 (APK에서 바로 evaluateJavascript에 사용)
    """
    try:
        supabase = get_supabase()
        result = supabase.table('scripts') \
            .select('content, version, hash') \
            .eq('name', name) \
            .execute()

        if not result.data:
            raise HTTPException(status_code=404, detail=f"Script '{name}' not found")

        s = result.data[0]
        return PlainTextResponse(
            content=s['content'],
            media_type="text/javascript",
            headers={
                'X-Script-Version': str(s['version']),
                'X-Script-Hash': s['hash'] or '',
            }
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/script")
async def create_script(body: ScriptCreate):
    """스크립트 생성 (hash 자동계산)"""
    try:
        supabase = get_supabase()
        h = hashlib.sha256(body.content.encode()).hexdigest()[:16]

        supabase.table('scripts').insert({
            'name': body.name,
            'version': 1,
            'content': body.content,
            'hash': h,
            'updated_at': datetime.now().isoformat(),
        }).execute()

        return {"status": "success", "name": body.name, "version": 1, "hash": h}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.put("/script/{name}")
async def update_script(name: str, body: ScriptUpdate):
    """스크립트 수정 (version+1, hash 재계산)"""
    try:
        supabase = get_supabase()

        cur = supabase.table('scripts').select('version').eq('name', name).execute()
        if not cur.data:
            raise HTTPException(status_code=404, detail=f"Script '{name}' not found")

        new_ver = cur.data[0]['version'] + 1
        h = hashlib.sha256(body.content.encode()).hexdigest()[:16]

        supabase.table('scripts').update({
            'version': new_ver,
            'content': body.content,
            'hash': h,
            'updated_at': datetime.now().isoformat(),
        }).eq('name', name).execute()

        return {"status": "success", "name": name, "version": new_ver, "hash": h}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/script/{name}")
async def delete_script(name: str):
    """스크립트 삭제"""
    try:
        supabase = get_supabase()
        supabase.table('scripts').delete().eq('name', name).execute()
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/script/list")
async def list_scripts():
    """전체 스크립트 목록 (관리자)"""
    try:
        supabase = get_supabase()
        result = supabase.table('scripts') \
            .select('name, version, hash, updated_at') \
            .order('name') \
            .execute()
        return {"scripts": result.data}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
