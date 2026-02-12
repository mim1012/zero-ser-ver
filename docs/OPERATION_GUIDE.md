# Zero Server 운영 가이드

**대상**: 1500개 Android 디바이스 네이버 쇼핑 자동화 시스템

---

## 📱 Part 1: 모바일 설치 및 실행

### 1.1 APK 빌드 (최초 1회)

```bash
# Android 프로젝트 디렉토리에서
cd /path/to/android-project

# 1. AndroidManifest.xml 수정
# D:/Project/zero/android/AndroidManifest_SAMPLE.xml 참고하여:
# - 권한 추가
# - TrafficAutomationService 등록

# 2. Java 파일 복사
cp D:/Project/zero/android/TaskExecutor.java app/src/main/java/com/zero/automation/
cp D:/Project/zero/android/JavaScriptInterface.java app/src/main/java/com/zero/automation/
cp D:/Project/zero/android/TrafficAutomationService.java app/src/main/java/com/zero/automation/

# 3. 서버 URL 설정 (TrafficAutomationService.java)
# private static final String SERVER_URL = "https://your-server.railway.app";

# 4. Release 빌드
./gradlew assembleRelease

# 5. APK 서명
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore my-release-key.keystore \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  alias_name

# 6. zipalign
zipalign -v 4 app-release-unsigned.apk zero-automation.apk
```

**결과물**: `zero-automation.apk` (약 5~10MB)

---

### 1.2 1500개 디바이스에 APK 설치

#### 방법 1: ADB over Network (개발/테스트용)

```bash
# 디바이스 IP 목록 (devices.txt)
192.168.0.101
192.168.0.102
...
192.168.0.1500

# 일괄 설치 스크립트 (install_all.sh)
#!/bin/bash
while IFS= read -r ip; do
  echo "Installing to $ip..."
  adb connect $ip:5555
  adb -s $ip:5555 install -r zero-automation.apk
  adb disconnect $ip:5555
done < devices.txt
```

#### 방법 2: MDM 솔루션 (운영 환경 권장)

**추천 MDM**:
- **Knox Manage** (삼성 디바이스)
- **Google Workspace** (Android Enterprise)
- **Jamf** (크로스 플랫폼)

**MDM 배포 프로세스**:
1. APK를 MDM 콘솔에 업로드
2. 디바이스 그룹 생성 (예: "Traffic_Automation_Group")
3. 1500개 디바이스를 그룹에 할당
4. "앱 설치" 정책 배포
5. 디바이스가 자동으로 APK 다운로드 및 설치

#### 방법 3: 수동 설치 (소규모)

```bash
# Google Drive/Dropbox에 APK 업로드
# 디바이스에서:
1. Chrome 브라우저에서 APK 다운로드 링크 열기
2. "다운로드" → "설치" → "열기"
```

---

### 1.3 디바이스 초기 설정 (디바이스당 1회)

#### Step 1: 권한 허용

앱 실행 시 자동으로 권한 요청 팝업이 뜹니다:

```
1. "알림 권한" → 허용
2. "다른 앱 위에 표시" → 허용 (백그라운드 WebView용)
```

**런타임 권한 코드** (SERVICE_USAGE_EXAMPLE.java에 구현됨):
```java
// Android 13+: 알림 권한
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
}

// Android 6+: 오버레이 권한
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    if (!Settings.canDrawOverlays(this)) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        startActivity(intent);
    }
}
```

#### Step 2: 디바이스 등록

앱이 처음 실행되면 자동으로 서버에 등록됩니다:

```java
// TaskExecutor.java에서 자동으로:
String deviceId = Settings.Secure.getString(
    getContentResolver(),
    Settings.Secure.ANDROID_ID  // 예: "1a2b3c4d5e6f7890"
);

// 서버에 자동 전송됨 (claim-work API 호출 시)
```

**서버 측 자동 등록** (devices_supabase.py):
```python
# POST /zero/api/v1/devices/register
{
    "device_id": "1a2b3c4d5e6f7890",
    "device_model": "SM-G998N",
    "android_version": "13"
}

# 서버 응답:
{
    "group_id": 1,
    "group_name": "Group_001",
    "role": "leader"  # 또는 "follower"
}
```

---

### 1.4 서비스 시작

#### 자동 시작 (앱 실행 시)

**MainActivity.java**:
```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // 1. 서비스 시작 버튼
    Button startButton = findViewById(R.id.btn_start_service);
    startButton.setOnClickListener(v -> {
        Intent serviceIntent = new Intent(this, TrafficAutomationService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);  // Android 8.0+
        } else {
            startService(serviceIntent);
        }

        Toast.makeText(this, "자동화 서비스 시작됨", Toast.LENGTH_SHORT).show();
    });
}
```

#### 부팅 시 자동 시작 (선택 사항)

**BootReceiver.java** (AndroidManifest_SAMPLE.xml 참고):
```java
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, TrafficAutomationService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}
```

**AndroidManifest.xml**:
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver android:name=".BootReceiver">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

---

### 1.5 서비스 실행 확인

#### 방법 1: Notification (사용자)

서비스 시작 후 알림창에 표시됩니다:

```
┌─────────────────────────────────┐
│ 네이버 쇼핑 자동화              │
│ 실행 중                         │
│ 작업 폴링 시작 (5분 간격)      │
└─────────────────────────────────┘
```

작업 실행 시:
```
┌─────────────────────────────────┐
│ 네이버 쇼핑 자동화              │
│ 작업 실행 중                    │
│ Traffic ID: 123 (완료: 5, 실패: 0) │
└─────────────────────────────────┘
```

#### 방법 2: Logcat (개발자)

```bash
adb logcat | grep "TrafficAutomation\|TaskExecutor\|JSInterface"

# 출력 예시:
I/TrafficAutomationService: Service started successfully
I/TaskExecutor: Task polling started (interval: 300 seconds)
I/TaskExecutor: Claiming work from server...
I/TaskExecutor: Work claimed: traffic_id=123, product=갤럭시 Z 폴드 6, mid=12345678
I/JavaScriptInterface: Starting automation: product=갤럭시 Z 폴드 6, mid=12345678
D/JavaScriptInterface: reportProgress: status=started
D/JavaScriptInterface: reportProgress: status=mid_clicked
D/JavaScriptInterface: reportProgress: status=dwelling
I/JavaScriptInterface: reportComplete: {"success":true,"dwell_time_ms":4500}
```

---

## 🖥️ Part 2: 서버 관리

### 2.1 작업 생성 (Supabase)

#### 방법 1: SQL 직접 실행

**Supabase Dashboard → SQL Editor**:

```sql
-- 단일 작업 생성
INSERT INTO traffic_navershopping (
    product_name,
    nv_mid,
    short_keyword,
    status,
    priority,
    created_at
) VALUES (
    '갤럭시 Z 폴드 6 자급제',  -- 상품명
    '12345678',                -- 네이버 상품 ID
    '갤럭시 폴드',             -- 짧은 키워드 (자동완성용)
    'pending',                 -- 초기 상태
    1,                         -- 우선순위 (1=높음)
    NOW()
);

-- 대량 작업 생성 (100개)
INSERT INTO traffic_navershopping (product_name, nv_mid, short_keyword, status, priority)
SELECT
    '갤럭시 S24 ' || generate_series,
    '9000000' || generate_series::text,
    '갤럭시',
    'pending',
    1
FROM generate_series(1, 100);

-- 엑셀에서 가져오기 (CSV 업로드)
COPY traffic_navershopping (product_name, nv_mid, short_keyword, status, priority)
FROM '/path/to/products.csv'
DELIMITER ','
CSV HEADER;
```

#### 방법 2: API 호출 (프로그래밍)

**Python 스크립트**:
```python
import requests

SERVER_URL = "https://your-server.railway.app"

# 단일 작업 생성
def create_task(product_name, nv_mid, short_keyword):
    response = requests.post(
        f"{SERVER_URL}/zero/api/v1/traffic/tasks",  # TODO: 이 API 엔드포인트 추가 필요
        json={
            "product_name": product_name,
            "nv_mid": nv_mid,
            "short_keyword": short_keyword,
            "priority": 1
        }
    )
    return response.json()

# 엑셀 파일에서 대량 생성
import pandas as pd

df = pd.read_excel("products.xlsx")

for _, row in df.iterrows():
    create_task(
        product_name=row['product_name'],
        nv_mid=row['nv_mid'],
        short_keyword=row['short_keyword']
    )
    print(f"Created task for {row['product_name']}")
```

**products.xlsx 예시**:
```
product_name          | nv_mid     | short_keyword
--------------------- | ---------- | -------------
갤럭시 S24 Ultra     | 12345678   | 갤럭시
아이폰 15 Pro        | 23456789   | 아이폰
맥북 프로 M3         | 34567890   | 맥북
```

#### 방법 3: 웹 대시보드 (권장)

**대시보드 UI** (향후 구현 예정):
```
┌─────────────────────────────────────────────┐
│  작업 생성                                  │
├─────────────────────────────────────────────┤
│  상품명: [갤럭시 S24 Ultra               ]  │
│  네이버 MID: [12345678                   ]  │
│  짧은 키워드: [갤럭시                    ]  │
│  우선순위: [높음 ▼]                        │
│                                             │
│  [ 작업 생성 ]  [ CSV 업로드 ]             │
└─────────────────────────────────────────────┘
```

---

### 2.2 작업 모니터링

#### 실시간 작업 현황 조회

**SQL 쿼리**:
```sql
-- 전체 작업 통계
SELECT
    status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage
FROM traffic_navershopping
GROUP BY status;

-- 결과:
-- status      | count | percentage
-- ----------- | ----- | ----------
-- pending     | 850   | 56.67%
-- claimed     | 50    | 3.33%
-- completed   | 500   | 33.33%
-- failed      | 100   | 6.67%
```

**시간대별 완료율**:
```sql
-- 최근 24시간 시간대별 완료 수
SELECT
    DATE_TRUNC('hour', completed_at) as hour,
    COUNT(*) as completed_count
FROM traffic_navershopping
WHERE status = 'completed'
  AND completed_at >= NOW() - INTERVAL '24 hours'
GROUP BY hour
ORDER BY hour DESC;
```

**디바이스별 작업량**:
```sql
-- 디바이스별 완료 작업 수
SELECT
    device_id,
    COUNT(*) as total_tasks,
    COUNT(CASE WHEN status = 'completed' THEN 1 END) as completed,
    COUNT(CASE WHEN status = 'failed' THEN 1 END) as failed,
    ROUND(
        COUNT(CASE WHEN status = 'completed' THEN 1 END) * 100.0 / COUNT(*),
        2
    ) as success_rate
FROM traffic_navershopping
WHERE device_id IS NOT NULL
GROUP BY device_id
ORDER BY total_tasks DESC
LIMIT 20;
```

#### API를 통한 실시간 모니터링

**dashboard.py 사용** (이미 구현됨):
```python
# GET /zero/api/v1/dashboard/logs?device_id=xxx&limit=100
import requests

response = requests.get(
    "https://your-server.railway.app/zero/api/v1/dashboard/logs",
    params={
        "device_id": "1a2b3c4d5e6f7890",
        "limit": 100
    }
)

logs = response.json()
for log in logs['logs']:
    print(f"[{log['timestamp']}] {log['status']}: {log['data']}")
```

---

### 2.3 에러 처리 및 재시도

#### 실패한 작업 조회

```sql
-- 최근 실패 작업
SELECT
    id,
    product_name,
    nv_mid,
    device_id,
    error_message,
    failed_at
FROM traffic_navershopping
WHERE status = 'failed'
ORDER BY failed_at DESC
LIMIT 100;
```

#### 에러 유형별 분류

```sql
-- 에러 유형 통계
SELECT
    CASE
        WHEN error_message LIKE '%CAPTCHA%' THEN 'CAPTCHA'
        WHEN error_message LIKE '%IP_BLOCKED%' THEN 'IP Blocked'
        WHEN error_message LIKE '%NO_MID_MATCH%' THEN 'MID Not Found'
        WHEN error_message LIKE '%TIMEOUT%' THEN 'Timeout'
        ELSE 'Other'
    END as error_type,
    COUNT(*) as count
FROM traffic_navershopping
WHERE status = 'failed'
GROUP BY error_type
ORDER BY count DESC;

-- 결과:
-- error_type     | count
-- -------------- | -----
-- CAPTCHA        | 45
-- NO_MID_MATCH   | 30
-- IP Blocked     | 15
-- Timeout        | 10
```

#### 자동 재시도 설정

**재시도 정책**:
```sql
-- 실패한 작업을 pending으로 복구 (최대 3회까지)
UPDATE traffic_navershopping
SET
    status = 'pending',
    retry_count = COALESCE(retry_count, 0) + 1,
    updated_at = NOW()
WHERE status = 'failed'
  AND error_message NOT LIKE '%CAPTCHA%'  -- CAPTCHA는 재시도 안 함
  AND COALESCE(retry_count, 0) < 3         -- 최대 3회
  AND failed_at >= NOW() - INTERVAL '24 hours';  -- 최근 24시간 내
```

**Python 스크립트 (자동화)**:
```python
import schedule
import time
from supabase import create_client

supabase = create_client(SUPABASE_URL, SUPABASE_KEY)

def auto_retry_failed_tasks():
    """매 1시간마다 실행"""
    result = supabase.rpc('retry_failed_tasks').execute()
    print(f"Retried {result.data['count']} tasks")

# 매 시간마다 실행
schedule.every().hour.do(auto_retry_failed_tasks)

while True:
    schedule.run_pending()
    time.sleep(60)
```

---

### 2.4 디바이스 관리

#### 활성 디바이스 확인

```sql
-- 최근 10분 내 heartbeat가 있는 디바이스
SELECT
    device_id,
    device_model,
    group_id,
    role,
    last_heartbeat,
    current_ip,
    status
FROM devices
WHERE last_heartbeat >= NOW() - INTERVAL '10 minutes'
  AND status = 'active'
ORDER BY last_heartbeat DESC;
```

#### 비활성 디바이스 알림

```sql
-- 30분 이상 heartbeat 없는 디바이스
SELECT
    device_id,
    group_id,
    last_heartbeat,
    EXTRACT(EPOCH FROM (NOW() - last_heartbeat)) / 60 as minutes_since_heartbeat
FROM devices
WHERE last_heartbeat < NOW() - INTERVAL '30 minutes'
  AND status = 'active'
ORDER BY last_heartbeat ASC;
```

#### 그룹별 디바이스 현황

```sql
-- 그룹별 디바이스 수 및 역할
SELECT
    dg.id as group_id,
    dg.group_name,
    COUNT(*) as total_devices,
    COUNT(CASE WHEN d.role = 'leader' THEN 1 END) as leaders,
    COUNT(CASE WHEN d.role = 'follower' THEN 1 END) as followers,
    COUNT(CASE WHEN d.status = 'active' THEN 1 END) as active_devices
FROM device_groups dg
LEFT JOIN devices d ON d.group_id = dg.id
GROUP BY dg.id, dg.group_name
ORDER BY dg.id;
```

---

### 2.5 성능 최적화

#### 작업 우선순위 관리

```sql
-- 우선순위가 높은 작업부터 할당
ALTER TABLE traffic_navershopping ADD COLUMN priority INTEGER DEFAULT 1;

-- claim-work API 수정 (traffic.py)
UPDATE traffic_navershopping
SET status = 'claimed', device_id = 'xxx', claimed_at = NOW()
WHERE id = (
    SELECT id FROM traffic_navershopping
    WHERE status = 'pending'
    ORDER BY priority DESC, created_at ASC  -- 우선순위 높은 것 먼저
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

#### 작업 분산 (지역별)

```sql
-- 디바이스 위치 기반 작업 할당
ALTER TABLE devices ADD COLUMN region VARCHAR(50);

-- 같은 지역의 디바이스에 작업 우선 할당
UPDATE traffic_navershopping
SET status = 'claimed', device_id = :device_id
WHERE id = (
    SELECT tn.id
    FROM traffic_navershopping tn
    LEFT JOIN devices d ON d.region = (
        SELECT region FROM devices WHERE device_id = :device_id
    )
    WHERE tn.status = 'pending'
    ORDER BY (d.region IS NOT NULL) DESC, tn.priority DESC
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

---

### 2.6 로그 관리 및 분석

#### 로그 조회

```sql
-- 최근 작업 로그 (task_logs 테이블)
SELECT
    tl.id,
    tl.traffic_id,
    tn.product_name,
    tl.device_id,
    tl.status,
    tl.data,
    tl.created_at
FROM task_logs tl
JOIN traffic_navershopping tn ON tn.id = tl.traffic_id
ORDER BY tl.created_at DESC
LIMIT 100;
```

#### 평균 작업 소요 시간

```sql
-- 작업별 평균 소요 시간
SELECT
    AVG(EXTRACT(EPOCH FROM (completed_at - claimed_at))) as avg_seconds,
    MIN(EXTRACT(EPOCH FROM (completed_at - claimed_at))) as min_seconds,
    MAX(EXTRACT(EPOCH FROM (completed_at - claimed_at))) as max_seconds
FROM traffic_navershopping
WHERE status = 'completed'
  AND completed_at IS NOT NULL
  AND claimed_at IS NOT NULL;

-- 결과:
-- avg_seconds | min_seconds | max_seconds
-- ----------- | ----------- | -----------
-- 15.3        | 8.2         | 45.7
```

---

## 🚨 Part 3: 장애 대응

### 3.1 일반적인 문제 및 해결

#### 문제 1: 디바이스가 작업을 받지 못함

**증상**:
- Notification에 "대기 중" 계속 표시
- Logcat: "No work available"

**원인 및 해결**:
```sql
-- 1. pending 작업이 있는지 확인
SELECT COUNT(*) FROM traffic_navershopping WHERE status = 'pending';
-- → 0개면 작업 생성 필요

-- 2. 작업은 있는데 claimed 상태로 고착된 경우
SELECT COUNT(*) FROM traffic_navershopping
WHERE status = 'claimed'
  AND claimed_at < NOW() - INTERVAL '10 minutes';
-- → claimed 상태에서 10분 이상 진행 안 된 작업 복구

UPDATE traffic_navershopping
SET status = 'pending', device_id = NULL, claimed_at = NULL
WHERE status = 'claimed'
  AND claimed_at < NOW() - INTERVAL '10 minutes';
```

#### 문제 2: CAPTCHA 빈발

**증상**:
- 많은 작업이 "CAPTCHA_DETECTED" 에러로 실패

**원인**:
- IP당 요청 빈도가 너무 높음
- 네이버가 자동화를 감지함

**해결**:
```sql
-- 1. 실패율 확인
SELECT
    COUNT(CASE WHEN error_message LIKE '%CAPTCHA%' THEN 1 END) as captcha_count,
    COUNT(*) as total_failed,
    ROUND(
        COUNT(CASE WHEN error_message LIKE '%CAPTCHA%' THEN 1 END) * 100.0 / COUNT(*),
        2
    ) as captcha_percentage
FROM traffic_navershopping
WHERE status = 'failed';

-- 2. 폴링 간격 증가 (TaskExecutor.java)
// private static final long POLL_INTERVAL_MS = 5 * 60 * 1000; // 5분
private static final long POLL_INTERVAL_MS = 10 * 60 * 1000; // 10분으로 변경

-- 3. 디바이스 IP 로테이션 (모바일 데이터 재연결)
// Android에서 비행기 모드 ON/OFF로 IP 변경
ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
// 비행기 모드 토글 (root 권한 필요)
```

#### 문제 3: MID를 찾지 못함 (NO_MID_MATCH)

**증상**:
- "NO_MID_MATCH" 에러 빈발

**원인**:
- 잘못된 MID 입력
- 상품이 품절/삭제됨
- 네이버 검색 결과에 나타나지 않음

**해결**:
```sql
-- 1. 실패한 MID 목록 추출
SELECT DISTINCT nv_mid, product_name, COUNT(*) as fail_count
FROM traffic_navershopping
WHERE status = 'failed'
  AND error_message LIKE '%NO_MID_MATCH%'
GROUP BY nv_mid, product_name
ORDER BY fail_count DESC;

-- 2. 수동으로 네이버에서 확인
-- https://msearch.shopping.naver.com/search/all?query={product_name}
-- MID가 실제로 존재하는지 확인

-- 3. 잘못된 작업 삭제
DELETE FROM traffic_navershopping
WHERE nv_mid IN ('12345678', '23456789')  -- 존재하지 않는 MID
  AND status = 'failed';
```

---

### 3.2 서버 장애 대응

#### Railway 서버 다운

**증상**:
- 디바이스에서 "HTTP GET failed: 500" 에러

**해결**:
```bash
# 1. Railway 상태 확인
# https://railway.app/dashboard → 프로젝트 선택 → Deployments

# 2. 로그 확인
railway logs

# 3. 재배포
git push origin master  # Railway가 자동 재배포

# 4. 수동 재시작
railway restart
```

#### Supabase 연결 끊김

**증상**:
- "Can't connect to Supabase" 에러

**해결**:
```python
# supabase_client.py에 재연결 로직 추가
from supabase import create_client
import time

def get_supabase_with_retry(max_retries=3):
    for attempt in range(max_retries):
        try:
            supabase = create_client(SUPABASE_URL, SUPABASE_KEY)
            # 연결 테스트
            supabase.table('devices').select('id').limit(1).execute()
            return supabase
        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(2 ** attempt)  # Exponential backoff
            else:
                raise e
```

---

## 📊 Part 4: 대시보드 (향후 구현)

### 4.1 실시간 모니터링 대시보드

**기능**:
- 전체 작업 현황 (진행 중/완료/실패)
- 디바이스 상태 (온라인/오프라인)
- 시간대별 트래픽 그래프
- 에러 빈도 차트

**기술 스택**:
- Frontend: React + Chart.js
- Backend: FastAPI (dashboard.py)
- WebSocket: 실시간 업데이트

**화면 예시**:
```
┌────────────────────────────────────────────────────────────┐
│  Zero Server Dashboard                                     │
├────────────────────────────────────────────────────────────┤
│  전체 작업: 1500                                          │
│  ■■■■■■■■■□ 완료: 850 (56.7%)                      │
│  ■■□□□□□□□□ 진행 중: 50 (3.3%)                    │
│  ■□□□□□□□□□ 실패: 100 (6.7%)                      │
│  □□□□□□□□□□ 대기 중: 500 (33.3%)                  │
├────────────────────────────────────────────────────────────┤
│  활성 디바이스: 1480 / 1500                               │
│  평균 작업 시간: 15.3초                                   │
│  성공률: 89.5%                                            │
├────────────────────────────────────────────────────────────┤
│  [시간대별 완료 그래프]                                   │
│    │                                                       │
│ 50 │     ▄▄▄                                               │
│ 40 │   ▄▀   ▀▄                                             │
│ 30 │ ▄▀       ▀▄▄                                          │
│    └──────────────────                                    │
│      12  14  16  18  20 (시)                              │
└────────────────────────────────────────────────────────────┘
```

---

## 🔧 Part 5: 유지보수

### 5.1 스크립트 업데이트 (무중단)

**시나리오**: 네이버 쇼핑 UI 변경으로 스크립트 수정 필요

**프로세스**:
```bash
# 1. 새 버전 스크립트 작성
vi app/automation_scripts/naver_shopping_v2.js

# 2. automation.py에 버전 추가
SCRIPT_VERSIONS = {
    "v1": {...},
    "v2": {
        "version": "v2",
        "file_name": "naver_shopping_v2.js",
        "release_date": "2026-01-20",
        "min_android_version": "1.0.0"
    }
}
LATEST_VERSION = "v2"  # 변경

# 3. 서버 배포
git add app/automation_scripts/naver_shopping_v2.js app/api/v1/automation.py
git commit -m "feat: Update automation script to v2"
git push origin master

# 4. 디바이스가 자동으로 새 버전 다운로드 (다음 폴링 때)
# - TaskExecutor.ensureScriptLoaded()가 자동 감지
# - 캐시된 버전 != 서버 최신 버전이면 다운로드
```

**무중단 업데이트**:
- 디바이스는 다음 작업 시작 전에 새 스크립트 다운로드
- 이미 실행 중인 작업은 이전 버전으로 계속 진행
- APK 재배포 불필요!

### 5.2 APK 업데이트 (유지보수)

**Java 코드 변경 시**:
```bash
# 1. 새 APK 빌드
./gradlew assembleRelease

# 2. 버전 코드 증가 (build.gradle)
android {
    defaultConfig {
        versionCode 2  // 1 → 2
        versionName "1.1"
    }
}

# 3. MDM으로 배포 또는 수동 설치
```

### 5.3 데이터베이스 백업

```bash
# Supabase 백업 (자동)
# Settings → Database → Backups
# 매일 자동 백업 (무료 플랜: 7일 보관)

# 수동 백업 (pg_dump)
pg_dump -h db.xxx.supabase.co -U postgres -d postgres > backup_$(date +%Y%m%d).sql

# 복원
psql -h db.xxx.supabase.co -U postgres -d postgres < backup_20260120.sql
```

---

## 📈 Part 6: 확장 및 최적화

### 6.1 디바이스 추가 (1500 → 3000대)

```sql
-- 그룹 크기 변경 (8 → 16)
-- devices_supabase.py
MAX_DEVICES_PER_GROUP = 16  # 8에서 변경
```

### 6.2 서버 스케일업

**Railway 플랜 업그레이드**:
- Hobby ($5/month): 500MB RAM, 0.5 vCPU
- **Pro ($20/month)**: 8GB RAM, 8 vCPU ← 권장 (3000대)

**Supabase 플랜**:
- Free: Connection Pool 60
- **Pro ($25/month)**: Connection Pool 200 ← 권장

---

## 📞 문의 및 지원

**구현 완료**: 2026-01-19
**다음 업데이트**: 대시보드 구현 (예정)
