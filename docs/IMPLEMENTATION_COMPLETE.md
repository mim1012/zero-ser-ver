# 네이버 쇼핑 자동화 통합 구현 완료 보고서

**프로젝트**: unified-runner.ts → Zero Server Android 통합
**날짜**: 2026-01-19
**상태**: Phase 1 (서버) ✅ 완료, Phase 2 (Android) ✅ 완료

---

## 📋 구현 개요

### 목표
D:/Project/turafic_update/unified-runner.ts의 네이버 쇼핑 트래픽 자동화 로직을 Zero Server Android 클라이언트에 통합

### 아키텍처
- **서버 (FastAPI)**: JavaScript 스크립트 배포 및 작업 관리
- **Android (Java)**: 작업 폴링, WebView 자동화, 서버 통신
- **JavaScript**: 네이버 쇼핑 특화 자동화 (검색/스크롤/클릭)

### 배포 방식
- APK: 프레임워크만 포함 (TaskExecutor, JavaScriptInterface 등)
- 자동화 스크립트: 서버에서 동적 다운로드 (버전 관리)

---

## ✅ Phase 1: 서버 측 구현 (완료)

### 생성된 파일

#### 1. `app/automation_scripts/naver_shopping_v1.js` (19.6KB, 650+ lines)

**핵심 기능:**
- ✅ Bezier 곡선 스크롤 (3차 베지어, requestAnimationFrame)
- ✅ 인간화 타이핑 (랜덤 딜레이 30~60ms)
- ✅ 3가지 MID 탐색 전략
  - URL 파라미터: `a[href*="nv_mid=12345"]`
  - URL 경로: `a[href*="/products/12345"]`
  - ID 속성: `[id="nstore_productId_12345"]`
- ✅ ackey/sm 파라미터 획득 (자동완성 클릭)
- ✅ CAPTCHA/IP 차단 감지
- ✅ Android 브릿지 통합 (reportProgress, reportError, reportComplete)

**코드 예시:**
```javascript
// Bezier 스크롤
async bezierScroll(targetY) {
    const startY = window.scrollY;
    const duration = this.randomBetween(800, 1500);
    // ... 3차 베지어 곡선 계산
    requestAnimationFrame(animate);
}

// 3가지 MID 탐색 전략
async findAndClickMID(mid) {
    // 전략 1: URL 파라미터
    let link = document.querySelector(`a[href*="nv_mid=${mid}"]`);
    // 전략 2: URL 경로
    link = document.querySelector(`a[href*="/products/${mid}"]`);
    // 전략 3: ID 속성
    const container = document.querySelector(`[id="nstore_productId_${mid}"]`);
}
```

#### 2. `app/api/v1/automation.py` (250+ lines)

**API 엔드포인트:**

| 엔드포인트 | 메서드 | 설명 |
|-----------|--------|------|
| `/automation/version` | GET | 최신 버전 정보 |
| `/automation/script` | GET | JavaScript 스크립트 다운로드 |
| `/automation/changelog` | GET | 버전별 변경 내역 |
| `/automation/compatibility` | GET | 앱 버전 호환성 체크 |
| `/automation/health` | GET | 시스템 헬스 체크 |

**테스트 결과:**
```bash
# 버전 정보
$ curl http://localhost:8000/zero/api/v1/automation/version
✅ Response: 200 OK
{
  "latest_version": "v1",
  "min_required_version": "v1",
  "versions": {...}
}

# 스크립트 다운로드
$ curl "http://localhost:8000/zero/api/v1/automation/script?check_only=true"
✅ Response: 200 OK
{
  "checksum": "17c4074f3af90652801f0e1c3c178aee",
  "size_bytes": 19586
}

# 헬스 체크
$ curl http://localhost:8000/zero/api/v1/automation/health
✅ Response: 200 OK
{
  "status": "healthy",
  "available_scripts": [...]
}
```

#### 3. `app/main.py` 수정

```python
from app.api.v1 import automation

app.include_router(automation.router, prefix="/zero/api/v1", tags=["automation"])
```

---

## ✅ Phase 2: Android 측 구현 (완료)

### 생성된 파일

#### 1. `android/TaskExecutor.java` (460+ lines)

**핵심 기능:**
- ✅ 5분마다 서버 작업 폴링 (`/traffic/claim-work`)
- ✅ JavaScript 스크립트 자동 다운로드 및 버전 관리
- ✅ SharedPreferences 캐싱 (오프라인 지원)
- ✅ 에러 처리 (연속 5회 실패 시 중지)
- ✅ TaskExecutionListener 패턴

**코드 예시:**
```java
// 5분마다 폴링
private static final long POLL_INTERVAL_MS = 5 * 60 * 1000;

public void startPolling() {
    pollingRunnable = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> {
                claimAndExecuteWork();
                pollingHandler.postDelayed(this, POLL_INTERVAL_MS);
            }).start();
        }
    };
    pollingHandler.post(pollingRunnable);
}

// 스크립트 버전 관리
private void ensureScriptLoaded() throws Exception {
    String versionResponse = httpGet(serverUrl + "/automation/version");
    JSONObject versionInfo = new JSONObject(versionResponse);
    String latestVersion = versionInfo.getString("latest_version");

    if (!latestVersion.equals(cachedScriptVersion)) {
        // 새 스크립트 다운로드
        downloadScript(latestVersion);
    }
}
```

#### 2. `android/JavaScriptInterface.java` (320+ lines)

**핵심 기능:**
- ✅ JavaScript → Android 메서드 (@JavascriptInterface)
  - `reportProgress(status, dataJson)`
  - `reportError(errorJson)`
  - `reportComplete(metadataJson)`
- ✅ Android → JavaScript 메서드
  - `startAutomation(productName, mid, shortKeyword)`
- ✅ 서버 API 통신 (log, complete, fail)
- ✅ EventListener 패턴

**코드 예시:**
```java
// JavaScript → Android
@JavascriptInterface
public void reportProgress(String status, String dataJson) {
    JSONObject data = new JSONObject(dataJson);

    // 이벤트 리스너 호출
    eventListener.onTaskProgress(status, dataJson);

    // 서버에 로그 전송
    new Thread(() -> sendLog(status, data)).start();
}

// Android → JavaScript
public void startAutomation(String productName, String mid, String shortKeyword) {
    String jsCode = String.format(
        "window.NaverShoppingAutomation.run('%s', '%s', '%s');",
        productName, mid, shortKeyword
    );

    webView.evaluateJavascript(jsCode, null);
}
```

#### 3. `android/TrafficAutomationService.java` (310+ lines)

**핵심 기능:**
- ✅ Foreground Service (백그라운드 실행)
- ✅ WebView 초기화 (백그라운드에서)
- ✅ TaskExecutor와 JavaScriptInterface 연동
- ✅ Notification 업데이트 (진행 상황 표시)
- ✅ START_STICKY (강제 종료 시 재시작)

**코드 예시:**
```java
@Override
public void onCreate() {
    // 1. Foreground Service 시작
    startForeground(NOTIFICATION_ID, createNotification(...));

    // 2. WebView 초기화
    webView = new WebView(this);
    WebViewHelper.initializeWebView(this, webView, configManager);

    // 3. JavaScript Interface 추가
    jsInterface = new JavaScriptInterface(this, webView, SERVER_URL, deviceId);
    webView.addJavascriptInterface(jsInterface, "AndroidInterface");

    // 4. TaskExecutor 시작
    taskExecutor = new TaskExecutor(this, SERVER_URL, deviceId);
    taskExecutor.startPolling();

    // 5. 초기 페이지 로드
    webView.loadUrl("https://m.naver.com/");
}
```

#### 4. `android/AndroidManifest_SAMPLE.xml`

**필수 권한:**
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

**서비스 등록:**
```xml
<service
    android:name="com.zero.automation.TrafficAutomationService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="dataSync"
    android:stopWithTask="false" />
```

#### 5. `android/SERVICE_USAGE_EXAMPLE.java` (230+ lines)

**사용 예시:**
```java
// 서비스 시작
Intent serviceIntent = new Intent(this, TrafficAutomationService.class);
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    startForegroundService(serviceIntent);
} else {
    startService(serviceIntent);
}

// 서비스 중지
stopService(serviceIntent);
```

---

## 🎯 전체 시스템 흐름

### 1. 서비스 시작
```
MainActivity
  └─> startForegroundService(TrafficAutomationService)
      └─> TrafficAutomationService.onCreate()
          ├─> WebView 초기화
          ├─> JavaScriptInterface 추가
          ├─> TaskExecutor.startPolling()
          └─> webView.loadUrl("https://m.naver.com/")
```

### 2. 작업 폴링 (5분 간격)
```
TaskExecutor (5분마다)
  └─> claimAndExecuteWork()
      ├─> ensureScriptLoaded()
      │   └─> GET /automation/version
      │   └─> GET /automation/script (필요시)
      └─> claimWork()
          └─> POST /traffic/claim-work
              └─> { traffic_id, product_name, nv_mid, short_keyword }
```

### 3. 작업 실행
```
TaskExecutor.onExecuteTask()
  └─> jsInterface.setCurrentTask(trafficId, productName, nvMid)
  └─> webView.evaluateJavascript(script)  // 스크립트 주입
  └─> jsInterface.startAutomation(productName, nvMid, shortKeyword)
      └─> window.NaverShoppingAutomation.run(...)
          ├─> 검색창 클릭
          ├─> 짧은 키워드 입력
          ├─> 자동완성 클릭 (ackey 획득)
          ├─> 상품명으로 재검색
          ├─> MID 탐색 (3가지 전략)
          ├─> 상품 클릭
          ├─> 체류 (3~6초)
          └─> reportComplete()
```

### 4. 진행 상황 보고
```
JavaScript (naver_shopping_v1.js)
  └─> window.AndroidInterface.reportProgress(status, data)
      └─> JavaScriptInterface.reportProgress()
          ├─> eventListener.onTaskProgress()  // UI 업데이트
          └─> POST /traffic/log  // 서버 로그
```

### 5. 작업 완료
```
JavaScript
  └─> window.AndroidInterface.reportComplete(metadata)
      └─> JavaScriptInterface.reportComplete()
          ├─> eventListener.onTaskCompleted()
          └─> POST /traffic/complete
              └─> Supabase: status='completed'
```

---

## 📊 파일 구조 요약

### 서버 (D:/Project/zero/app/)
```
app/
├── automation_scripts/
│   └── naver_shopping_v1.js         # 네이버 쇼핑 자동화 JavaScript (19.6KB)
├── api/v1/
│   ├── automation.py                # 스크립트 배포 API (250 lines)
│   ├── traffic.py                   # 작업 관리 API (기존)
│   └── ...
└── main.py                          # FastAPI 앱 (라우터 등록 추가)
```

### Android (D:/Project/zero/android/)
```
android/
├── TaskExecutor.java                # 작업 폴링 오케스트레이터 (460 lines)
├── JavaScriptInterface.java         # JS ↔ Android 브릿지 (320 lines)
├── TrafficAutomationService.java    # Foreground Service (310 lines)
├── AndroidManifest_SAMPLE.xml       # Manifest 설정 가이드
├── SERVICE_USAGE_EXAMPLE.java       # 사용 예제 (230 lines)
├── ConfigManager.java               # 서버 설정 관리 (기존)
├── CustomWebViewClient.java         # WebView 클라이언트 (기존)
└── WebViewHelper.java               # WebView 초기화 (기존)
```

**총 생성 파일**: 8개
**총 코드 라인**: ~2,500 lines

---

## 🧪 테스트 계획

### Phase 1 테스트 (서버) - ✅ 완료
- [x] GET `/automation/version` - 200 OK
- [x] GET `/automation/script` - 19.6KB 다운로드 성공
- [x] GET `/automation/health` - healthy 상태 확인
- [x] FastAPI 서버 실행 성공 (http://localhost:8000)

### Phase 2 테스트 (Android) - 대기
- [ ] TaskExecutor 폴링 로그 확인 (Logcat)
- [ ] JavaScript 스크립트 주입 성공 확인
- [ ] `window.NaverShoppingAutomation` 객체 존재 확인
- [ ] reportProgress → 서버 로그 전송 확인
- [ ] 1개 디바이스 테스트
- [ ] 10개 디바이스 소규모 테스트
- [ ] 1500개 디바이스 대규모 테스트

### 통합 테스트 시나리오
1. Supabase에 작업 1개 생성 (`traffic_navershopping` 테이블)
2. Android 서비스 시작
3. 5분 이내 작업 클레임 확인
4. WebView에서 m.naver.com → 검색 → MID 클릭 확인
5. Notification에서 진행 상황 확인
6. `task_logs` 테이블에서 로그 확인
7. 작업 완료 후 `status='completed'` 확인

---

## 🚀 배포 가이드

### 서버 배포 (Railway)
```bash
cd D:/Project/zero

# 1. Git 커밋
git add app/automation_scripts/ app/api/v1/automation.py app/main.py
git commit -m "feat: Add automation script distribution system

- Add naver_shopping_v1.js (Bezier scroll, 3-strategy MID search)
- Add automation API endpoints (version, script, health)
- Integrate automation router in main.py"

# 2. Railway 배포
git push origin master
# Railway가 자동으로 감지하여 배포 시작

# 3. 배포 확인
curl https://your-server.railway.app/zero/api/v1/automation/version
```

### Android 빌드 및 배포
```bash
# 1. AndroidManifest.xml 수정
# - AndroidManifest_SAMPLE.xml 참고하여 권한 및 서비스 추가

# 2. Gradle 빌드
./gradlew assembleRelease

# 3. APK 서명 (선택 사항)
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore my-release-key.keystore \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  alias_name

# 4. 1500개 디바이스에 배포
# - ADB over network
# - MDM (Mobile Device Management) 솔루션
# - 또는 수동 설치
```

---

## ⚠️ 운영 고려사항

### 서버 리소스
- **FastAPI workers**: 4개 권장
- **Supabase Connection Pool**: 100 권장
- **폴링 간격**: 5분 (1500대 기준, 초당 ~5 requests)
- **Railway 리소스**: 1GB RAM, 1 vCPU 충분

### 모니터링
- **Supabase Dashboard**: 쿼리 성능, 테이블 크기
- **Railway Metrics**: CPU/메모리 사용률, 요청 수
- **task_logs 테이블**: 성공률, 평균 소요 시간, 에러 빈도

### 보안
- [ ] Railway 환경변수에 민감 정보 저장 (SUPABASE_KEY)
- [ ] CORS 허용 도메인 제한 (`allow_origins=["*"]` → 특정 도메인)
- [ ] API Rate Limiting 추가 (DDoS 방지)
- [ ] Android APK 난독화 (ProGuard/R8)

### 장애 대응
- **서버 다운**: Android는 캐시된 스크립트로 계속 작동 (1시간 유효)
- **MySQL 연결 실패**: Supabase만 사용 가능 (작업 관리는 정상)
- **Supabase 다운**: 작업 폴링 실패, 에러 로그 누적
- **Android 강제 종료**: START_STICKY로 자동 재시작

---

## 📈 다음 단계 (Phase 3-6)

### Phase 3: 스크롤 최적화 (3시간)
- ✅ 이미 구현됨 (bezierScroll in naver_shopping_v1.js)
- [ ] GPU 가속 추가 (WebView: `setLayerType(LAYER_TYPE_HARDWARE)`)

### Phase 4: CAPTCHA 처리 (시간 미정)
- [ ] CAPTCHA 감지 → 서버 fail 보고 (현재 구현됨)
- [ ] 2Captcha API 통합 (서버)
- [ ] Android → CAPTCHA 이미지 추출 → 서버 전송 → 해결

### Phase 5: 대시보드 (시간 미정)
- [ ] 실시간 작업 현황 (완료/실패/진행 중)
- [ ] 디바이스별 통계 (작업 수, 성공률)
- [ ] 에러 로그 조회 및 필터링

### Phase 6: 안정성 개선 (시간 미정)
- [ ] WebView 메모리 누수 감지 (LeakCanary)
- [ ] 10회마다 WebView 재생성
- [ ] 네트워크 재연결 로직 (Exponential Backoff)

---

## 🎉 완료 요약

✅ **Phase 1 (서버)**: JavaScript 스크립트 배포 시스템 구축 완료
✅ **Phase 2 (Android)**: 작업 폴링 및 자동화 프레임워크 구축 완료

**다음 작업**: Android APK 빌드 및 1개 디바이스 테스트

**예상 일정**:
- Phase 1~2: 완료 (2026-01-19)
- 테스트 및 배포: 1~2일
- 전체 시스템 안정화: 3~5일

---

## 📞 문의 및 지원

**구현 완료 날짜**: 2026-01-19
**다음 리뷰**: Android APK 빌드 후 테스트 결과 공유
