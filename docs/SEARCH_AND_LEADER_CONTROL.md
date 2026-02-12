# 검색 방식 및 대장봇/쫄병봇 제어 상세 가이드

---

## 📍 Part 1: 검색 방식 및 상세페이지 진입

### 1.1 전체 흐름도

```
m.naver.com 접속
    ↓
검색창 클릭 (#MM_SEARCH_FAKE)
    ↓
짧은 키워드 입력 (예: "갤럭시")
    ↓
자동완성 목록 대기
    ↓
자동완성 항목 랜덤 클릭 (2~N번째 중 선택) ← **핵심: ackey/sm 파라미터 획득**
    ↓
URL에서 ackey 추출
    ↓
상품명으로 재검색 (ackey 포함)
    ↓
스크롤하며 MID 탐색 (3가지 전략)
    ↓
상품 클릭
    ↓
상세페이지 체류 (3~6초)
    ↓
완료 보고
```

---

### 1.2 Step-by-Step 상세 설명

#### **Step 1: 초기 페이지 로드**

```javascript
// TrafficAutomationService.java에서
webView.loadUrl("https://m.naver.com/");

// 1.5~2.5초 대기 (사람처럼)
await this.sleep(this.randomBetween(1500, 2500));
```

**화면 상태**:
```
┌─────────────────────────────┐
│  NAVER                      │
│                             │
│  ┌─────────────────────┐   │
│  │ 🔍 검색어를 입력... │   │ ← #MM_SEARCH_FAKE
│  └─────────────────────┘   │
│                             │
│  [뉴스] [쇼핑] [날씨] ...  │
└─────────────────────────────┘
```

---

#### **Step 2: 검색창 클릭**

```javascript
window.scrollTo(0, 0); // 페이지 맨 위로

const searchFakeBox = document.querySelector('#MM_SEARCH_FAKE');
searchFakeBox.click();

// 0.8~1.2초 대기
await this.sleep(this.randomBetween(800, 1200));
```

**화면 변화**:
```
┌─────────────────────────────┐
│  ← NAVER                    │
│                             │
│  ┌─────────────────────┐   │
│  │ 🔍 갤럭시          │   │ ← #query.sch_input (실제 입력창)
│  └─────────────────────┘   │
│                             │
│  [최근 검색어]             │
└─────────────────────────────┘
```

---

#### **Step 3: 짧은 키워드 입력 (humanType)**

**목적**: 자동완성을 트리거하기 위한 짧은 키워드 입력

```javascript
// shortKeyword가 없으면 상품명 첫 단어 사용
const searchKeyword = shortKeyword || productName.split(' ')[0].substring(0, 10);
// 예: "갤럭시 Z 폴드 6" → "갤럭시"

const inputField = document.querySelector('#query.sch_input');
await this.humanType(inputField, searchKeyword);

// humanType 함수: 한 글자씩 30~60ms 딜레이
for (const char of text) {
    element.value += char;
    element.dispatchEvent(new Event('input', { bubbles: true }));
    await this.sleep(this.randomBetween(30, 60)); // 사람처럼 타이핑
}

// 1.5~2.5초 대기 (자동완성 로드 대기)
await this.sleep(this.randomBetween(1500, 2500));
```

**화면 변화** (타이핑 중):
```
┌─────────────────────────────┐
│  ← NAVER                    │
│                             │
│  ┌─────────────────────┐   │
│  │ 🔍 갤럭             │   │ ← 한 글자씩
│  └─────────────────────┘   │
│                             │
│  ┌───────────────────────┐ │
│  │ 갤럭시 S24           │ │ ← 자동완성 목록
│  │ 갤럭시 탭            │ │   (li.u_atcp_l[data-area="top"])
│  │ 갤럭시 워치          │ │
│  └───────────────────────┘ │
└─────────────────────────────┘
```

---

#### **Step 4: 자동완성 항목 랜덤 클릭** ← **핵심!**

**왜 자동완성을 클릭하는가?**
- 네이버 검색 URL에 `ackey`와 `sm` 파라미터가 추가됨
- 이 파라미터들이 있어야 "진짜 사용자"로 인식됨
- 직접 검색하면 이 파라미터가 없어서 봇으로 의심받을 수 있음

```javascript
const autocompleteSelector = '#sb-ac-recomm-wrap li.u_atcp_l[data-area="top"] a.u_atcp_a';
const autocompleteItems = document.querySelectorAll(autocompleteSelector);

if (autocompleteItems.length > 1) {
    // 첫 번째 항목(0)을 제외하고 랜덤 선택
    const randomIndex = this.randomBetween(1, autocompleteItems.length - 1);
    const selectedItem = autocompleteItems[randomIndex];

    this.log(`자동완성 ${autocompleteItems.length}개 중 ${randomIndex+1}번째 선택: "${keywordText}"`);
    selectedItem.click();
}
```

**예시**:
- 자동완성 목록: ["갤럭시 S24", "갤럭시 탭", "갤럭시 워치"]
- 랜덤 선택: 2번째 "갤럭시 탭" 클릭

**URL 변화**:
```
Before: https://m.search.naver.com/search.naver?query=갤럭시

After:  https://m.search.naver.com/search.naver?query=갤럭시+탭
        &where=m
        &sm=mob_hty.idx
        &ackey=AAAAAGaY1Z4BH5mVx...  ← 핵심 파라미터!
```

---

#### **Step 5: ackey 추출 및 상품명으로 재검색**

```javascript
// 현재 URL에서 ackey, sm 파라미터 추출
const url = new URL(window.location.href);
const ackey = url.searchParams.get('ackey'); // "AAAAAGaY1Z4BH5mVx..."
const sm = url.searchParams.get('sm');       // "mob_hty.idx"

this.log(`ackey=${ackey}, sm=${sm}`);
this.reportProgress('ackey_extracted', { ackey: ackey, sm: sm });

// query를 실제 상품명으로 변경
url.searchParams.set('query', productName); // "갤럭시 Z 폴드 6 자급제"

// 재검색 (ackey와 sm은 그대로 유지)
window.location.href = url.toString();
```

**최종 검색 URL**:
```
https://m.search.naver.com/search.naver?query=갤럭시+Z+폴드+6+자급제
&where=m
&sm=mob_hty.idx
&ackey=AAAAAGaY1Z4BH5mVx...  ← ackey 유지!
```

**이점**:
- 네이버가 "사용자가 자동완성을 통해 검색했다"고 인식
- 봇 탐지 우회 가능성 증가

---

#### **Step 6: CAPTCHA 및 IP 차단 감지**

```javascript
const bodyText = document.body.innerText || '';

// CAPTCHA 감지
const captchaDetected = bodyText.includes('보안 확인') ||
                       bodyText.includes('자동입력방지');

// IP 차단 감지
const ipBlocked = bodyText.includes('비정상적인 접근') ||
                 bodyText.includes('자동화된 접근') ||
                 bodyText.includes('접근이 제한');

if (captchaDetected) {
    throw new Error('CAPTCHA_DETECTED');
}

if (ipBlocked) {
    throw new Error('IP_BLOCKED');
}
```

---

#### **Step 7: MID 탐색 (3가지 전략)**

**네이버 쇼핑 상품은 3가지 형태로 표시됨**:

##### **전략 1: 가격비교 (URL 파라미터)**
```html
<a href="https://cr3.shopping.naver.com/...?nv_mid=12345678">
    <img src="product.jpg">
    <span>갤럭시 Z 폴드 6</span>
</a>
```

```javascript
// CSS 셀렉터
const link = document.querySelector(`a[href*="nv_mid=${mid}"]`);
if (link && this.isElementVisible(link)) {
    link.click();
    return true;
}
```

##### **전략 2: 플러스스토어 (URL 경로)**
```html
<a href="https://smartstore.naver.com/main/products/12345678">
    <img src="product.jpg">
    <span>갤럭시 Z 폴드 6</span>
</a>
```

```javascript
const link = document.querySelector(`a[href*="/products/${mid}"]`);
if (link && this.isElementVisible(link)) {
    link.click();
    return true;
}
```

##### **전략 3: 플러스스토어 (ID 속성, 폴백)**
```html
<div>
    <a href="https://smartstore.naver.com/...">
        <img src="product.jpg">
    </a>
    <span id="nstore_productId_12345678">상품 정보</span>
</div>
```

```javascript
const container = document.querySelector(`[id="nstore_productId_${mid}"]`);
if (container && this.isElementVisible(container)) {
    const prevLink = container.previousElementSibling;
    if (prevLink && prevLink.tagName === 'A') {
        prevLink.click();
        return true;
    }
}
```

**스크롤 및 반복**:
```javascript
const MAX_SCROLL = 15; // 최대 15번 스크롤

for (let i = 0; i < MAX_SCROLL; i++) {
    // 3가지 전략으로 MID 탐색
    // ...

    // 못 찾으면 스크롤
    const currentY = window.scrollY;
    await this.bezierScroll(currentY + 500); // Bezier 곡선으로 부드럽게
    await this.sleep(this.randomBetween(300, 500));

    // 스크롤 끝 감지
    const reachedBottom = (window.scrollY + window.innerHeight) >=
                         (document.body.scrollHeight - 100);
    if (reachedBottom) break;
}
```

---

#### **Step 8: 상세페이지 진입 및 체류**

```javascript
// MID 클릭 성공
link.click();

// 페이지 로딩 대기
await this.sleep(2000);

// 현재 URL 확인
const currentPageUrl = window.location.href;
const isProductPage = currentPageUrl.includes('smartstore.naver.com') ||
                     currentPageUrl.includes('brand.naver.com');

// 체류 시간 (3~6초)
const dwellTime = this.randomBetween(3000, 6000);
this.log(`상세페이지 체류 ${(dwellTime / 1000).toFixed(1)}초...`);

this.reportProgress('dwelling', {
    dwell_time_ms: dwellTime,
    product_page_entered: isProductPage
});

await this.sleep(dwellTime);

// 완료 보고
this.reportComplete({
    success: true,
    product_page_entered: isProductPage,
    dwell_time_ms: dwellTime,
    final_url: currentPageUrl
});
```

---

### 1.3 Bezier 스크롤 상세

**일반 스크롤 vs Bezier 스크롤**:

```javascript
// ❌ 봇처럼 보이는 스크롤
window.scrollTo(0, 500); // 순간 이동

// ✅ 사람처럼 보이는 스크롤 (Bezier 곡선)
await this.bezierScroll(500);
```

**Bezier 스크롤 원리**:
```javascript
async bezierScroll(targetY) {
    const startY = window.scrollY;
    const duration = this.randomBetween(800, 1500); // 랜덤 속도
    const startTime = Date.now();

    // 3차 베지어 곡선 제어점 (랜덤)
    const curvature = Math.min(distance * 0.3, 100);
    const cp1y = startY + (targetY - startY) * 0.1 + (Math.random() - 0.5) * curvature;
    const cp2y = startY + (targetY - startY) * 0.9 + (Math.random() - 0.5) * curvature;

    return new Promise((resolve) => {
        const animate = () => {
            const elapsed = Date.now() - startTime;
            let t = Math.min(elapsed / duration, 1);

            // 3차 베지어 곡선 계산
            const currentY = this.cubicBezier(t, startY, cp1y, cp2y, targetY);

            // 랜덤 노이즈 추가 (사람의 손떨림)
            const noise = (Math.random() - 0.5) * 2;
            window.scrollTo(0, currentY + noise);

            if (t < 1) {
                requestAnimationFrame(animate); // 부드러운 애니메이션
            } else {
                resolve();
            }
        };
        requestAnimationFrame(animate);
    });
}
```

**시각적 비교**:
```
일반 스크롤:
Y
│
│        ┌─────────
│        │
│        │
│────────┘
└────────────── Time

Bezier 스크롤 (사람처럼):
Y
│
│      ╭───╮
│    ╭╯     ╰╮
│  ╭╯         ╰╮
│ ╯             ╰
└────────────── Time
  (가속) (감속)
```

---

## 👑 Part 2: 대장봇 (Leader) vs 쫄병봇 (Follower) 제어

### 2.1 현재 구현 상태

#### devices_supabase.py (이미 구현됨)

```python
# 디바이스 등록 시 자동 그룹 할당
@router.post("/devices/register")
async def register_device(request: DeviceRegisterRequest):
    # 그룹 찾기 (8명 미만)
    group = find_available_group()

    if not group:
        # 새 그룹 생성
        group = create_new_group()

    # 디바이스 추가
    device = add_device_to_group(device_id, group.id)

    # 역할 할당
    devices_in_group = get_devices_in_group(group.id)

    if len(devices_in_group) == 1:
        role = "leader"  # 첫 번째 디바이스 = 대장봇
        update_group_leader(group.id, device_id)
    else:
        role = "follower"  # 나머지 = 쫄병봇

    return {
        "group_id": group.id,
        "group_name": group.group_name,
        "role": role  # "leader" 또는 "follower"
    }
```

**결과**:
```
Group_001:
  - Device_001 (leader)   ← 대장봇
  - Device_002 (follower) ← 쫄병봇
  - Device_003 (follower)
  - ...
  - Device_008 (follower)

Group_002:
  - Device_009 (leader)   ← 대장봇
  - Device_010 (follower)
  - ...
```

---

### 2.2 대장봇/쫄병봇 차별화 전략

#### **전략 A: 검색 패턴 차별화** (권장)

**대장봇 (Leader)**:
- 짧은 키워드로 검색 (예: "갤럭시")
- 자동완성 2~3번째 항목 선택
- 스크롤 천천히 (8~12번)
- 체류 시간 길게 (5~8초)

**쫄병봇 (Follower)**:
- 짧은 키워드 + 추가 단어 (예: "갤럭시 케이스")
- 자동완성 무작위 선택
- 스크롤 빠르게 (3~6번)
- 체류 시간 짧게 (3~5초)

---

#### **전략 B: 검색 타이밍 차별화**

**대장봇 (Leader)**:
- 즉시 작업 시작 (claim 후 0~10초)

**쫄병봇 (Follower)**:
- 대장봇 작업 완료 후 시작
- 또는 랜덤 지연 (30초~2분)

---

#### **전략 C: 검색 경로 차별화**

**대장봇 (Leader)**:
- 홈 → 검색 → 상세페이지

**쫄병봇 (Follower)**:
- 카테고리 → 검색 → 상세페이지
- 또는 쇼핑 탭 → 검색 → 상세페이지

---

### 2.3 구체적 구현 방안

#### 방안 1: JavaScript 스크립트 분리

**naver_shopping_leader_v1.js** (대장봇용):
```javascript
config: {
    scrollSpeed: 1000,           // 느리게
    clickDelay: [1000, 1500],    // 여유있게
    dwellTime: [5000, 8000],     // 길게 체류
    maxScrollAttempts: 12,       // 많이 스크롤
    autocompleteIndex: [1, 2]    // 2~3번째 선택
}
```

**naver_shopping_follower_v1.js** (쫄병봇용):
```javascript
config: {
    scrollSpeed: 600,            // 빠르게
    clickDelay: [600, 1000],     // 빠르게
    dwellTime: [3000, 5000],     // 짧게 체류
    maxScrollAttempts: 6,        // 적게 스크롤
    autocompleteIndex: [0, 5]    // 무작위 선택
}
```

**서버 배포**:
```python
# automation.py
SCRIPT_VERSIONS = {
    "v1_leader": {
        "version": "v1",
        "role": "leader",
        "file_name": "naver_shopping_leader_v1.js"
    },
    "v1_follower": {
        "version": "v1",
        "role": "follower",
        "file_name": "naver_shopping_follower_v1.js"
    }
}

@router.get("/automation/script")
async def get_automation_script(role: str = "follower"):
    if role == "leader":
        script_file = "naver_shopping_leader_v1.js"
    else:
        script_file = "naver_shopping_follower_v1.js"
    # ...
```

**Android에서 role 기반 스크립트 요청**:
```java
// TaskExecutor.java
private void ensureScriptLoaded() throws Exception {
    // 디바이스 role 가져오기
    String role = getDeviceRole(); // "leader" 또는 "follower"

    String scriptEndpoint = serverUrl +
        "/zero/api/v1/automation/script?version=latest&role=" + role;

    String scriptResponse = httpGet(scriptEndpoint);
    // ...
}
```

---

#### 방안 2: 단일 스크립트 + 파라미터

**naver_shopping_v1.js 수정**:
```javascript
// Android에서 role 전달
async run(productName, mid, shortKeyword, role = 'follower') {
    // role에 따라 설정 변경
    const config = this.getConfigByRole(role);

    // 대장봇: 체류 시간 길게
    const dwellTime = this.randomBetween(...config.dwellTime);

    // 쫄병봇: 스크롤 적게
    const maxScroll = config.maxScrollAttempts;

    // ...
}

getConfigByRole(role) {
    if (role === 'leader') {
        return {
            scrollSpeed: 1000,
            dwellTime: [5000, 8000],
            maxScrollAttempts: 12
        };
    } else {
        return {
            scrollSpeed: 600,
            dwellTime: [3000, 5000],
            maxScrollAttempts: 6
        };
    }
}
```

**JavaScriptInterface.java**:
```java
public void startAutomation(String productName, String mid, String shortKeyword, String role) {
    String jsCode = String.format(
        "window.NaverShoppingAutomation.run('%s', '%s', '%s', '%s');",
        productName, mid, shortKeyword, role  // role 추가
    );

    webView.evaluateJavascript(jsCode, null);
}
```

---

#### 방안 3: 타이밍 제어 (서버 측)

**traffic.py 수정**:
```python
@router.post("/traffic/claim-work")
async def claim_work(request: ClaimWorkRequest):
    device = get_device(request.device_id)

    if device.role == "follower":
        # 쫄병봇: 같은 그룹 대장봇이 작업 완료했는지 확인
        leader = get_group_leader(device.group_id)

        if leader:
            recent_leader_task = get_recent_task(leader.device_id)

            if recent_leader_task and recent_leader_task.status == "claimed":
                # 대장봇이 아직 작업 중이면 대기
                return {"message": "Waiting for leader"}

    # 작업 할당
    task = assign_task(device.device_id)
    return task
```

---

### 2.4 추천 구현 방안: **방안 2 (단일 스크립트 + 파라미터)**

**장점**:
- APK 재배포 없이 스크립트만 업데이트
- 서버 부하 최소화
- 유지보수 간편

**구현 순서**:
1. naver_shopping_v1.js에 `getConfigByRole()` 추가
2. JavaScriptInterface.java에 role 파라미터 추가
3. TaskExecutor.java에서 디바이스 role 조회
4. TrafficAutomationService.java에서 role 전달

---

## 📊 Part 3: 대장봇/쫄병봇 운영 시나리오

### 시나리오 1: 자연스러운 그룹 트래픽

**목표**: 8명이 한 그룹으로 움직이며 자연스럽게 트래픽 생성

**설정**:
- Group_001: 8개 디바이스
- 상품: "갤럭시 Z 폴드 6"
- 목표 클릭 수: 8회

**실행 흐름**:
```
T+0분:   대장봇 (Device_001) 작업 시작
         → 검색: "갤럭시" → 자동완성 2번째 → 스크롤 12번 → 클릭 → 7초 체류

T+1분:   쫄병 1 (Device_002) 작업 시작 (30초 지연)
         → 검색: "갤럭시 케이스" → 자동완성 4번째 → 스크롤 5번 → 클릭 → 4초 체류

T+2분:   쫄병 2 (Device_003) 작업 시작 (랜덤 지연)
         → 검색: "갤럭시" → 자동완성 1번째 → 스크롤 8번 → 클릭 → 5초 체류

...

T+10분:  쫄병 7 (Device_008) 작업 완료
```

**결과**:
- 네이버 입장에서: "8명의 서로 다른 사용자가 10분에 걸쳐 같은 상품을 클릭"
- 자연스러운 트래픽 패턴

---

### 시나리오 2: 대량 트래픽 (1500대)

**목표**: 하루 10,000개 상품에 대해 각각 10회 클릭

**설정**:
- 그룹 수: 188개 (1500 ÷ 8)
- 작업 수: 10,000개
- 그룹당 작업: 53개 (10,000 ÷ 188)

**전략**:
```sql
-- 작업 생성 시 그룹 할당
INSERT INTO traffic_navershopping (product_name, nv_mid, target_group_id, status)
VALUES ('갤럭시 S24', '12345678', 1, 'pending'),  -- Group_001 전용
       ('아이폰 15', '23456789', 2, 'pending'),  -- Group_002 전용
       ...
```

**claim-work API 수정**:
```python
@router.post("/traffic/claim-work")
async def claim_work(request: ClaimWorkRequest):
    device = get_device(request.device_id)

    # 같은 그룹에 할당된 작업 우선
    task = supabase.table('traffic_navershopping') \
        .select('*') \
        .eq('target_group_id', device.group_id) \
        .eq('status', 'pending') \
        .limit(1) \
        .execute()

    if not task.data:
        # 그룹 전용 작업 없으면 일반 작업
        task = get_any_pending_task()

    return task
```

**결과**:
- Group_001의 8개 디바이스가 모두 "갤럭시 S24"만 클릭
- 10분 내에 8회 클릭 완료
- 자연스러운 그룹 행동 패턴

---

## 🔧 Part 4: 구현 체크리스트

### Phase 1: 기본 차별화 (1시간)
- [ ] naver_shopping_v1.js에 `getConfigByRole(role)` 함수 추가
- [ ] JavaScriptInterface.startAutomation()에 role 파라미터 추가
- [ ] TaskExecutor.claimAndExecuteWork()에서 디바이스 role 조회
- [ ] TrafficAutomationService에서 role 전달

### Phase 2: 타이밍 제어 (2시간)
- [ ] traffic.py에 대장봇 완료 확인 로직 추가
- [ ] 쫄병봇 지연 시간 설정 (30초~2분)
- [ ] Supabase에 `last_leader_complete_at` 컬럼 추가

### Phase 3: 그룹 작업 할당 (3시간)
- [ ] traffic_navershopping 테이블에 `target_group_id` 컬럼 추가
- [ ] 작업 생성 시 그룹 할당 로직
- [ ] claim-work API에서 그룹 우선 할당

### Phase 4: 대시보드 (향후)
- [ ] 그룹별 작업 진행 상황 실시간 표시
- [ ] 대장봇/쫄병봇 활동 로그
- [ ] 그룹 성공률 분석

---

## 📝 요약

### 검색 방식:
1. **짧은 키워드** 입력 → 자동완성 트리거
2. **자동완성 클릭** → ackey/sm 파라미터 획득 (핵심!)
3. **상품명으로 재검색** (ackey 유지)
4. **Bezier 스크롤**로 MID 탐색 (사람처럼)
5. **3가지 전략**으로 상품 클릭
6. **랜덤 체류** 후 완료 보고

### 대장봇/쫄병봇:
- **대장봇**: 천천히, 길게, 정교하게
- **쫄병봇**: 빠르게, 짧게, 무작위로
- **그룹 단위** 작업으로 자연스러운 패턴 생성

### 다음 작업:
1. naver_shopping_v1.js에 role 기반 설정 추가
2. Android에서 role 전달 구현
3. 서버에서 그룹 기반 작업 할당
