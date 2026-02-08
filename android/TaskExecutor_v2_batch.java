package com.zero.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * TaskExecutor v2 - 배치 작업 처리 기능 추가
 *
 * 주요 변경사항:
 * 1. 배치로 작업 가져오기 (claim-work-batch API)
 * 2. Queue 기반 순차 처리
 * 3. Callback을 통한 연속 실행
 * 4. 작업 간 2초 딜레이
 */
public class TaskExecutor {
    private static final String TAG = "TaskExecutor";

    // 설정
    private static final long POLL_INTERVAL_MS = 5 * 60 * 1000; // 5분
    private static final int BATCH_SIZE = 10; // 한 번에 가져올 작업 개수
    private static final long TASK_DELAY_MS = 2000; // 작업 간 딜레이 2초

    private static final String PREF_NAME = "TaskExecutor";
    private static final String KEY_CACHED_SCRIPT = "cached_script";
    private static final String KEY_CACHED_SCRIPT_VERSION = "cached_script_version";
    private static final String KEY_CACHED_SCRIPT_CHECKSUM = "cached_script_checksum";

    // Context 및 Handler
    private final Context context;
    private final Handler mainHandler;
    private final Handler pollingHandler;
    private Runnable pollingRunnable;

    // 서버 설정
    private final String serverUrl;
    private final String deviceId;

    // 캐시된 스크립트
    private String cachedScript;
    private String cachedScriptVersion;
    private String cachedScriptChecksum;

    // Listener
    private TaskExecutionListener taskExecutionListener;

    // 배치 처리 관련 필드 (스레드 안전)
    private final Queue<JSONObject> taskQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isProcessingTasks = false;

    // 상태
    private volatile boolean isPolling = false;
    private volatile int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    /**
     * TaskCompletionCallback - 작업 완료 콜백
     */
    public interface TaskCompletionCallback {
        void onComplete();
    }

    /**
     * TaskExecutionListener - 작업 실행 콜백 인터페이스
     */
    public interface TaskExecutionListener {
        /**
         * 작업 실행 요청 (배치 모드)
         *
         * @param trafficId 트래픽 ID
         * @param productName 상품명
         * @param nvMid 네이버 상품 ID
         * @param shortKeyword 짧은 키워드 (optional)
         * @param script JavaScript 스크립트
         * @param callback 작업 완료 시 호출될 콜백
         */
        void onExecuteTask(int trafficId, String productName, String nvMid,
                          String shortKeyword, String script, TaskCompletionCallback callback);

        /**
         * 작업 없음 (대기 중)
         */
        void onNoWork();

        /**
         * 에러 발생
         *
         * @param error 에러 메시지
         */
        void onError(String error);
    }

    /**
     * TaskExecutor 생성자
     */
    public TaskExecutor(Context context, String serverUrl, String deviceId) {
        this.context = context;
        this.serverUrl = serverUrl;
        this.deviceId = deviceId;

        this.mainHandler = new Handler(Looper.getMainLooper());
        this.pollingHandler = new Handler(Looper.getMainLooper());

        // 캐시된 스크립트 로드
        loadCachedScript();
    }

    /**
     * TaskExecutionListener 설정
     */
    public void setTaskExecutionListener(TaskExecutionListener listener) {
        this.taskExecutionListener = listener;
    }

    /**
     * 폴링 시작
     */
    public void startPolling() {
        if (isPolling) {
            Log.w(TAG, "Polling already started");
            return;
        }

        Log.i(TAG, "Starting polling (interval: " + POLL_INTERVAL_MS + "ms, batch_size: " + BATCH_SIZE + ")");
        isPolling = true;
        consecutiveErrors = 0;

        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                claimAndExecuteWork();
                pollingHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };

        // 즉시 첫 번째 작업 시작
        pollingRunnable.run();
    }

    /**
     * 폴링 중지
     */
    public void stopPolling() {
        if (!isPolling) {
            return;
        }

        Log.i(TAG, "Stopping polling");
        isPolling = false;

        if (pollingRunnable != null) {
            pollingHandler.removeCallbacks(pollingRunnable);
            pollingRunnable = null;
        }
    }

    /**
     * 배치 작업 가져오기 및 실행
     */
    private void claimAndExecuteWork() {
        new Thread(() -> {
            try {
                // 1. 스크립트 최신 버전 확인 및 다운로드
                ensureScriptLoaded();

                // 2. 배치로 작업 가져오기
                JSONArray tasks = claimWorkBatch(BATCH_SIZE);

                if (tasks == null || tasks.length() == 0) {
                    Log.i(TAG, "No work available");
                    mainHandler.post(() -> {
                        if (taskExecutionListener != null) {
                            taskExecutionListener.onNoWork();
                        }
                    });
                    consecutiveErrors = 0;
                    return;
                }

                Log.i(TAG, "Claimed " + tasks.length() + " tasks");

                // 3. 작업 큐에 추가
                for (int i = 0; i < tasks.length(); i++) {
                    taskQueue.offer(tasks.getJSONObject(i));
                }

                // 4. 작업 처리 시작 (아직 처리 중이 아닌 경우만)
                if (!isProcessingTasks) {
                    mainHandler.post(() -> processNextTask());
                }

                consecutiveErrors = 0;

            } catch (Exception e) {
                Log.e(TAG, "Error claiming work: " + e.getMessage(), e);
                handleError("Failed to claim work: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 배치로 작업 가져오기 (서버 API 호출)
     */
    private JSONArray claimWorkBatch(int batchSize) throws Exception {
        URL url = new URL(serverUrl + "/zero/api/v1/traffic/claim-work-batch");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            // Request Body
            JSONObject requestBody = new JSONObject();
            requestBody.put("device_id", deviceId);
            requestBody.put("batch_size", batchSize);

            OutputStream os = conn.getOutputStream();
            os.write(requestBody.toString().getBytes("UTF-8"));
            os.close();

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();

                JSONObject responseJson = new JSONObject(response.toString());
                return responseJson.getJSONArray("tasks");

            } else if (responseCode == 404) {
                // 작업 없음
                return new JSONArray();

            } else {
                throw new Exception("Server returned error code: " + responseCode);
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * 큐에서 다음 작업 꺼내서 처리
     */
    private void processNextTask() {
        if (taskQueue.isEmpty()) {
            Log.i(TAG, "All tasks completed. Queue is empty.");
            isProcessingTasks = false;
            return;
        }

        isProcessingTasks = true;

        JSONObject task = taskQueue.poll();

        try {
            int trafficId = task.getInt("traffic_id");
            String productName = task.getString("product_name");
            String nvMid = task.getString("nv_mid");
            String shortKeyword = task.optString("short_keyword", "");

            Log.i(TAG, "Processing task [" + trafficId + "]: " + productName);

            // Callback 생성 (작업 완료 시 다음 작업 처리)
            TaskCompletionCallback callback = new TaskCompletionCallback() {
                @Override
                public void onComplete() {
                    Log.i(TAG, "Task [" + trafficId + "] completed. Processing next task after delay...");

                    // 2초 후 다음 작업 처리
                    mainHandler.postDelayed(() -> processNextTask(), TASK_DELAY_MS);
                }
            };

            // Listener에게 작업 전달
            if (taskExecutionListener != null) {
                taskExecutionListener.onExecuteTask(trafficId, productName, nvMid,
                                                   shortKeyword, cachedScript, callback);
            } else {
                Log.w(TAG, "TaskExecutionListener is null. Skipping task.");
                callback.onComplete(); // 다음 작업으로 진행
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing task: " + e.getMessage(), e);
            // 에러 발생 시에도 다음 작업 계속 처리
            mainHandler.postDelayed(() -> processNextTask(), TASK_DELAY_MS);
        }
    }

    /**
     * 스크립트 로드 확인 및 다운로드
     */
    private void ensureScriptLoaded() throws Exception {
        // 1. 서버에서 최신 버전 정보 가져오기
        JSONObject versionInfo = getScriptVersion();
        String latestVersion = versionInfo.getString("latest_version");

        // 2. 캐시된 버전과 비교
        if (cachedScript != null && latestVersion.equals(cachedScriptVersion)) {
            Log.d(TAG, "Using cached script version: " + cachedScriptVersion);
            return;
        }

        // 3. 새로운 스크립트 다운로드
        Log.i(TAG, "Downloading script version: " + latestVersion);
        JSONObject scriptData = downloadScript(latestVersion);

        cachedScript = scriptData.getString("script");
        cachedScriptVersion = scriptData.getString("version");
        cachedScriptChecksum = scriptData.getString("checksum");

        // 4. SharedPreferences에 캐시
        saveScriptToCache();

        Log.i(TAG, "Script downloaded and cached. Version: " + cachedScriptVersion +
                   ", Size: " + cachedScript.length() + " bytes");
    }

    /**
     * 서버에서 스크립트 버전 정보 가져오기
     */
    private JSONObject getScriptVersion() throws Exception {
        URL url = new URL(serverUrl + "/zero/api/v1/automation/version");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            return new JSONObject(response.toString());

        } finally {
            conn.disconnect();
        }
    }

    /**
     * 서버에서 스크립트 다운로드
     */
    private JSONObject downloadScript(String version) throws Exception {
        URL url = new URL(serverUrl + "/zero/api/v1/automation/script?version=" + version);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            return new JSONObject(response.toString());

        } finally {
            conn.disconnect();
        }
    }

    /**
     * SharedPreferences에서 캐시된 스크립트 로드
     */
    private void loadCachedScript() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        cachedScript = prefs.getString(KEY_CACHED_SCRIPT, null);
        cachedScriptVersion = prefs.getString(KEY_CACHED_SCRIPT_VERSION, null);
        cachedScriptChecksum = prefs.getString(KEY_CACHED_SCRIPT_CHECKSUM, null);

        if (cachedScript != null) {
            Log.i(TAG, "Loaded cached script. Version: " + cachedScriptVersion);
        }
    }

    /**
     * SharedPreferences에 스크립트 저장
     */
    private void saveScriptToCache() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_CACHED_SCRIPT, cachedScript);
        editor.putString(KEY_CACHED_SCRIPT_VERSION, cachedScriptVersion);
        editor.putString(KEY_CACHED_SCRIPT_CHECKSUM, cachedScriptChecksum);
        editor.apply();
    }

    /**
     * 에러 처리
     */
    private void handleError(String error) {
        consecutiveErrors++;

        Log.e(TAG, "Error count: " + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS);

        mainHandler.post(() -> {
            if (taskExecutionListener != null) {
                taskExecutionListener.onError(error);
            }
        });

        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "Max consecutive errors reached. Stopping polling.");
            stopPolling();
        }
    }

    /**
     * 큐 상태 조회
     */
    public int getQueueSize() {
        return taskQueue.size();
    }

    /**
     * 작업 처리 중인지 확인
     */
    public boolean isProcessing() {
        return isProcessingTasks;
    }
}
