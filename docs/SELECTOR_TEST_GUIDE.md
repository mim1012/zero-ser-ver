# 네이버 쇼핑 모바일 셀렉터 검증 가이드

## ⚠️ 현재 문제점

### 1. 자동완성 셀렉터가 PC 버전 기준
```javascript
// 현재 코드 (naver_shopping_v1.js:413)
const autocompleteSelector = '#sb-ac-recomm-wrap li.u_atcp_l[data-area="top"] a.u_atcp_a';
```

**문제:**
- 이 셀렉터는 PC 네이버 검색 기준
- 모바일 (`m.naver.com`) DOM 구조가 다를 수 있음
- 네이버가 클래스명 변경하면 즉시 작동 중단

---

## 🔍 실제 모바일 페이지에서 셀렉터 검증 필요

### 테스트 절차

#### 1단계: Chrome DevTools로 모바일 DOM 확인

```bash
# Android 디바이스 연결
adb devices

# Chrome Remote Debugging
chrome://inspect/#devices
```

**또는 PC Chrome에서:**
1. `m.naver.com` 접속
2. F12 → Device Toolbar (Ctrl+Shift+M)
3. 갤럭시 S20 선택
4. 검색창에 "갤럭시" 입력

#### 2단계: 자동완성 DOM 구조 확인

**Console에서 실행:**
```javascript
// 자동완성이 나타난 상태에서
document.querySelector('#sb-ac-recomm-wrap')
// → 이 요소가 있는지 확인

// 모든 자동완성 항목
document.querySelectorAll('#sb-ac-recomm-wrap li a')
// → 개수와 구조 확인

// 실제 보이는 요소
Array.from(document.querySelectorAll('a')).filter(a => {
    const rect = a.getBoundingClientRect();
    return rect.top > 50 && rect.top < 300 && a.textContent.includes('갤럭시');
})
```

#### 3단계: MID 셀렉터 검증

**검색 결과 페이지에서:**
```javascript
// 현재 페이지의 모든 MID 수집
function collectMIDs() {
    const mids = new Set();

    // 방법 1: nv_mid 파라미터
    document.querySelectorAll('a[href*="nv_mid="]').forEach(a => {
        const match = a.href.match(/nv_mid=(\d+)/);
        if (match) mids.add(match[1]);
    });

    // 방법 2: /products/ 경로
    document.querySelectorAll('a[href*="/products/"]').forEach(a => {
        const match = a.href.match(/\/products\/(\d+)/);
        if (match) mids.add(match[1]);
    });

    return Array.from(mids);
}

const foundMIDs = collectMIDs();
console.log('페이지의 MID:', foundMIDs);
console.log('총 개수:', foundMIDs.length);
```

---

## 🛠️ 개선 방안

### 방안 1: 다중 전략 적용 (권장)

기존 코드를 `robust_selectors.js` 로직으로 교체:

```javascript
// AS-IS (단일 셀렉터)
const autocompleteItems = document.querySelectorAll('#sb-ac-recomm-wrap li.u_atcp_l a.u_atcp_a');
if (!autocompleteItems.length) {
    throw new Error('자동완성 없음');
}

// TO-BE (다중 전략)
const autocompleteItems = RobustSelectors.findAutocompleteItems(shortKeyword);
if (!autocompleteItems.length) {
    // 전략 1~5를 모두 시도했으므로 진짜 없는 것
    console.warn('자동완성 없음 - 검색 버튼 클릭으로 폴백');
    const searchBtn = RobustSelectors.findSearchButton();
    searchBtn?.click();
}
```

### 방안 2: 실시간 DOM 탐색

셀렉터를 하드코딩하지 않고 위치/텍스트 기반으로 찾기:

```javascript
// 검색창 아래 200px 이내의 링크 = 자동완성
function findAutocompleteByPosition(keyword) {
    const searchBox = document.querySelector('input[type="search"]');
    if (!searchBox) return [];

    const rect = searchBox.getBoundingClientRect();
    const allLinks = document.querySelectorAll('a');

    return Array.from(allLinks).filter(a => {
        const aRect = a.getBoundingClientRect();
        const isBelow = aRect.top > rect.bottom && aRect.top < rect.bottom + 200;
        const hasKeyword = a.textContent.toLowerCase().includes(keyword.toLowerCase());
        return isBelow && hasKeyword;
    });
}
```

### 방안 3: 셀렉터 자동 업데이트

서버에서 셀렉터를 관리하고 동적으로 업데이트:

```json
// server/config/selectors.json
{
  "version": "2024-01-20",
  "mobile": {
    "autocomplete": [
      "#sb-ac-recomm-wrap li a",
      ".autocomplete-item a",
      "[data-role='autocomplete'] a"
    ],
    "search_input": [
      "input[type='search']",
      "input[name='query']",
      ".search-input"
    ]
  }
}
```

---

## 📊 테스트 케이스

### 테스트 1: 자동완성 클릭

**준비:**
1. `m.naver.com` 접속
2. 검색창 클릭
3. "갤럭시" 입력

**검증:**
```javascript
// Console에서 실행
const items = RobustSelectors.findAutocompleteItems('갤럭시');
console.log('자동완성 개수:', items.length);
console.log('텍스트:', items.map(a => a.textContent.trim()));

// 3번째 항목 클릭 시뮬레이션
if (items.length > 2) {
    items[2].click();
    console.log('✓ 클릭 성공:', items[2].textContent);
}
```

**기대 결과:**
- items.length > 0
- 클릭 후 검색 결과 페이지로 이동

---

### 테스트 2: MID 찾기

**준비:**
1. "갤럭시 S24" 검색
2. 상품 목록 확인
3. MID 하나 선택 (예: `12345678`)

**검증:**
```javascript
// Console에서 실행
const targetMID = '12345678';
const result = RobustSelectors.findProductByMID(targetMID);

if (result) {
    console.log('✓ MID 발견');
    console.log('전략:', result.strategy);
    console.log('링크:', result.element.href);
    console.log('텍스트:', result.element.textContent.trim().substring(0, 50));

    // 클릭 시뮬레이션
    result.element.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setTimeout(() => result.element.click(), 1000);
} else {
    console.error('✗ MID 찾기 실패');
    console.log('페이지의 모든 MID:', RobustSelectors.collectAllMIDs());
}
```

**기대 결과:**
- result !== null
- 클릭 후 상품 상세페이지로 이동

---

### 테스트 3: 전체 자동화 플로우

**WebView에서 스크립트 주입 후:**
```javascript
// 1. robust_selectors.js 로드
(robust_selectors.js 내용)

// 2. 테스트 실행
async function testFullFlow() {
    try {
        // 검색창 찾기
        const input = RobustSelectors.findSearchInput();
        if (!input) throw new Error('검색창 없음');

        // 검색어 입력
        input.value = '갤럭시';
        input.dispatchEvent(new Event('input', { bubbles: true }));

        // 자동완성 대기
        await new Promise(r => setTimeout(r, 1500));

        // 자동완성 클릭
        const items = RobustSelectors.findAutocompleteItems('갤럭시');
        if (items.length > 0) {
            items[Math.floor(Math.random() * items.length)].click();
        } else {
            const btn = RobustSelectors.findSearchButton();
            btn?.click();
        }

        // 검색 결과 대기
        await new Promise(r => setTimeout(r, 3000));

        // MID 찾기
        const allMIDs = RobustSelectors.collectAllMIDs();
        console.log('발견된 MID:', allMIDs.slice(0, 10));

        if (allMIDs.length > 0) {
            const testMID = allMIDs[0];
            const result = RobustSelectors.findProductByMID(testMID);
            if (result) {
                result.element.scrollIntoView({ behavior: 'smooth' });
                await new Promise(r => setTimeout(r, 1000));
                result.element.click();
                console.log('✓ 전체 플로우 성공');
            }
        }

    } catch (e) {
        console.error('✗ 에러:', e.message);
    }
}

testFullFlow();
```

---

## 🚨 주의사항

### 1. 네이버 DOM 구조는 자주 변경됨
- **주간 단위**로 셀렉터 검증 필요
- 로그에서 "MID 찾기 실패" 증가 시 즉시 확인

### 2. PC vs 모바일 차이
- **반드시 모바일 환경**에서 테스트
- `m.naver.com` 과 `www.naver.com` 은 완전히 다른 DOM

### 3. A/B 테스트
- 네이버는 사용자마다 다른 UI 제공 가능
- 여러 디바이스/계정으로 테스트 권장

---

## 📋 체크리스트

실제 배포 전 확인:

- [ ] 모바일 Chrome에서 자동완성 셀렉터 검증
- [ ] 최소 10개 MID로 찾기 테스트
- [ ] PC와 모바일 DOM 차이 확인
- [ ] 네트워크 느린 환경 테스트
- [ ] 자동완성 없는 경우 폴백 동작 확인
- [ ] MID 없는 경우 에러 처리 확인
- [ ] 로그에 발견된 MID 목록 기록되는지 확인

---

## 🔧 긴급 수정 시

셀렉터가 깨졌을 때 빠른 대응:

```javascript
// 1. 서버에서 새 셀렉터 배포 (app/automation_scripts/)
// 2. 디바이스는 다음 스크립트 다운로드 시 자동 업데이트 (최대 1시간)
// 3. 긴급 시: 모든 디바이스 재시작 (TaskExecutor 강제 스크립트 다운로드)
```

**서버 핫픽스 예시:**
```bash
# 1. 셀렉터 수정
vim app/automation_scripts/naver_shopping_v1.js

# 2. 스크립트 버전 증가
vim app/api/v1/automation.py
# SCRIPT_VERSIONS["v1"]["version"] = "1.0.1"

# 3. Railway 배포
git add .
git commit -m "hotfix: Update autocomplete selector"
git push origin master

# 4. 확인
curl https://zero-server.railway.app/zero/api/v1/automation/version
```

---

## 결론

**현재 상태:**
- ⚠️ 셀렉터가 PC 버전 기준으로 되어있을 가능성 높음
- ⚠️ 네이버 DOM 변경 시 전체 작동 중단 위험

**권장 조치:**
1. ✅ 실제 Android WebView에서 `robust_selectors.js` 테스트
2. ✅ 테스트 결과 기반으로 `naver_shopping_v1.js` 업데이트
3. ✅ 다중 전략 적용하여 안정성 확보
4. ✅ 주간 단위 모니터링 및 검증

**다음 단계:** 실제 디바이스에서 위 테스트 실행 후 결과 공유해주세요!
