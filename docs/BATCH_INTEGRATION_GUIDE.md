# 배치 작업 처리 시스템 통합 가이드

## 📋 목차
1. [시스템 개요](#시스템-개요)
2. [서버 설정](#서버-설정)
3. [Android 클라이언트 통합](#android-클라이언트-통합)
4. [테스트 절차](#테스트-절차)
5. [모니터링](#모니터링)
6. [트러블슈팅](#트러블슈팅)

---

## 시스템 개요

### 기존 방식 (단일 작업)
```
디바이스 → [5분 대기] → 서버에서 작업 1개 가져오기 → 실행 → [5분 대기] → ...
```
**문제점:** 작업 간 5분 대기 시간으로 비효율적

### 배치 방식 (새로운 방식)
```
디바이스 → 서버에서 작업 10개 배치 가져오기 →
  작업1 실행 → [2초] → 작업2 실행 → [2초] → ... → 작업10 실행 →
  [5분 대기] → 다음 배치 10개 가져오기 → ...
```
**장점:**
- 서버 부하 10배 감소 (1500대 기준: 5 req/s → 0.5 req/s)
- 작업 처리 속도 향상 (5분 → 2초 간격)
- 연속적인 트래픽 생성

---

## 서버 설정

### 1. 환경 변수 설정 (.env 파일)

**위치:** `D:\Project\zero\.env`

```env
# Supabase Configuration
SUPABASE_URL=https://hdtjkaieulphqwmcjhcx.supabase.co
SUPABASE_KEY=your_supabase_service_role_key

# MySQL (선택사항 - 로컬 개발용)
DATABASE_URL=mysql+pymysql://user:password@localhost:3306/zero_db
```

### 2. 서버 시작

```bash
cd D:/Project/zero

# 개발 모드
uvicorn app.main:app --reload --port 8000

# 프로덕션 모드 (Railway 자동 배포)
git push origin master
```

### 3. API 엔드포인트 확인

배치 API가 정상 작동하는지 확인:

```bash
# 배치 작업 요청 (5개)
curl -X POST "http://localhost:8000/zero/api/v1/traffic/claim-work-batch" \
  -H "Content-Type: application/json" \
  -d '{"device_id": "test_device_001", "batch_size": 5}'

# 응답 예시
{
  "tasks": [
    {
      "traffic_id": 1,
      "slot_id": 0,
      "product_name": "갤럭시 S24 Ultra",
      "nv_mid": "12345678",
      "short_keyword": "갤럭시",
      "target_url": "https://msearch.shopping.naver.com/product/12345678"
    },
    ...
  ],
  "total_claimed": 5
}
```

---

## Android 클라이언트 통합

### 파일 교체

#### 1. TaskExecutor.java 교체

**기존 파일:** `android/TaskExecutor.java`
**새 파일:** `android/TaskExecutor_v2_batch.java`

**변경 사항:**
- ✅ 배치 작업 가져오기 (`claim-work-batch` API)
- ✅ Queue 기반 작업 관리
- ✅ TaskCompletionCallback 인터페이스
- ✅ 작업 간 2초 딜레이

**교체 방법:**
```bash
# 백업
cp android/TaskExecutor.java android/TaskExecutor_v1_backup.java

# 새 버전으로 교체
cp android/TaskExecutor_v2_batch.java android/TaskExecutor.java
```

#### 2. TrafficAutomationService.java 교체

**기존 파일:** `android/TrafficAutomationService.java`
**새 파일:** `android/TrafficAutomationService_v2_batch.java`

**변경 사항:**
- ✅ TaskCompletionCallback 저장 및 호출
- ✅ 작업 완료 시 다음 작업 자동 트리거
- ✅ 큐 크기 Notification 표시

**교체 방법:**
```bash
# 백업
cp android/TrafficAutomationService.java android/TrafficAutomationService_v1_backup.java

# 새 버전으로 교체
cp android/TrafficAutomationService_v2_batch.java android/TrafficAutomationService.java
```

### 빌드 및 배포

```bash
# Android Studio에서 프로젝트 리빌드
./gradlew clean build

# APK 생성
./gradlew assembleRelease

# 테스트 디바이스에 설치
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 테스트 절차

### 1. 서버 측 테스트 데이터 생성

```bash
cd D:/Project/zero

# 테스트 작업 10개 생성
python create_test_tasks.py
```

**확인:**
```python
from app.database.supabase_client import get_supabase
supabase = get_supabase()

# pending 작업 확인
result = supabase.table('distributedTasks').select('*').eq('status', 'pending').execute()
print(f"Pending tasks: {len(result.data)}")
```

### 2. 단일 디바이스 테스트

**시나리오:** 1대의 테스트 디바이스로 배치 처리 검증

#### 예상 동작:
1. 서비스 시작 → 즉시 10개 작업 배치 요청
2. 작업 1 실행 (30~60초)
3. 2초 대기
4. 작업 2 실행 (30~60초)
5. ...
6. 작업 10 완료 후 5분 대기
7. 다음 배치 요청

#### 로그 확인:
```logcat
TaskExecutor: Claimed 10 tasks
TaskExecutor: Processing task [1]: 갤럭시 S24 Ultra
JavaScriptInterface: Task 1 completed
TaskExecutor: Task [1] completed. Processing next task after delay...
TaskExecutor: Processing task [2]: 갤럭시 S24
...
```

### 3. 다중 디바이스 테스트

**시나리오:** 3대의 디바이스로 동시 배치 처리 검증

#### 준비:
```python
# 30개 작업 생성 (각 디바이스당 10개)
# create_test_tasks.py를 3번 실행
python create_test_tasks.py
python create_test_tasks.py
python create_test_tasks.py
```

#### 예상 동작:
- Device A: 작업 1~10 할당
- Device B: 작업 11~20 할당
- Device C: 작업 21~30 할당

각 디바이스는 독립적으로 큐 처리

---

## 모니터링

### 1. Supabase 대시보드에서 실시간 확인

**pending 작업 조회:**
```sql
SELECT COUNT(*) FROM "distributedTasks" WHERE status = 'pending';
```

**assigned 작업 조회 (디바이스별):**
```sql
SELECT "assignedNodeId", COUNT(*) as task_count
FROM "distributedTasks"
WHERE status = 'assigned'
GROUP BY "assignedNodeId";
```

**완료된 작업 조회:**
```sql
SELECT COUNT(*) FROM "distributedTasks" WHERE status = 'completed';
```

### 2. 서버 로그 모니터링

Railway 대시보드 또는 로컬 서버 로그:
```bash
# 배치 요청 로그
INFO: 127.0.0.1:52079 - "POST /zero/api/v1/traffic/claim-work-batch HTTP/1.1" 200 OK

# 작업 완료 로그
INFO: 127.0.0.1:52080 - "POST /zero/api/v1/traffic/complete HTTP/1.1" 200 OK
```

### 3. Android Logcat 모니터링

```bash
adb logcat | grep -E "TaskExecutor|TrafficAutomationService|JavaScriptInterface"
```

**주요 로그:**
```
TaskExecutor: Claimed 10 tasks
TaskExecutor: Processing task [ID] (queue_size=9)
JavaScriptInterface: [대장] Automation started
JavaScriptInterface: Task completed successfully
TaskExecutor: Triggering next task via callback...
```

---

## 성능 메트릭

### 기대 성능 (1500대 기준)

| 항목 | 단일 모드 | 배치 모드 (batch_size=10) |
|------|-----------|---------------------------|
| 서버 요청/초 | ~5 req/s | ~0.5 req/s |
| 작업 간 대기 시간 | 5분 | 2초 |
| 1시간당 작업 처리 (1대) | 12개 | 120개 |
| 1시간당 총 처리 (1500대) | 18,000개 | 180,000개 |

### 측정 방법

**서버 부하 측정:**
```bash
# 1분간 요청 수 카운트
grep "claim-work-batch" server.log | wc -l
```

**작업 처리 속도 측정:**
```sql
-- 최근 1시간 완료된 작업
SELECT COUNT(*)
FROM "distributedTasks"
WHERE status = 'completed'
  AND "completedAt" > NOW() - INTERVAL '1 hour';
```

---

## 트러블슈팅

### 문제 1: 작업이 할당되지 않음

**증상:**
- Logcat에 "No work available" 반복 출력

**확인:**
```sql
SELECT COUNT(*) FROM "distributedTasks" WHERE status = 'pending';
```

**해결:**
```python
# 테스트 작업 생성
python create_test_tasks.py
```

---

### 문제 2: 작업이 assigned 상태로 멈춤

**증상:**
- Supabase에서 많은 작업이 'assigned' 상태로 남아있음
- 완료되지 않음

**원인:**
- 디바이스 오프라인
- JavaScript 에러
- Callback 호출 안됨

**확인:**
```sql
SELECT "assignedNodeId", COUNT(*)
FROM "distributedTasks"
WHERE status = 'assigned'
  AND "assignedAt" < NOW() - INTERVAL '30 minutes'
GROUP BY "assignedNodeId";
```

**해결:**
```sql
-- 30분 이상 된 assigned 작업을 pending으로 되돌리기
UPDATE "distributedTasks"
SET status = 'pending', "assignedNodeId" = NULL, "assignedAt" = NULL
WHERE status = 'assigned'
  AND "assignedAt" < NOW() - INTERVAL '30 minutes';
```

---

### 문제 3: Callback이 호출되지 않음

**증상:**
- 첫 번째 작업만 실행되고 멈춤
- 큐에 작업은 있지만 처리 안됨

**Logcat 확인:**
```
TaskExecutor: Processing task [1]: ...
JavaScriptInterface: Task completed
(다음 로그 없음)
```

**원인:**
- `currentCallback`이 null
- `onTaskCompleted`에서 callback 호출 안됨

**해결:**
```java
// TrafficAutomationService.java 확인
@Override
public void onTaskCompleted(int trafficId, boolean success) {
    // 이 부분이 있는지 확인
    if (currentCallback != null) {
        currentCallback.onComplete(); // ← 필수!
    }
}
```

---

### 문제 4: 서버 에러 500

**증상:**
```
POST /zero/api/v1/traffic/claim-work-batch HTTP/1.1" 500 Internal Server Error
```

**확인:**
```bash
# 서버 로그 확인
tail -f railway.log

# 또는 Railway 대시보드에서 Logs 확인
```

**일반적인 원인:**
1. Supabase 연결 실패 → SUPABASE_URL, SUPABASE_KEY 확인
2. 테이블 없음 → `database_setup.sql` 실행
3. Enum 타입 불일치 → `taskType`, `targetNodeType` 값 확인

---

## 롤백 절차

배치 모드에서 문제 발생 시 단일 모드로 복원:

```bash
# 1. Android 파일 복원
cp android/TaskExecutor_v1_backup.java android/TaskExecutor.java
cp android/TrafficAutomationService_v1_backup.java android/TrafficAutomationService.java

# 2. APK 재빌드 및 배포
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# 3. 서비스 재시작
adb shell am stopservice com.zero.automation/.TrafficAutomationService
adb shell am startservice com.zero.automation/.TrafficAutomationService
```

---

## 추가 최적화 (선택사항)

### 1. 배치 크기 동적 조절

디바이스 성능에 따라 batch_size 조절:

```java
// TaskExecutor.java
private int getBatchSize() {
    // 고성능 디바이스: 20개
    if (isHighEndDevice()) {
        return 20;
    }
    // 일반 디바이스: 10개
    return 10;
}
```

### 2. 우선순위 기반 작업 할당

```sql
-- Supabase에서 priority 컬럼 활용
UPDATE "distributedTasks" SET priority = 2 WHERE "productName" LIKE '갤럭시%';
UPDATE "distributedTasks" SET priority = 1 WHERE "productName" LIKE '아이폰%';
```

서버는 자동으로 priority 높은 순서로 할당합니다.

### 3. 작업 타임아웃 설정

```java
// TaskExecutor.java
private static final long TASK_TIMEOUT_MS = 5 * 60 * 1000; // 5분

Handler timeoutHandler = new Handler();
timeoutHandler.postDelayed(() -> {
    Log.e(TAG, "Task timeout! Moving to next task.");
    if (currentCallback != null) {
        currentCallback.onComplete();
    }
}, TASK_TIMEOUT_MS);
```

---

## 결론

배치 작업 처리 시스템으로 전환하면:

✅ 서버 부하 10배 감소
✅ 작업 처리 속도 150배 향상 (5분 → 2초)
✅ 연속적인 트래픽 생성으로 자연스러운 패턴
✅ 1500대 규모에서도 안정적인 운영

**다음 단계:** 프로덕션 환경에 적용 후 모니터링하며 batch_size, 딜레이 시간 등을 최적화하세요.
