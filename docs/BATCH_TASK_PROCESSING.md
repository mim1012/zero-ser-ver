# 배치 작업 처리 방식 (테이블에서 작업 목록 가져오기)

## 📋 변경 사항 개요

### 기존 방식 (단일 작업)
```
폴링 (5분) → claim 1개 → 실행 → 완료 → 대기 → 폴링 (5분) → ...
```

### 신규 방식 (배치 작업)
```
폴링 (5분) → claim 10개 → 실행 1 → 실행 2 → ... → 실행 10 → 폴링 (5분) → ...
```

**장점**:
- 폴링 횟수 감소 (서버 부하 ↓)
- 연속 작업 처리 (효율 ↑)
- 1500대 기준: 초당 5 req → 0.5 req (10배 감소)

---

## 🔄 Part 1: 서버 API 수정

### 1.1 새 엔드포인트: `/traffic/claim-work-batch`

**파일**: `app/api/v1/traffic.py`

```python
from typing import List
from pydantic import BaseModel

class ClaimWorkBatchRequest(BaseModel):
    device_id: str
    batch_size: int = 10  # 한 번에 가져올 작업 수

class TaskItem(BaseModel):
    traffic_id: int
    product_name: str
    nv_mid: str
    short_keyword: str = ""
    priority: int = 1

class ClaimWorkBatchResponse(BaseModel):
    device_id: str
    tasks: List[TaskItem]
    total_claimed: int
    message: str

@router.post("/traffic/claim-work-batch", response_model=ClaimWorkBatchResponse)
async def claim_work_batch(request: ClaimWorkBatchRequest):
    """
    배치로 여러 작업 한 번에 가져오기

    Args:
        device_id: 디바이스 ID
        batch_size: 가져올 작업 수 (기본 10개, 최대 50개)

    Returns:
        tasks: 할당된 작업 목록
        total_claimed: 실제 할당된 작업 수
    """
    supabase = get_supabase()

    # batch_size 제한 (최대 50개)
    batch_size = min(request.batch_size, 50)

    claimed_tasks = []

    for i in range(batch_size):
        try:
            # 작업 1개씩 원자적으로 할당
            result = supabase.table('traffic_navershopping') \
                .select('id, product_name, nv_mid, short_keyword, priority, slot_naver(*)') \
                .eq('status', 'pending') \
                .order('priority', desc=True) \
                .order('created_at', desc=False) \
                .limit(1) \
                .execute()

            if not result.data or len(result.data) == 0:
                break  # 더 이상 작업 없음

            task = result.data[0]
            traffic_id = task['id']

            # 상태 업데이트
            update_result = supabase.table('traffic_navershopping') \
                .update({
                    'status': 'claimed',
                    'device_id': request.device_id,
                    'claimed_at': datetime.now().isoformat()
                }) \
                .eq('id', traffic_id) \
                .eq('status', 'pending') \
                .execute()

            if update_result.data:
                claimed_tasks.append({
                    'traffic_id': traffic_id,
                    'product_name': task['product_name'],
                    'nv_mid': task['nv_mid'],
                    'short_keyword': task.get('short_keyword', ''),
                    'priority': task.get('priority', 1)
                })

        except Exception as e:
            logger.error(f"Error claiming task {i+1}: {str(e)}")
            continue

    return {
        "device_id": request.device_id,
        "tasks": claimed_tasks,
        "total_claimed": len(claimed_tasks),
        "message": f"Successfully claimed {len(claimed_tasks)} tasks"
    }
```

---

## 🤖 Part 2: Android TaskExecutor 수정

### 2.1 TaskExecutor.java 수정

**파일**: `android/TaskExecutor.java`

```java
public class TaskExecutor {
    private static final long POLL_INTERVAL_MS = 5 * 60 * 1000; // 5분
    private static final int BATCH_SIZE = 10; // 한 번에 가져올 작업 수

    // 작업 큐
    private Queue<JSONObject> taskQueue = new LinkedList<>();
    private boolean isProcessingTasks = false;

    /**
     * 작업 클레임 및 실행 (배치 방식)
     */
    private void claimAndExecuteWork() {
        try {
            Log.d(TAG, "Claiming work batch from server...");

            // 1. 스크립트 최신 버전 확인
            ensureScriptLoaded();

            // 2. 배치로 여러 작업 가져오기
            JSONArray tasks = claimWorkBatch(BATCH_SIZE);

            if (tasks == null || tasks.length() == 0) {
                Log.d(TAG, "No work available");
                consecutiveErrors = 0;

                if (taskExecutionListener != null) {
                    mainHandler.post(() -> taskExecutionListener.onNoWork());
                }
                return;
            }

            Log.i(TAG, String.format("Claimed %d tasks", tasks.length()));

            // 3. 작업 큐에 추가
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                taskQueue.offer(task);
            }

            // 4. 작업 순차 처리 시작
            if (!isProcessingTasks) {
                processNextTask();
            }

            consecutiveErrors = 0;

        } catch (Exception e) {
            Log.e(TAG, "Error claiming work", e);
            handleError(e.getMessage());
        }
    }

    /**
     * 배치로 여러 작업 가져오기
     *
     * @param batchSize 가져올 작업 수
     * @return 작업 배열 (JSONArray)
     */
    private JSONArray claimWorkBatch(int batchSize) throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/claim-work-batch";

        JSONObject requestBody = new JSONObject();
        requestBody.put("device_id", deviceId);
        requestBody.put("batch_size", batchSize);

        String response = httpPost(endpoint, requestBody.toString());

        if (response == null || response.isEmpty()) {
            return new JSONArray();
        }

        JSONObject json = new JSONObject(response);
        return json.getJSONArray("tasks");
    }

    /**
     * 다음 작업 처리
     */
    private void processNextTask() {
        if (taskQueue.isEmpty()) {
            isProcessingTasks = false;
            Log.d(TAG, "All tasks completed, waiting for next poll");
            return;
        }

        isProcessingTasks = true;

        try {
            JSONObject task = taskQueue.poll();

            int trafficId = task.getInt("traffic_id");
            String productName = task.getString("product_name");
            String nvMid = task.getString("nv_mid");
            String shortKeyword = task.optString("short_keyword", "");

            Log.i(TAG, String.format("Processing task %d/%d: traffic_id=%d, product=%s",
                (BATCH_SIZE - taskQueue.size()), BATCH_SIZE, trafficId, productName));

            // WebView에서 실행 (메인 스레드)
            if (taskExecutionListener != null) {
                mainHandler.post(() -> {
                    taskExecutionListener.onExecuteTask(
                        trafficId,
                        productName,
                        nvMid,
                        shortKeyword,
                        cachedScript,
                        new TaskCompletionCallback() {
                            @Override
                            public void onComplete() {
                                // 작업 완료 후 다음 작업 처리
                                new Handler().postDelayed(() -> {
                                    processNextTask();
                                }, 2000); // 2초 대기 후 다음 작업
                            }

                            @Override
                            public void onError(String error) {
                                // 에러 발생해도 다음 작업 계속
                                Log.e(TAG, "Task error: " + error);
                                new Handler().postDelayed(() -> {
                                    processNextTask();
                                }, 2000);
                            }
                        }
                    );
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing task", e);
            // 에러 발생해도 다음 작업 계속
            processNextTask();
        }
    }

    /**
     * TaskCompletionCallback - 작업 완료 콜백
     */
    public interface TaskCompletionCallback {
        void onComplete();
        void onError(String error);
    }

    /**
     * TaskExecutionListener - 작업 실행 콜백 (수정)
     */
    public interface TaskExecutionListener {
        void onExecuteTask(int trafficId, String productName, String nvMid,
                          String shortKeyword, String script,
                          TaskCompletionCallback callback);
        void onNoWork();
        void onError(String error);
    }
}
```

---

## 🔧 Part 3: TrafficAutomationService 수정

### 3.1 작업 완료 콜백 처리

**파일**: `android/TrafficAutomationService.java`

```java
public class TrafficAutomationService extends Service {

    private TaskExecutor.TaskCompletionCallback currentCallback;

    @Override
    public void onCreate() {
        super.onCreate();

        // ... (기존 코드)

        // TaskExecutor 리스너 설정
        taskExecutor.setTaskExecutionListener(
            new TaskExecutor.TaskExecutionListener() {
                @Override
                public void onExecuteTask(int trafficId, String productName, String nvMid,
                                        String shortKeyword, String script,
                                        TaskExecutor.TaskCompletionCallback callback) {

                    currentCallback = callback;

                    Log.i(TAG, String.format("Executing task: traffic_id=%d, product=%s, mid=%s",
                        trafficId, productName, nvMid));

                    // JavaScript Interface에 현재 작업 설정
                    jsInterface.setCurrentTask(trafficId, productName, nvMid);

                    // 스크립트 주입
                    injectScript(script, () -> {
                        // 자동화 시작
                        jsInterface.startAutomation(productName, nvMid, shortKeyword);
                    });
                }

                @Override
                public void onNoWork() {
                    Log.d(TAG, "No work available, waiting...");
                    updateNotification("대기 중", "작업 없음");
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "TaskExecutor error: " + error);
                    updateNotification("에러 발생", error);
                }
            }
        );

        // JavaScript Interface 리스너 설정
        jsInterface.setEventListener(new JavaScriptInterface.EventListener() {
            @Override
            public void onTaskStarted(int trafficId) {
                updateNotification("작업 실행 중", "Traffic ID: " + trafficId);
            }

            @Override
            public void onTaskProgress(String status, String data) {
                Log.d(TAG, String.format("Task progress: %s", status));
            }

            @Override
            public void onTaskCompleted(int trafficId, boolean success) {
                Log.i(TAG, String.format("Task completed: %d (success: %s)", trafficId, success));

                // 작업 완료 콜백 호출 (다음 작업으로 이동)
                if (currentCallback != null) {
                    currentCallback.onComplete();
                    currentCallback = null;
                }
            }

            @Override
            public void onTaskError(int trafficId, String error) {
                Log.e(TAG, String.format("Task error: %d - %s", trafficId, error));

                // 에러 콜백 호출 (다음 작업으로 이동)
                if (currentCallback != null) {
                    currentCallback.onError(error);
                    currentCallback = null;
                }
            }
        });

        // 폴링 시작
        taskExecutor.startPolling();
    }
}
```

---

## 📊 Part 4: 테이블 구조 (Supabase)

### 4.1 기존 테이블 활용

**테이블**: `traffic_navershopping`

```sql
-- 테이블 구조 (기존)
CREATE TABLE traffic_navershopping (
    id SERIAL PRIMARY KEY,
    product_name VARCHAR(255) NOT NULL,
    nv_mid VARCHAR(50) NOT NULL,
    short_keyword VARCHAR(100),
    status VARCHAR(20) DEFAULT 'pending',  -- pending, claimed, completed, failed
    device_id VARCHAR(100),
    priority INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT NOW(),
    claimed_at TIMESTAMP,
    completed_at TIMESTAMP,
    metadata JSONB
);

-- 인덱스 (성능 최적화)
CREATE INDEX idx_status_priority ON traffic_navershopping(status, priority DESC, created_at ASC);
CREATE INDEX idx_device_id ON traffic_navershopping(device_id);
```

### 4.2 대량 작업 생성

**방법 1: SQL**

```sql
-- 엑셀/CSV에서 가져온 상품 목록 삽입
INSERT INTO traffic_navershopping (product_name, nv_mid, short_keyword, status, priority)
VALUES
    ('갤럭시 S24 Ultra', '12345678', '갤럭시', 'pending', 1),
    ('아이폰 15 Pro', '23456789', '아이폰', 'pending', 1),
    ('맥북 프로 M3', '34567890', '맥북', 'pending', 1),
    -- ... 10,000개
    ('에어팟 프로 2세대', '99999999', '에어팟', 'pending', 1);
```

**방법 2: Python 스크립트**

```python
import pandas as pd
from supabase import create_client

# CSV 파일 읽기
df = pd.read_csv('products.csv')

# Supabase 연결
supabase = create_client(SUPABASE_URL, SUPABASE_KEY)

# 배치 삽입 (1000개씩)
batch_size = 1000

for i in range(0, len(df), batch_size):
    batch = df[i:i+batch_size]

    tasks = []
    for _, row in batch.iterrows():
        tasks.append({
            'product_name': row['product_name'],
            'nv_mid': row['nv_mid'],
            'short_keyword': row['short_keyword'],
            'status': 'pending',
            'priority': 1
        })

    result = supabase.table('traffic_navershopping').insert(tasks).execute()
    print(f"Inserted {len(tasks)} tasks (batch {i//batch_size + 1})")
```

**products.csv 예시**:
```csv
product_name,nv_mid,short_keyword
갤럭시 S24 Ultra,12345678,갤럭시
아이폰 15 Pro,23456789,아이폰
맥북 프로 M3,34567890,맥북
...
```

---

## 🧪 Part 5: 테스트

### 5.1 로컬 테스트

#### Step 1: 테스트 작업 생성

```sql
-- Supabase SQL Editor
INSERT INTO traffic_navershopping (product_name, nv_mid, short_keyword, status, priority)
VALUES
    ('갤럭시 S24 Ultra', '12345678', '갤럭시', 'pending', 1),
    ('갤럭시 S24', '12345679', '갤럭시', 'pending', 1),
    ('갤럭시 S23', '12345680', '갤럭시', 'pending', 1),
    ('갤럭시 Z 폴드 6', '12345681', '갤럭시', 'pending', 1),
    ('갤럭시 Z 플립 6', '12345682', '갤럭시', 'pending', 1),
    ('갤럭시 탭 S9', '12345683', '갤럭시탭', 'pending', 1),
    ('갤럭시 워치 6', '12345684', '갤럭시워치', 'pending', 1),
    ('갤럭시 버즈 2 프로', '12345685', '갤럭시버즈', 'pending', 1),
    ('갤럭시 북 4', '12345686', '갤럭시북', 'pending', 1),
    ('갤럭시 A54', '12345687', '갤럭시', 'pending', 1);
```

#### Step 2: 서비스 시작

```java
// MainActivity.java
Intent serviceIntent = new Intent(this, TrafficAutomationService.class);
startForegroundService(serviceIntent);
```

#### Step 3: Logcat 확인

```bash
adb logcat | grep "TaskExecutor\|TrafficAutomation"

# 예상 출력:
I/TaskExecutor: Claiming work batch from server...
I/TaskExecutor: Claimed 10 tasks
I/TaskExecutor: Processing task 1/10: traffic_id=1, product=갤럭시 S24 Ultra
I/TrafficAutomation: Executing task: traffic_id=1, product=갤럭시 S24 Ultra
I/JavaScriptInterface: Starting automation: product=갤럭시 S24 Ultra, mid=12345678
D/JavaScriptInterface: reportProgress: status=started
D/JavaScriptInterface: reportProgress: status=mid_clicked
D/JavaScriptInterface: reportProgress: status=dwelling
I/JavaScriptInterface: reportComplete: {"success":true}
I/TaskExecutor: Processing task 2/10: traffic_id=2, product=갤럭시 S24
...
I/TaskExecutor: All tasks completed, waiting for next poll
```

---

## 📈 Part 6: 성능 비교

### 기존 방식 (단일 작업)

**1500대 디바이스, 10,000개 작업**:
```
총 폴링 횟수: 10,000회
서버 요청 수: 10,000 requests (claim-work)
평균 초당 요청: 10,000 / (10,000 * 15초 / 1500대) = 15 req/s
```

### 신규 방식 (배치 10개)

**1500대 디바이스, 10,000개 작업**:
```
총 폴링 횟수: 1,000회 (10,000 / 10)
서버 요청 수: 1,000 requests (claim-work-batch)
평균 초당 요청: 1,000 / (1,000 * 15초 * 10 / 1500대) = 1.5 req/s

→ 서버 부하 10배 감소!
```

---

## 🎯 Part 7: 배치 크기 최적화

### 권장 배치 크기

| 디바이스 수 | 작업 수 | 배치 크기 | 예상 처리 시간 |
|-------------|---------|-----------|----------------|
| 100대 | 1,000개 | 5개 | 20분 |
| 500대 | 5,000개 | 10개 | 15분 |
| 1500대 | 10,000개 | 10개 | 10분 |
| 1500대 | 50,000개 | 20개 | 45분 |

**공식**:
```
처리 시간 = (작업 수 / 디바이스 수 / 배치 크기) × (평균 작업 시간 × 배치 크기 + 폴링 간격)
```

**예시** (1500대, 10,000개 작업, 배치 10개):
```
처리 시간 = (10,000 / 1500 / 10) × (15초 × 10 + 0초)
         = 0.67 × 150초
         = 100초
         ≈ 1.7분
```

---

## 🔄 Part 8: 구현 체크리스트

### 서버 (FastAPI)
- [ ] traffic.py에 `/traffic/claim-work-batch` 추가
- [ ] ClaimWorkBatchRequest, ClaimWorkBatchResponse 모델 추가
- [ ] 배치 처리 로직 구현 (for loop with atomic update)
- [ ] 테스트: `curl -X POST ... -d '{"device_id":"test","batch_size":10}'`

### Android
- [ ] TaskExecutor.java 수정
  - [ ] `taskQueue` 추가
  - [ ] `claimWorkBatch()` 메서드 추가
  - [ ] `processNextTask()` 메서드 추가
  - [ ] `TaskCompletionCallback` 인터페이스 추가
- [ ] TrafficAutomationService.java 수정
  - [ ] `currentCallback` 저장
  - [ ] `onTaskCompleted`에서 callback 호출
- [ ] APK 빌드 및 테스트

### 데이터베이스
- [ ] 테스트 작업 10개 생성
- [ ] 인덱스 확인: `status, priority, created_at`

---

## 💡 추가 기능

### 1. 우선순위 처리

```sql
-- 높은 우선순위 작업 먼저 처리
INSERT INTO traffic_navershopping (product_name, nv_mid, short_keyword, status, priority)
VALUES
    ('VIP 상품', '99999999', 'VIP', 'pending', 10),  -- 우선순위 높음
    ('일반 상품', '88888888', '일반', 'pending', 1);  -- 우선순위 낮음
```

### 2. 그룹 단위 작업 할당

```sql
-- 특정 그룹에만 작업 할당
INSERT INTO traffic_navershopping (product_name, nv_mid, target_group_id, status)
VALUES ('갤럭시 S24', '12345678', 1, 'pending');

-- claim-work-batch API 수정
WHERE status = 'pending'
  AND (target_group_id IS NULL OR target_group_id = :group_id)
```

---

## 📞 요약

**변경 사항**:
- ✅ 한 번에 10개 작업 가져오기
- ✅ 순차적으로 연속 처리
- ✅ 서버 부하 10배 감소
- ✅ 폴링 간격 유지 (5분)
- ✅ 테이블 기반 작업 관리

**다음 단계**:
1. 서버에 `/traffic/claim-work-batch` API 추가
2. Android TaskExecutor 수정
3. Supabase에 테스트 작업 10개 생성
4. 1개 디바이스로 테스트
5. 전체 배포

구현 파일은 `D:/Project/zero/BATCH_TASK_PROCESSING.md`에 저장되어 있습니다!