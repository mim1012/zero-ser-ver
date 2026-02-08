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

public class JavaScriptInterface {
    private static final String TAG = "JavaScriptInterface";

    private final Context context;
    private final WebView webView;
    private final String serverUrl;
    private final String deviceId;
    private final Handler mainHandler;

    private volatile int currentTrafficId = -1;
    private volatile String currentProductName = "";
    private volatile String currentNvMid = "";

    private EventListener eventListener;

    public interface EventListener {
        void onTaskStarted(int trafficId);
        void onTaskProgress(String status, String data);
        void onTaskCompleted(int trafficId, boolean success);
        void onTaskError(int trafficId, String error);
    }

    public JavaScriptInterface(Context context, WebView webView, String serverUrl, String deviceId) {
        this.context = context;
        this.webView = webView;
        this.serverUrl = serverUrl;
        this.deviceId = deviceId;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setEventListener(EventListener listener) {
        this.eventListener = listener;
    }

    public void setCurrentTask(int trafficId, String productName, String nvMid) {
        this.currentTrafficId = trafficId;
        this.currentProductName = productName;
        this.currentNvMid = nvMid;
    }

    @JavascriptInterface
    public void reportProgress(String status, String dataJson) {
        Log.d(TAG, String.format("reportProgress: status=%s, data=%s", status, dataJson));

        try {
            JSONObject data = new JSONObject(dataJson);

            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onTaskProgress(status, dataJson));
            }

            new Thread(() -> {
                try {
                    sendLog(status, data);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send log to server", e);
                }
            }).start();

            if ("completed".equals(status)) {
                handleTaskCompleted(data);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in reportProgress", e);
        }
    }

    @JavascriptInterface
    public void reportError(String errorJson) {
        Log.e(TAG, "reportError: " + errorJson);

        try {
            JSONObject errorData = new JSONObject(errorJson);
            String errorMessage = errorData.optString("error", "Unknown error");

            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onTaskError(currentTrafficId, errorMessage));
            }

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

    @JavascriptInterface
    public void reportComplete(String metadataJson) {
        Log.i(TAG, "reportComplete: " + metadataJson);

        try {
            JSONObject metadata = new JSONObject(metadataJson);

            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onTaskCompleted(currentTrafficId, true));
            }

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

    public void startAutomation(String productName, String mid, String shortKeyword) {
        if (webView == null) {
            Log.e(TAG, "WebView is null");
            return;
        }

        if (shortKeyword == null || shortKeyword.isEmpty()) {
            if (productName != null && !productName.isEmpty()) {
                String firstWord = productName.split(" ")[0];
                shortKeyword = firstWord.substring(0, Math.min(10, firstWord.length()));
            } else {
                shortKeyword = "";
            }
        }

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

        if (eventListener != null) {
            eventListener.onTaskStarted(currentTrafficId);
        }

        mainHandler.post(() -> {
            webView.evaluateJavascript(jsCode, result -> {
                if (result != null) {
                    Log.d(TAG, "JavaScript execution result: " + result);
                }
            });
        });
    }

    private void sendLog(String status, JSONObject data) throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/log";

        JSONObject requestBody = new JSONObject();
        requestBody.put("traffic_id", currentTrafficId);
        requestBody.put("device_id", deviceId);
        requestBody.put("status", status);
        requestBody.put("data", data);

        httpPost(endpoint, requestBody.toString());
    }

    private void sendComplete(JSONObject metadata) throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/complete";

        JSONObject requestBody = new JSONObject();
        requestBody.put("traffic_id", currentTrafficId);
        requestBody.put("device_id", deviceId);
        requestBody.put("metadata", metadata);

        httpPost(endpoint, requestBody.toString());
    }

    private void sendFail(String errorMessage) throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/fail";

        JSONObject requestBody = new JSONObject();
        requestBody.put("traffic_id", currentTrafficId);
        requestBody.put("device_id", deviceId);
        requestBody.put("error_message", errorMessage);

        httpPost(endpoint, requestBody.toString());
    }

    private String httpPost(String urlString, String jsonBody) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

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

    private void handleTaskCompleted(JSONObject metadata) {
        currentTrafficId = -1;
        currentProductName = "";
        currentNvMid = "";
    }

    public int getCurrentTrafficId() {
        return currentTrafficId;
    }

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
