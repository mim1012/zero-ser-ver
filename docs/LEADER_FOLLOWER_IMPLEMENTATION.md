# 대장봇/쫄병봇 차별화 구현 가이드

## ✅ 구현 완료 상태

### 1. JavaScript 스크립트 생성 완료
- **파일**: `app/automation_scripts/naver_shopping_v2_role.js`
- **크기**: ~24KB
- **핵심 기능**:
  - `getConfigByRole(role)` - 역할별 설정 반환
  - Leader: 천천히, 정교하게, 5~8초 체류
  - Follower: 빠르게, 무작위로, 3~5초 체류

---

## 📋 다음 구현 단계

### Step 1: 서버 automation.py 수정

**파일**: `app/api/v1/automation.py`

```python
# 스크립트 버전 정보
SCRIPT_VERSIONS = {
    "v1": {...},
    "v2": {  # 추가
        "version": "v2",
        "file_name": "naver_shopping_v2_role.js",
        "release_date": "2026-01-20",
        "description": "Role-based leader/follower bot differentiation",
        "min_android_version": "1.0.0"
    }
}

# 최신 버전
LATEST_VERSION = "v2"  # v1 → v2로 변경
```

---

### Step 2: Android JavaScriptInterface 수정

**파일**: `android/JavaScriptInterface.java`

#### 현재 코드:
```java
public void startAutomation(String productName, String mid, String shortKeyword) {
    String jsCode = String.format(
        "window.NaverShoppingAutomation.run('%s', '%s', '%s');",
        productName, mid, shortKeyword
    );
    webView.evaluateJavascript(jsCode, null);
}
```

#### 수정 후:
```java
// 생성자에서 device role 저장
private String deviceRole;

public JavaScriptInterface(Context context, WebView webView, String serverUrl, String deviceId, String role) {
    this.context = context;
    this.webView = webView;
    this.serverUrl = serverUrl;
    this.deviceId = deviceId;
    this.deviceRole = role;  // "leader" 또는 "follower"
    this.mainHandler = new Handler(Looper.getMainLooper());
}

// startAutomation에 role 추가
public void startAutomation(String productName, String mid, String shortKeyword) {
    String jsCode = String.format(
        "window.NaverShoppingAutomation.run('%s', '%s', '%s', '%s');",
        productName, mid, shortKeyword, deviceRole  // role 추가
    );

    Log.i(TAG, String.format("Starting automation: role=%s, product=%s, mid=%s",
        deviceRole, productName, mid));

    mainHandler.post(() -> {
        webView.evaluateJavascript(jsCode, result -> {
            if (result != null) {
                Log.d(TAG, "JavaScript execution result: " + result);
            }
        });
    });
}
```

---

### Step 3: TrafficAutomationService 수정

**파일**: `android/TrafficAutomationService.java`

#### 수정 사항:
```java
@Override
public void onCreate() {
    super.onCreate();

    // ...

    // 1. Device role 조회
    String deviceRole = getDeviceRole(); // "leader" 또는 "follower"

    // 2. JavaScript Interface 생성 시 role 전달
    jsInterface = new JavaScriptInterface(
        this,
        webView,
        SERVER_URL,
        deviceId,
        deviceRole  // role 추가
    );

    webView.addJavascriptInterface(jsInterface, "AndroidInterface");

    // ...
}

/**
 * 서버에서 디바이스 role 조회
 */
private String getDeviceRole() {
    try {
        // ConfigManager 또는 SharedPreferences에서 캐시된 role 조회
        SharedPreferences prefs = getSharedPreferences("DeviceInfo", Context.MODE_PRIVATE);
        String cachedRole = prefs.getString("device_role", null);

        if (cachedRole != null) {
            Log.i(TAG, "Using cached device role: " + cachedRole);
            return cachedRole;
        }

        // 캐시 없으면 서버에서 조회
        String url = SERVER_URL + "/zero/api/v1/devices/info?device_id=" + deviceId;
        // HTTP GET 요청...
        // JSONObject response = httpGet(url);
        // String role = response.getString("role");

        // 임시로 follower 반환 (실제 구현 시 서버 API 호출)
        String role = "follower";

        // 캐시 저장
        prefs.edit().putString("device_role", role).apply();

        return role;

    } catch (Exception e) {
        Log.e(TAG, "Failed to get device role", e);
        return "follower";  // 기본값
    }
}
```

---

### Step 4: 서버 API 추가 (devices_supabase.py)

**새 엔드포인트**: `GET /devices/info?device_id=xxx`

```python
@router.get("/devices/info")
async def get_device_info(device_id: str):
    """
    디바이스 정보 조회 (role 포함)

    Returns:
        - device_id: 디바이스 ID
        - group_id: 그룹 ID
        - role: "leader" 또는 "follower"
        - group_name: 그룹 이름
    """
    supabase = get_supabase()

    result = supabase.table('devices') \
        .select('device_id, group_id, role') \
        .eq('device_id', device_id) \
        .single() \
        .execute()

    if not result.data:
        raise HTTPException(status_code=404, detail="Device not found")

    device = result.data

    # 그룹 정보 조회
    group_result = supabase.table('device_groups') \
        .select('group_name') \
        .eq('id', device['group_id']) \
        .single() \
        .execute()

    return {
        "device_id": device['device_id'],
        "group_id": device['group_id'],
        "role": device['role'],
        "group_name": group_result.data['group_name'] if group_result.data else None
    }
```

---

## 🧪 테스트 방법

### 1. 로컬 테스트 (단일 디바이스)

#### 1-1. Leader 테스트
```java
// MainActivity.java
String testRole = "leader";
Intent serviceIntent = new Intent(this, TrafficAutomationService.class);
serviceIntent.putExtra("test_role", testRole);
startForegroundService(serviceIntent);
```

**예상 동작**:
- Logcat: `[대장] 대장봇 모드 활성화 (Leader Mode)`
- 스크롤: 천천히 (1000ms 간격)
- 자동완성: 2~3번째 선택
- 체류 시간: 5~8초

#### 1-2. Follower 테스트
```java
String testRole = "follower";
// ...
```

**예상 동작**:
- Logcat: `[쫄병] 쫄병봇 모드 활성화 (Follower Mode)`
- 스크롤: 빠르게 (600ms 간격)
- 자동완성: 무작위 선택
- 체류 시간: 3~5초

---

### 2. 그룹 테스트 (8개 디바이스)

#### 시나리오: Group_001의 8개 디바이스가 같은 상품 클릭

**Supabase 작업 생성**:
```sql
INSERT INTO traffic_navershopping (product_name, nv_mid, short_keyword, target_group_id, status)
VALUES ('갤럭시 Z 폴드 6', '12345678', '갤럭시', 1, 'pending')
RETURNING *;
```

**기대 결과**:
```
T+0:00  Device_001 (Leader) 시작
        → 자동완성 2번째 선택
        → 12번 스크롤
        → 7.2초 체류

T+0:30  Device_002 (Follower) 시작 (30초 지연)
        → 자동완성 4번째 선택
        → 6번 스크롤
        → 4.1초 체류

T+1:00  Device_003 (Follower) 시작
        → 자동완성 1번째 선택
        → 5번 스크롤
        → 3.8초 체류

... (나머지 디바이스)

T+10:00 모든 작업 완료
```

**검증**:
```sql
-- 그룹별 평균 체류 시간 확인
SELECT
    d.role,
    AVG(CAST(tn.metadata->>'dwell_time_ms' AS INTEGER)) as avg_dwell_ms,
    COUNT(*) as task_count
FROM traffic_navershopping tn
JOIN devices d ON d.device_id = tn.device_id
WHERE tn.status = 'completed'
  AND d.group_id = 1
GROUP BY d.role;

-- 예상 결과:
-- role     | avg_dwell_ms | task_count
-- -------- | ------------ | ----------
-- leader   | 6500         | 1
-- follower | 4100         | 7
```

---

## 🎯 역할별 차이점 요약

| 항목 | 대장봇 (Leader) | 쫄병봇 (Follower) |
|------|----------------|------------------|
| **체류 시간** | 5~8초 | 3~5초 |
| **스크롤 속도** | 1000ms (느림) | 600ms (빠름) |
| **스크롤 횟수** | 최대 12번 | 최대 8번 |
| **자동완성 선택** | 2~3번째 | 무작위 (1~6번째) |
| **타이핑 속도** | 40~80ms/글자 | 20~50ms/글자 |
| **로그 태그** | `[대장]` | `[쫄병]` |

---

## 📊 서버 배포 체크리스트

### 1. 파일 확인
- [x] `app/automation_scripts/naver_shopping_v2_role.js` 생성 완료

### 2. automation.py 수정
- [ ] SCRIPT_VERSIONS에 v2 추가
- [ ] LATEST_VERSION = "v2"로 변경

### 3. devices_supabase.py 수정
- [ ] GET /devices/info 엔드포인트 추가

### 4. Git 커밋 및 배포
```bash
cd D:/Project/zero

git add app/automation_scripts/naver_shopping_v2_role.js
git add app/api/v1/automation.py
git add app/api/v1/devices_supabase.py

git commit -m "feat: Add role-based bot differentiation (leader/follower)

- Add naver_shopping_v2_role.js with role-specific configurations
- Leader: slower (5-8s dwell), detailed behavior
- Follower: faster (3-5s dwell), random behavior
- Add GET /devices/info API endpoint
- Update automation.py to v2"

git push origin master
```

### 5. Android APK 업데이트
- [ ] JavaScriptInterface.java 수정 (role 파라미터 추가)
- [ ] TrafficAutomationService.java 수정 (getDeviceRole 추가)
- [ ] APK 빌드 및 배포

---

## 🔄 롤백 계획

**문제 발생 시 v1으로 롤백**:

### 서버 측:
```python
# automation.py
LATEST_VERSION = "v1"  # v2 → v1로 변경
```

```bash
git revert HEAD
git push origin master
```

### Android 측:
- 이전 APK 재설치
- 또는 role 파라미터 제거 (v1은 role 없이 동작)

---

## 📈 성능 모니터링

### Supabase 쿼리

#### 1. 역할별 성공률
```sql
SELECT
    d.role,
    COUNT(*) as total_tasks,
    COUNT(CASE WHEN tn.status = 'completed' THEN 1 END) as completed,
    ROUND(COUNT(CASE WHEN tn.status = 'completed' THEN 1 END) * 100.0 / COUNT(*), 2) as success_rate
FROM traffic_navershopping tn
JOIN devices d ON d.device_id = tn.device_id
GROUP BY d.role;
```

#### 2. 역할별 평균 작업 시간
```sql
SELECT
    d.role,
    AVG(EXTRACT(EPOCH FROM (tn.completed_at - tn.claimed_at))) as avg_seconds,
    AVG(CAST(tn.metadata->>'dwell_time_ms' AS INTEGER)) as avg_dwell_ms
FROM traffic_navershopping tn
JOIN devices d ON d.device_id = tn.device_id
WHERE tn.status = 'completed'
GROUP BY d.role;
```

#### 3. 그룹별 역할 분포
```sql
SELECT
    dg.group_name,
    COUNT(CASE WHEN d.role = 'leader' THEN 1 END) as leaders,
    COUNT(CASE WHEN d.role = 'follower' THEN 1 END) as followers
FROM device_groups dg
JOIN devices d ON d.group_id = dg.id
GROUP BY dg.group_name
ORDER BY dg.group_name;
```

---

## 💡 추가 개선 아이디어

### 1. 타이밍 제어
**쫄병봇이 대장봇 완료 후 시작**:
```python
# traffic.py
@router.post("/traffic/claim-work")
async def claim_work(request: ClaimWorkRequest):
    device = get_device(request.device_id)

    if device.role == "follower":
        # 같은 그룹 대장봇의 최근 작업 확인
        leader = get_group_leader(device.group_id)
        recent_leader_task = get_recent_task(leader.device_id)

        if recent_leader_task.status == "claimed":
            # 대장봇이 아직 작업 중이면 대기
            return {"message": "Waiting for leader", "wait_seconds": 30}

    # 작업 할당...
```

### 2. 검색 경로 차별화
**대장봇**: 홈 → 검색
**쫄병봇**: 쇼핑 탭 → 검색

### 3. 그룹 동기화
**8명이 함께 움직이는 패턴**:
- 대장봇 시작
- 5분 후: 쫄병 1~3 시작
- 10분 후: 쫄병 4~7 시작

---

## 📞 문의

**구현 완료 날짜**: 2026-01-20
**다음 단계**: 서버 배포 → Android 업데이트 → 그룹 테스트
