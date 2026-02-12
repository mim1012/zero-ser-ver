"""
시나리오 / 스크립트 배포 API

Android APK의 ScenarioManager + ScriptEngine이 호출하는 엔드포인트:
- GET /scenario/active  → 활성 시나리오 목록 (가중치 포함)
- GET /scenario/{id}    → 시나리오 JSON 전체
- GET /script/version   → 스크립트 메타데이터 (hash)
- GET /script/{name}    → JS 스크립트 내용 (plain text)
"""

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import PlainTextResponse
import hashlib
import json

router = APIRouter()

# ============================================================
# 시나리오 정의 (v4 — 간소화)
#
# 흐름:
#   랜딩페이지(referrer=adpangshopping.co.kr)
#   → meta refresh → msearch.shopping.naver.com/search/all?query=...
#   → findMid(3전략) → 클릭 → 상세페이지 체류
# ============================================================

SCENARIOS = {
    "shopping_tab_v1": {
        "id": "shopping_tab_v1",
        "name": "랜딩 → 쇼핑 검색결과 → MID 클릭 → 체류",
        "version": 5,
        "variables": {
            "mid": "{{task.nv_mid}}",
            "landing_url": "{{task.target_url}}"
        },
        "steps": [
            # ── 1. 랜딩페이지 방문 (referrer 생성) ──
            # meta refresh 1초 후 → msearch.shopping.naver.com/search/all?query=...
            {
                "id": "s01_landing",
                "action": "navigate",
                "url": "{{landing_url}}",
                "timeout": 15000
            },
            # ── 2. 리다이렉트 대기 (meta refresh 1초 + 페이지 로드) ──
            {
                "id": "s02_wait_redirect",
                "action": "delay",
                "ms": [2500, 3500]
            },
            # ── 3. 차단 여부 확인 ──
            {
                "id": "s03_check_status",
                "action": "checkStatus",
                "onBlocked": "abort"
            },
            # ── 4. 쇼핑 검색결과에서 MID 상품 찾기 + 클릭 ──
            # maxScroll=10 (페이지당 스크롤 횟수), maxPages=5 (다음 페이지 버튼 탐색)
            {
                "id": "s04_find_mid",
                "action": "findMid",
                "mid": "{{mid}}",
                "maxScroll": 10,
                "maxPages": 5,
                "timeout": 60000
            },
            # ── 5. 상품 상세페이지 체류 ──
            {
                "id": "s05_dwell",
                "action": "dwell",
                "ms": [3000, 6000],
                "scrollDist": 2000,
                "scrollCount": [1, 3]
            },
            # ── 6. 완료 보고 ──
            {
                "id": "s06_report",
                "action": "report",
                "status": "completed"
            }
        ]
    }
}

# 활성 시나리오 + 가중치
ACTIVE_SCENARIOS = [
    {"id": "shopping_tab_v1", "version": 5, "weight": 100}
]

# ============================================================
# 스크립트 정의 (ScriptEngine용)
# ============================================================

SCRIPTS = {
    # evalJS로 인라인 처리하므로 별도 스크립트 없음
}


# ============================================================
# 시나리오 엔드포인트
# ============================================================

@router.get("/scenario/active")
async def get_active_scenarios(device_id: str = Query(default="")):
    """활성 시나리오 목록 (가중치 포함)"""
    return {"scenarios": ACTIVE_SCENARIOS}


@router.get("/scenario/{scenario_id}")
async def get_scenario(scenario_id: str):
    """시나리오 JSON 전체"""
    if scenario_id not in SCENARIOS:
        raise HTTPException(status_code=404, detail=f"Scenario not found: {scenario_id}")
    return SCENARIOS[scenario_id]


# ============================================================
# 스크립트 엔드포인트
# ============================================================

@router.get("/script/version")
async def get_script_versions():
    """스크립트 메타데이터 (hash)"""
    scripts_meta = {}
    for name, content in SCRIPTS.items():
        h = hashlib.md5(content.encode('utf-8')).hexdigest()
        scripts_meta[name] = {"hash": h}
    return {"scripts": scripts_meta}


@router.get("/script/{name}")
async def get_script(name: str):
    """JS 스크립트 내용 (plain text)"""
    if name not in SCRIPTS:
        raise HTTPException(status_code=404, detail=f"Script not found: {name}")
    return PlainTextResponse(content=SCRIPTS[name])
