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
# unified-runner-shopping-tab-app.ts 흐름 참조
#
# 흐름:
#   랜딩페이지(referrer) → 검색결과(sm=mtp_hty.top,query=keyword)
#   → m.naver.com → 자동완성 클릭(ackey+sm=mtp_sug.top)
#   → URL query를 상품명으로 변경(ackey 유지)
#   → msearch.shopping.naver.com 쇼핑탭
#   → 3전략 MID 찾기 → 클릭 → 체류
# ============================================================

SCENARIOS = {
    "shopping_tab_v1": {
        "id": "shopping_tab_v1",
        "name": "랜딩 → 자동완성(ackey) → 쇼핑탭 → MID 클릭",
        "version": 3,
        "variables": {
            "keyword": "{{task.short_keyword}}",
            "product_name": "{{task.product_name}}",
            "mid": "{{task.nv_mid}}",
            "landing_url": "{{task.target_url}}"
        },
        "steps": [
            # ── 1. 랜딩페이지 방문 (referrer 생성) ──
            # → meta refresh 1초 후 m.search.naver.com/search.naver?query=keyword&sm=mtp_hty.top&where=m
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
            # ── 2. 검색결과에서 잠시 스크롤 (자연스러운 행동) ──
            {
                "id": "s04_scroll_results",
                "action": "scroll",
                "distance": 600,
                "stepRange": [100, 200],
                "stepDelay": [200, 400]
            },
            {
                "id": "s05_wait_browse",
                "action": "delay",
                "ms": [1000, 2000]
            },
            # ── 3. m.naver.com으로 이동 (자동완성 위해) ──
            {
                "id": "s06_go_naver",
                "action": "navigate",
                "url": "https://m.naver.com/",
                "timeout": 30000
            },
            {
                "id": "s07_wait_naver",
                "action": "delay",
                "ms": [1500, 2500]
            },
            # ── 4. 검색창 클릭 + 짧은 키워드 입력 ──
            {
                "id": "s08_scroll_top",
                "action": "evalJS",
                "script": "window.scrollTo(0,0);'ok'",
                "timeout": 3000
            },
            {
                "id": "s09_click_search",
                "action": "tap",
                "selector": "#MM_SEARCH_FAKE",
                "fallback": ".sch_area",
                "timeout": 10000
            },
            {
                "id": "s10_wait",
                "action": "delay",
                "ms": [500, 1000]
            },
            {
                "id": "s11_type_keyword",
                "action": "humanType",
                "selector": "#query",
                "fallback": "input.sch_input",
                "text": "{{keyword}}",
                "clearFirst": True,
                "charDelay": [80, 150],
                "gapDelay": [20, 70]
            },
            {
                "id": "s12_wait_autocomplete",
                "action": "delay",
                "ms": [1500, 2500]
            },
            # ── 5. 자동완성 항목 클릭 → ackey + sm=mtp_sug.top 획득 ──
            {
                "id": "s13_click_autocomplete",
                "action": "evalJS",
                "script": "(function(){var items=document.querySelectorAll('#sb-ac-recomm-wrap li.u_atcp_l[data-area=\"top\"] a.u_atcp_a');if(items.length>1){var idx=Math.floor(Math.random()*(items.length-1))+1;items[idx].click();return 'clicked_'+idx;}else if(items.length===1){items[0].click();return 'clicked_0';}else{var btn=document.querySelector('button[type=\"submit\"],.btn_search,[class*=\"search_btn\"]');if(btn){btn.click();return 'search_btn';}return 'ERROR:no_autocomplete';}})()",
                "timeout": 5000
            },
            {
                "id": "s14_wait_results",
                "action": "delay",
                "ms": [2500, 3500]
            },
            # ── 6. URL의 query를 상품명으로 변경 (ackey/sm 유지) ──
            # unified-runner: urlObj.searchParams.set('query', productName)
            {
                "id": "s15_modify_url",
                "action": "evalJS",
                "vars": {
                    "productName": "{{product_name}}"
                },
                "script": "(function(){try{var url=new URL(window.location.href);url.searchParams.set('query',__V.productName);window.location.href=url.toString();return 'ok';}catch(e){return 'ERROR:'+e.message;}})()",
                "timeout": 10000
            },
            {
                "id": "s16_wait_product_search",
                "action": "delay",
                "ms": [2500, 3500]
            },
            # ── 7. 쇼핑탭 진입 ──
            # unified-runner: msearch.shopping.naver.com/search/all?query={productName}
            {
                "id": "s17_shopping_tab",
                "action": "evalJS",
                "vars": {
                    "productName": "{{product_name}}"
                },
                "script": "(function(){window.location.href='https://msearch.shopping.naver.com/search/all?query='+encodeURIComponent(__V.productName);return 'ok';})()",
                "timeout": 30000
            },
            {
                "id": "s18_wait_shopping",
                "action": "delay",
                "ms": [2500, 3500]
            },
            {
                "id": "s19_check_status",
                "action": "checkStatus",
                "onBlocked": "abort"
            },
            # ── 8. 3전략 MID 찾기 + 클릭 ──
            # unified-runner: nv_mid= / /products/ / nstore_productId_
            {
                "id": "s20_find_mid",
                "action": "findMid",
                "mid": "{{mid}}",
                "maxScroll": 10,
                "timeout": 30000
            },
            # ── 9. 상품 페이지 체류 ──
            {
                "id": "s21_dwell",
                "action": "dwell",
                "ms": [3000, 6000],
                "scrollDist": 2000,
                "scrollCount": [1, 3]
            },
            # ── 10. 완료 ──
            {
                "id": "s22_report",
                "action": "report",
                "status": "completed"
            }
        ]
    }
}

# 활성 시나리오 + 가중치
ACTIVE_SCENARIOS = [
    {"id": "shopping_tab_v1", "version": 3, "weight": 100}
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
