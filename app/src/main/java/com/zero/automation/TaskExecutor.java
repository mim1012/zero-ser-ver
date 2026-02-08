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

public class TaskExecutor {
    private static final String TAG = "TaskExecutor";

    private static final long POLL_INTERVAL_MS = 5 * 60 * 1000; // 5분
    private static final String PREF_NAME = "TaskExecutor";
    private static final String KEY_CACHED_SCRIPT = "cached_script";
    private static final String KEY_CACHED_SCRIPT_VERSION = "cached_script_version";
    private static final String KEY_CACHED_SCRIPT_CHECKSUM = "cached_script_checksum";

    private final Context context;
    private final Handler mainHandler;
    private final Handler pollingHandler;
    private Runnable pollingRunnable;

    private final String serverUrl;
    private final String deviceId;

    private String cachedScript;
    private String cachedScriptVersion;
    private String cachedScriptChecksum;

    private TaskExecutionListener taskExecutionListener;

    private volatile boolean isPolling = false;
    private volatile int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    public interface TaskExecutionListener {
        void onExecuteTask(int trafficId, String productName, String nvMid, String shortKeyword, String script);
        void onNoWork();
        void onError(String error);
    }

    public TaskExecutor(Context context, String serverUrl, String deviceId) {
        this.context = context;
        this.serverUrl = serverUrl;
        this.deviceId = deviceId;

        this.mainHandler = new Handler(Looper.getMainLooper());
        this.pollingHandler = new Handler(Looper.getMainLooper());

        loadCachedScript();
    }

    public void setTaskExecutionListener(TaskExecutionListener listener) {
        this.taskExecutionListener = listener;
    }

    public void startPolling() {
        if (isPolling) {
            return;
        }

        isPolling = true;
        consecutiveErrors = 0;

        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPolling) return;

                new Thread(() -> {
                    try {
                        claimAndExecuteWork();
                    } catch (Exception e) {
                        Log.e(TAG, "Error in polling loop", e);
                        handleError(e.getMessage());
                    } finally {
                        if (isPolling) {
                            pollingHandler.postDelayed(this, POLL_INTERVAL_MS);
                        }
                    }
                }).start();
            }
        };

        pollingHandler.post(pollingRunnable);
        Log.i(TAG, "Task polling started");
    }

    public void stopPolling() {
        isPolling = false;

        if (pollingRunnable != null) {
            pollingHandler.removeCallbacks(pollingRunnable);
        }

        Log.i(TAG, "Task polling stopped");
    }

    private void claimAndExecuteWork() {
        try {
            ensureScriptLoaded();

            JSONObject work = claimWork();

            if (work == null) {
                consecutiveErrors = 0;
                if (taskExecutionListener != null) {
                    mainHandler.post(() -> taskExecutionListener.onNoWork());
                }
                return;
            }

            int trafficId = work.getInt("traffic_id");
            String productName = work.getString("product_name");
            String nvMid = work.getString("nv_mid");
            String shortKeyword = work.optString("short_keyword", "");

            consecutiveErrors = 0;

            if (taskExecutionListener != null) {
                mainHandler.post(() -> {
                    taskExecutionListener.onExecuteTask(
                        trafficId, productName, nvMid, shortKeyword, cachedScript
                    );
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error claiming work", e);
            handleError(e.getMessage());
        }
    }

    private JSONObject claimWork() throws Exception {
        String endpoint = serverUrl + "/zero/api/v1/traffic/claim-work";

        JSONObject requestBody = new JSONObject();
        requestBody.put("device_id", deviceId);

        String response = httpPost(endpoint, requestBody.toString());

        if (response == null || response.isEmpty()) {
            return null;
        }

        JSONObject json = new JSONObject(response);

        if (!json.has("traffic_id")) {
            return null;
        }

        return json;
    }

    private void ensureScriptLoaded() throws Exception {
        String versionEndpoint = serverUrl + "/zero/api/v1/automation/version";
        String versionResponse = httpGet(versionEndpoint);

        JSONObject versionInfo = new JSONObject(versionResponse);
        String latestVersion = versionInfo.getString("latest_version");

        if (cachedScriptVersion != null && cachedScriptVersion.equals(latestVersion)) {
            return;
        }

        String scriptEndpoint = serverUrl + "/zero/api/v1/automation/script?version=" + latestVersion;
        String scriptResponse = httpGet(scriptEndpoint);

        JSONObject scriptData = new JSONObject(scriptResponse);

        cachedScript = scriptData.getString("script");
        cachedScriptChecksum = scriptData.getString("checksum");
        cachedScriptVersion = scriptData.getString("version");

        saveCachedScript();
    }

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
                return null;
            } else {
                throw new Exception("HTTP POST failed: " + responseCode);
            }
        } finally {
            conn.disconnect();
        }
    }

    private void handleError(String errorMessage) {
        consecutiveErrors++;

        if (taskExecutionListener != null) {
            mainHandler.post(() -> taskExecutionListener.onError(errorMessage));
        }

        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "Too many consecutive errors, stopping polling");
            stopPolling();
        }
    }

    private void loadCachedScript() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        cachedScript = prefs.getString(KEY_CACHED_SCRIPT, null);
        cachedScriptVersion = prefs.getString(KEY_CACHED_SCRIPT_VERSION, null);
        cachedScriptChecksum = prefs.getString(KEY_CACHED_SCRIPT_CHECKSUM, null);
    }

    private void saveCachedScript() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_CACHED_SCRIPT, cachedScript)
            .putString(KEY_CACHED_SCRIPT_VERSION, cachedScriptVersion)
            .putString(KEY_CACHED_SCRIPT_CHECKSUM, cachedScriptChecksum)
            .apply();
    }

    public String getCachedScriptVersion() {
        return cachedScriptVersion;
    }

    public boolean isPolling() {
        return isPolling;
    }
}
