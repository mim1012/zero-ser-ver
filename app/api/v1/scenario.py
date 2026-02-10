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
# 시나리오 정의
# ============================================================

SCENARIOS = {
    "shopping_tab_v1": {
        "id": "shopping_tab_v1",
        "name": "랜딩 → 자동완성 → 쇼핑탭 → MID 클릭",
        "version": 2,
        "variables": {
            "keyword": "{{task.short_keyword}}",
            "product_name": "{{task.product_name}}",
            "mid": "{{task.nv_mid}}",
            "landing_url": "{{task.target_url}}"
        },
        "steps": [
            # ── 1. 랜딩페이지 방문 (referrer 생성) ──
            {
                "id": "s01_landing",
                "action": "navigate",
                "url": "{{landing_url}}",
                "timeout": 15000
            },
            {
                "id": "s02_wait_redirect",
                "action": "delay",
                "ms": [2500, 3500]
            },
            {
                "id": "s03_check_page",
                "action": "checkStatus",
                "onBlocked": "abort"
            },
            # ── 2. 네이버 홈에서 검색 ──
            {
                "id": "s04_scroll_top",
                "action": "evalJS",
                "script": "window.scrollTo(0,0);'ok'",
                "timeout": 3000
            },
            {
                "id": "s05_click_search",
                "action": "tap",
                "selector": "#MM_SEARCH_FAKE",
                "fallback": ".sch_area",
                "timeout": 10000
            },
            {
                "id": "s06_wait",
                "action": "delay",
                "ms": [500, 1000]
            },
            # ── 3. 짧은 키워드 입력 ──
            {
                "id": "s07_type_keyword",
                "action": "humanType",
                "selector": "#query",
                "fallback": "input.sch_input",
                "text": "{{keyword}}",
                "clearFirst": True,
                "charDelay": [80, 150],
                "gapDelay": [20, 70]
            },
            {
                "id": "s08_wait_autocomplete",
                "action": "delay",
                "ms": [1500, 2500]
            },
            # ── 4. 자동완성 클릭 (ackey/sm 생성) ──
            {
                "id": "s09_click_autocomplete",
                "action": "evalJS",
                "script": "(function(){var items=document.querySelectorAll('#sb-ac-recomm-wrap li.u_atcp_l[data-area=\"top\"] a.u_atcp_a');if(items.length>1){var idx=Math.floor(Math.random()*(items.length-1))+1;items[idx].click();return 'clicked_'+idx;}else if(items.length===1){items[0].click();return 'clicked_0';}else{var btn=document.querySelector('button[type=\"submit\"],.btn_search,[class*=\"search_btn\"]');if(btn){btn.click();return 'search_btn';}return 'ERROR:no_autocomplete';}})()",
                "timeout": 5000
            },
            {
                "id": "s10_wait_results",
                "action": "delay",
                "ms": [2500, 3500]
            },
            # ── 5. URL의 query를 상품명으로 변경 (ackey/sm 유지) ──
            {
                "id": "s11_modify_url",
                "action": "evalJS",
                "vars": {
                    "productName": "{{product_name}}"
                },
                "script": "(function(){try{var url=new URL(window.location.href);url.searchParams.set('query',__V.productName);window.location.href=url.toString();return 'ok';}catch(e){return 'ERROR:'+e.message;}})()",
                "timeout": 10000
            },
            {
                "id": "s12_wait_product_search",
                "action": "delay",
                "ms": [2500, 3500]
            },
            # ── 6. 쇼핑탭 진입 ──
            {
                "id": "s13_shopping_tab",
                "action": "evalJS",
                "vars": {
                    "productName": "{{product_name}}"
                },
                "script": "(function(){window.location.href='https://msearch.shopping.naver.com/search/all?query='+encodeURIComponent(__V.productName);return 'ok';})()",
                "timeout": 30000
            },
            {
                "id": "s14_wait_shopping",
                "action": "delay",
                "ms": [2500, 3500]
            },
            {
                "id": "s15_check_status",
                "action": "checkStatus",
                "onBlocked": "abort"
            },
            # ── 7. MID 찾기 + 클릭 ──
            {
                "id": "s16_find_mid",
                "action": "findMid",
                "mid": "{{mid}}",
                "maxScroll": 10,
                "timeout": 30000
            },
            # ── 8. 체류 ──
            {
                "id": "s17_dwell",
                "action": "dwell",
                "ms": [3000, 6000],
                "scrollDist": 2000,
                "scrollCount": [1, 3]
            },
            # ── 9. 완료 ──
            {
                "id": "s18_report",
                "action": "report",
                "status": "completed"
            }
        ]
    }
}

# 활성 시나리오 + 가중치
ACTIVE_SCENARIOS = [
    {"id": "shopping_tab_v1", "version": 2, "weight": 100}
]

# ============================================================
# 스크립트 정의 (ScriptEngine용)
# ============================================================

SCRIPTS = {
    # 현재 사용되는 runScript는 없음 (evalJS로 인라인 처리)
    # 추후 확장용 placeholder
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
