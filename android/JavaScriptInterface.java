package com.zero.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * JavaScriptInterface - JavaScript ↔ Android 브릿지
 *
 * JavaScript에서 호출 가능한 Android 메서드 제공:
 * - reportProgress(status, dataJson) - 진행 상황 보고
 * - reportError(errorJson) - 에러 보고
 * - reportComplete(metadataJson) - 작업 완료 보고
 *
 * Android에서 JavaScript로 호출:
 * - startAutomation(productName, mid, shortKeyword) - 자동화 시작
 */
public class JavaScriptInterface {
    private static final String TAG = "JavaScriptInterface";

    private final Context context;
    private final WebView webView;
    private final String serverUrl;
    private final String deviceId;
    private final Handler mainHandler;

    // 현재 실행 중인 작업 (멀티스레드 접근 가능)
    private volatile int currentTrafficId = -1;
    private volatile String currentProductName = "";
    private volatile String currentNvMid = "";

    // 이벤트 리스너
    private EventListener eventListener;

    /**
     * EventListener - 작업 진행 상태 이벤트 콜백
     */
    public interface EventListener {
        void onTaskStarted(int trafficId);
        void onTaskProgress(String status, String data);
        void onTaskCompleted(int trafficId, boolean success);
        void onTaskError(int trafficId, String error);
    }

    /**
     * JavaScriptInterface 생성자
     *
     * @param context Context
     * @param webView WebView 인스턴스
     * @param serverUrl 서버 URL
     * @param deviceId 디바이스 ID
     */
    public JavaScriptInterface(Context context, WebView webView, String serverUrl, String deviceId) {
        this.context = context;
        this.webView = webView;
        this.serverUrl = serverUrl;
        this.deviceId = deviceId;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * EventListener 설정
     */
    public void setEventListener(EventListener listener) {
        this.eventListener = listener;
    }

    /**
     * 현재 작업 정보 설정
     */
    public void setCurrentTask(int trafficId, String productName, String nvMid) {
        this.currentTrafficId = trafficId;
        this.currentProductName = productName;
        this.currentNvMid = nvMid;
    }

    // ============================================================
    // JavaScript → Android 메서드 (@JavascriptInterface)
    // ============================================================

    /**
     * JavaScript에서 진행 상황 보고
     *
     * @param status 상태 (started, mid_clicked, dwelling, completed, error, log 등)
     * @param dataJson 데이터 JSON 문자열
     */
    @JavascriptInterface
    public void reportProgress(String status, String dataJson) {
        Log.d(TAG, String.format("reportProgress: status=%s, data=%s", status, dataJson));

        try {
            JSONObject data = new JSONObject(dataJson);

            // 이벤트 리스너 호출
            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onTaskProgress(status, dataJson));
            }

            // 서버에 로그 전송 (백그라운드)
            new Thread(() -> {
                try {
                    sendLog(status, data);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send log to server", e);
                }
            }).start();

            // 특정 상태에 따른 처리
            if ("completed".equals(status)) {
                handleTaskCompleted(data);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in reportProgress", e);
        }
    }

    /**
     * JavaScript에서 에러 보고
     *
     * @param errorJson 에러 정보 JSON 문자열
     */
    @JavascriptInterface
    public void reportError(String errorJson) {
        Log.e(TAG, "reportError: " + errorJson);

        try {
            JSONObject errorData = new JSONObject(errorJson);
            String errorMessage = errorData.optString("error", "Unknown error");

            // 이벤트 리스너 호출
            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onTaskError(currentTrafficId, errorMessage));
            }

            // 서버에 실패 보고 (백그라운드)
            new Thread(() -> {
                try {
                    sendFail(errorMessage);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send fail to server", e);
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Error in reportError", e);
        }
    }

    /**
     * JavaScript에서 작업 완료 보고
     *
     * @param metadataJson 메타데이터 JSON 문자열
     */
    @JavascriptInterface
    public void reportComplete(String metadataJson) {
        Log.i(TAG, "reportComplete: " + metadataJson);

        try {
            JSONObject metadata = new JSONObject(metadataJson);

            // 이벤트 리스너 호출
            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onTaskCompleted(currentTrafficId, true));
            }

            // 서버에 완료 보고 (백그라운드)
            new Thread(() -> {
                try {
                    sendComplete(metadata);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send complete to server", e);
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Error in reportComplete", e);
        }
    }

    // ============================================================
    // Android → JavaScript 메서드
    // ============================================================

    /**
     * JavaScript 자동화 시작
     *
     * @param productName 상품명
     * @param mid 네이버 상품 ID
     * @param shortKeyword 짧은 키워드 (optional)
     */
    public void startAutomation(String productName, String mid, String shortKeyword) {
        if (webView == null) {
            Log.e(TAG, "WebView is null");
            return;
        }

        // shortKeyword가 비어있으면 상품명 첫 단어 사용
        if (shortKeyword == null || shortKeyword.isEmpty()) {
            if (productName != null && !productName.isEmpty()) {
                String firstWord = productName.split(" ")[0];
                shortKeyword = firstWord.substring(0, Math.min(10, firstWord.length()));
            } else {
                shortKeyword = "";
            }
        }

        // JS 인젝션 방지를 위한 이스케이프
        String escapedProductName = escapeJS(productName);
        String escapedShortKeyword = escapeJS(shortKeyword);

        String jsCode = String.format(
            "if (window.NaverShoppingAutomation) { " +
            "  window.NaverShoppingAutomation.run('%s', '%s', '%s'); " +
            "} else { " +
            "  console.error('NaverShoppingAutomation not loaded'); " +
            "}",
            escapedProductName,
            mid,
            escapedShortKeyword
        );

        Log.i(TAG, String.format("Starting automation: product=%s, mid=%s, keyword=%s",
            productName, mid, shortKeyword));

        // 이벤트 리스너 호출
        if (eventListener != null) {
            eventListener.onTaskStarted(currentTrafficId);
        }

        // WebView에서 JavaScript 실행 (메인 스레드)
        mainHandler.post(() -> {
            webView.evaluateJavascript(jsCode, result -> {
                if (result != null) {
                    Log.d(TAG, "JavaScript execution result: " + result);
                }
            });
        });
    }

    // ============================================================
    // 서버 API 통신 메서드
    // ============================================================

    /**
     * 서버에 로그 전송
     */
    private void sendLog(String status, JSONObject data) throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/log";

        JSONObject requestBody = new JSONObject();
        requestBody.put("traffic_id", currentTrafficId);
        requestBody.put("device_id", deviceId);
        requestBody.put("status", status);
        requestBody.put("data", data);

        httpPost(endpoint, requestBody.toString());

        Log.d(TAG, "Log sent to server: " + status);
    }

    /**
     * 서버에 완료 보고
     */
    private void sendComplete(JSONObject metadata) throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/complete";

        JSONObject requestBody = new JSONObject();
        requestBody.put("traffic_id", currentTrafficId);
        requestBody.put("device_id", deviceId);
        requestBody.put("metadata", metadata);

        httpPost(endpoint, requestBody.toString());

        Log.i(TAG, "Task completion sent to server");
    }

    /**
     * 서버에 실패 보고
     */
    private void sendFail(String errorMessage) throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/fail";

        JSONObject requestBody = new JSONObject();
        requestBody.put("traffic_id", currentTrafficId);
        requestBody.put("device_id", deviceId);
        requestBody.put("error_message", errorMessage);

        httpPost(endpoint, requestBody.toString());

        Log.i(TAG, "Task failure sent to server");
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
            os.write(jsonBody.getBytes("UTF-8"));
            os.close();

            int responseCode = conn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())
                );

                StringBuilder response = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                reader.close();
                return response.toString();

            } else {
                throw new Exception("HTTP POST failed: " + responseCode);
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * 작업 완료 처리
     */
    private void handleTaskCompleted(JSONObject metadata) {
        Log.i(TAG, String.format("Task completed: traffic_id=%d, product=%s, mid=%s",
            currentTrafficId, currentProductName, currentNvMid));

        // 작업 정보 초기화
        currentTrafficId = -1;
        currentProductName = "";
        currentNvMid = "";
    }

    /**
     * 현재 작업 ID 가져오기
     */
    public int getCurrentTrafficId() {
        return currentTrafficId;
    }

    /**
     * JS 인젝션 방지를 위한 문자열 이스케이프
     */
    private static String escapeJS(String input) {
        if (input == null) return "";
        return input
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("</", "<\\/");
    }
}
