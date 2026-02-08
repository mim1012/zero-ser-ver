package com.zero.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TaskExecutor - 네이버 쇼핑 자동화 작업 오케스트레이터
 *
 * 주요 기능:
 * 1. 5분마다 서버에서 작업 폴링 (claim-work API)
 * 2. JavaScript 스크립트 다운로드 및 버전 관리
 * 3. WebView에 작업 할당 (TaskExecutionListener)
 * 4. 에러 처리 및 재시도 로직
 */
public class TaskExecutor {
    private static final String TAG = "TaskExecutor";

    // 설정
    private static final long POLL_INTERVAL_MS = 5 * 60 * 1000; // 5분
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

    // 상태
    private volatile boolean isPolling = false;
    private volatile int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    /**
     * TaskExecutionListener - 작업 실행 콜백 인터페이스
     */
    public interface TaskExecutionListener {
        /**
         * 작업 실행 요청
         *
         * @param trafficId 트래픽 ID
         * @param productName 상품명
         * @param nvMid 네이버 상품 ID
         * @param shortKeyword 짧은 키워드 (optional)
         * @param script JavaScript 스크립트
         */
        void onExecuteTask(int trafficId, String productName, String nvMid, String shortKeyword, String script);

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
     *
     * @param context Context
     * @param serverUrl 서버 URL (예: https://zero-server.railway.app)
     * @param deviceId 디바이스 ID
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
     * 작업 폴링 시작
     */
    public void startPolling() {
        if (isPolling) {
            Log.w(TAG, "Polling already started");
            return;
        }

        isPolling = true;
        consecutiveErrors = 0;

        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPolling) return;

                // 백그라운드 스레드에서 작업 실행
                new Thread(() -> {
                    try {
                        claimAndExecuteWork();
                    } catch (Exception e) {
                        Log.e(TAG, "Error in polling loop", e);
                        handleError(e.getMessage());
                    } finally {
                        // 다음 폴링 스케줄
                        if (isPolling) {
                            pollingHandler.postDelayed(this, POLL_INTERVAL_MS);
                        }
                    }
                }).start();
            }
        };

        // 즉시 첫 번째 폴링 시작
        pollingHandler.post(pollingRunnable);

        Log.i(TAG, "Task polling started (interval: " + (POLL_INTERVAL_MS / 1000) + " seconds)");
    }

    /**
     * 작업 폴링 중지
     */
    public void stopPolling() {
        isPolling = false;

        if (pollingRunnable != null) {
            pollingHandler.removeCallbacks(pollingRunnable);
        }

        Log.i(TAG, "Task polling stopped");
    }

    /**
     * 작업 클레임 및 실행
     */
    private void claimAndExecuteWork() {
        try {
            Log.d(TAG, "Claiming work from server...");

            // 1. 스크립트 최신 버전 확인 및 다운로드
            ensureScriptLoaded();

            // 2. 서버에서 작업 클레임
            JSONObject work = claimWork();

            if (work == null) {
                Log.d(TAG, "No work available");
                consecutiveErrors = 0;

                if (taskExecutionListener != null) {
                    mainHandler.post(() -> taskExecutionListener.onNoWork());
                }
                return;
            }

            // 3. 작업 정보 추출
            int trafficId = work.getInt("traffic_id");
            String productName = work.getString("product_name");
            String nvMid = work.getString("nv_mid");
            String shortKeyword = work.optString("short_keyword", "");

            Log.i(TAG, String.format("Work claimed: traffic_id=%d, product=%s, mid=%s",
                trafficId, productName, nvMid));

            consecutiveErrors = 0;

            // 4. WebView에서 실행 (메인 스레드)
            if (taskExecutionListener != null) {
                mainHandler.post(() -> {
                    taskExecutionListener.onExecuteTask(
                        trafficId,
                        productName,
                        nvMid,
                        shortKeyword,
                        cachedScript
                    );
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error claiming work", e);
            handleError(e.getMessage());
        }
    }

    /**
     * 서버에서 작업 클레임
     *
     * @return 작업 정보 JSON (작업 없으면 null)
     */
    private JSONObject claimWork() throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/claim-work";

        JSONObject requestBody = new JSONObject();
        requestBody.put("device_id", deviceId);

        String response = httpPost(endpoint, requestBody.toString());

        if (response == null || response.isEmpty()) {
            return null;
        }

        JSONObject json = new JSONObject(response);

        // 작업 없음
        if (!json.has("traffic_id")) {
            return null;
        }

        return json;
    }

    /**
     * 스크립트 다운로드 및 버전 관리
     */
    private void ensureScriptLoaded() throws Exception {
        // 1. 서버에서 최신 버전 정보 가져오기
        String versionEndpoint = serverUrl + "/zero/api/v1/automation/version";
        String versionResponse = httpGet(versionEndpoint);

        JSONObject versionInfo = new JSONObject(versionResponse);
        String latestVersion = versionInfo.getString("latest_version");

        // 2. 캐시된 버전과 비교
        if (cachedScriptVersion != null && cachedScriptVersion.equals(latestVersion)) {
            Log.d(TAG, "Script already up to date: " + latestVersion);
            return;
        }

        // 3. 새 스크립트 다운로드
        Log.i(TAG, "Downloading new script version: " + latestVersion);

        String scriptEndpoint = serverUrl + "/zero/api/v1/automation/script?version=" + latestVersion;
        String scriptResponse = httpGet(scriptEndpoint);

        JSONObject scriptData = new JSONObject(scriptResponse);

        String script = scriptData.getString("script");
        String checksum = scriptData.getString("checksum");
        String version = scriptData.getString("version");

        // 4. 캐시 저장
        cachedScript = script;
        cachedScriptVersion = version;
        cachedScriptChecksum = checksum;

        saveCachedScript();

        Log.i(TAG, String.format("Script downloaded: version=%s, size=%d bytes, checksum=%s",
            version, script.length(), checksum));
    }

    /**
     * HTTP GET 요청
     */
    private String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())
                );

                try {
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    return response.toString();
                } finally {
                    reader.close();
                }

            } else {
                throw new Exception("HTTP GET failed: " + responseCode);
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * HTTP POST 요청
     */
    private String httpPost(String urlString, String jsonBody) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            // Request body 전송
            OutputStream os = conn.getOutputStream();
            try {
                os.write(jsonBody.getBytes("UTF-8"));
                os.flush();
            } finally {
                os.close();
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())
                );

                try {
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    return response.toString();
                } finally {
                    reader.close();
                }

            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                // 404: 작업 없음
                return null;

            } else {
                throw new Exception("HTTP POST failed: " + responseCode);
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * 에러 처리
     */
    private void handleError(String errorMessage) {
        consecutiveErrors++;

        Log.e(TAG, String.format("Error occurred (consecutive: %d/%d): %s",
            consecutiveErrors, MAX_CONSECUTIVE_ERRORS, errorMessage));

        if (taskExecutionListener != null) {
            mainHandler.post(() -> taskExecutionListener.onError(errorMessage));
        }

        // 연속 에러가 임계값을 초과하면 폴링 중지
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "Too many consecutive errors, stopping polling");
            stopPolling();
        }
    }

    /**
     * 캐시된 스크립트 로드
     */
    private void loadCachedScript() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        cachedScript = prefs.getString(KEY_CACHED_SCRIPT, null);
        cachedScriptVersion = prefs.getString(KEY_CACHED_SCRIPT_VERSION, null);
        cachedScriptChecksum = prefs.getString(KEY_CACHED_SCRIPT_CHECKSUM, null);

        if (cachedScript != null) {
            Log.i(TAG, String.format("Cached script loaded: version=%s, size=%d bytes",
                cachedScriptVersion, cachedScript.length()));
        }
    }

    /**
     * 캐시된 스크립트 저장
     */
    private void saveCachedScript() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putString(KEY_CACHED_SCRIPT, cachedScript);
        editor.putString(KEY_CACHED_SCRIPT_VERSION, cachedScriptVersion);
        editor.putString(KEY_CACHED_SCRIPT_CHECKSUM, cachedScriptChecksum);

        editor.apply();

        Log.d(TAG, "Script cached to SharedPreferences");
    }

    /**
     * 현재 캐시된 스크립트 버전 가져오기
     */
    public String getCachedScriptVersion() {
        return cachedScriptVersion;
    }

    /**
     * 폴링 상태 확인
     */
    public boolean isPolling() {
        return isPolling;
    }
}
