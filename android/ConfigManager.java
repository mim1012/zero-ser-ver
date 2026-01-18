package com.sec.android.app.sbrowser.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Option C: 서버에서 JSON 설정을 다운로드하여 관리하는 클래스
 * 
 * 기능:
 * - 서버로부터 헤더, User-Agent, WebView 설정을 JSON으로 다운로드
 * - 로컬 캐시에 저장하여 오프라인 시에도 사용 가능
 * - 1시간마다 자동 갱신
 */
public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREFS_NAME = "ZeroConfig";
    private static final String KEY_CONFIG_JSON = "config_json";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final long UPDATE_INTERVAL_MS = 60 * 60 * 1000; // 1시간
    
    private static ConfigManager instance;
    private Context context;
    private SharedPreferences prefs;
    private OkHttpClient httpClient;
    private String serverUrl;
    
    // 캐시된 설정
    private JSONObject cachedConfig;
    private long lastUpdateTime;
    
    private ConfigManager(Context context, String serverUrl) {
        this.context = context.getApplicationContext();
        this.serverUrl = serverUrl;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        
        // 로컬 캐시에서 설정 로드
        loadFromCache();
    }
    
    public static synchronized ConfigManager getInstance(Context context, String serverUrl) {
        if (instance == null) {
            instance = new ConfigManager(context, serverUrl);
        }
        return instance;
    }
    
    /**
     * 로컬 캐시에서 설정 로드
     */
    private void loadFromCache() {
        String jsonStr = prefs.getString(KEY_CONFIG_JSON, null);
        lastUpdateTime = prefs.getLong(KEY_LAST_UPDATE, 0);
        
        if (jsonStr != null) {
            try {
                cachedConfig = new JSONObject(jsonStr);
                Log.i(TAG, "Loaded config from cache");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse cached config", e);
                cachedConfig = null;
            }
        }
    }
    
    /**
     * 서버로부터 최신 설정 다운로드
     * 
     * @param deviceModel 기기 모델 (예: "SM-G998N")
     * @param chromeVersion Chrome 버전 (예: "143")
     * @return 성공 여부
     */
    public boolean updateFromServer(String deviceModel, String chromeVersion) {
        try {
            String url = String.format("%s/zero/api/v1/config/full?device_model=%s&chrome_version=%s",
                    serverUrl, deviceModel, chromeVersion);
            
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();
            
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.e(TAG, "Server returned error: " + response.code());
                return false;
            }
            
            String jsonStr = response.body().string();
            JSONObject newConfig = new JSONObject(jsonStr);
            
            // 캐시 업데이트
            cachedConfig = newConfig;
            lastUpdateTime = System.currentTimeMillis();
            
            // SharedPreferences에 저장
            prefs.edit()
                    .putString(KEY_CONFIG_JSON, jsonStr)
                    .putLong(KEY_LAST_UPDATE, lastUpdateTime)
                    .apply();
            
            Log.i(TAG, "Config updated from server successfully");
            return true;
            
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to update config from server", e);
            return false;
        }
    }
    
    /**
     * 필요 시 자동 업데이트 (1시간 경과 시)
     */
    public void autoUpdateIfNeeded(String deviceModel, String chromeVersion) {
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime > UPDATE_INTERVAL_MS) {
            Log.i(TAG, "Auto-updating config (last update was " + (now - lastUpdateTime) / 1000 + " seconds ago)");
            updateFromServer(deviceModel, chromeVersion);
        }
    }
    
    /**
     * User-Agent 가져오기
     */
    public String getUserAgent() {
        if (cachedConfig == null) {
            return getDefaultUserAgent();
        }
        
        try {
            return cachedConfig.optString("user_agent", getDefaultUserAgent());
        } catch (Exception e) {
            Log.e(TAG, "Failed to get user_agent", e);
            return getDefaultUserAgent();
        }
    }
    
    /**
     * 커스텀 헤더 Map 가져오기
     */
    public Map<String, String> getCustomHeaders() {
        Map<String, String> headers = new HashMap<>();
        
        if (cachedConfig == null) {
            return headers;
        }
        
        try {
            JSONObject headersObj = cachedConfig.optJSONObject("headers");
            if (headersObj != null) {
                Iterator<String> keys = headersObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = headersObj.getString(key);
                    headers.put(key, value);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse headers", e);
        }
        
        return headers;
    }
    
    /**
     * WebView 설정 가져오기
     */
    public JSONObject getWebViewSettings() {
        if (cachedConfig == null) {
            return new JSONObject();
        }
        
        return cachedConfig.optJSONObject("webview_settings");
    }
    
    /**
     * 기본 User-Agent (fallback)
     */
    private String getDefaultUserAgent() {
        return String.format(
                "Mozilla/5.0 (Linux; Android %s; %s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36",
                Build.VERSION.RELEASE,
                Build.MODEL
        );
    }
    
    /**
     * 전체 설정 JSON 가져오기 (디버깅용)
     */
    public JSONObject getFullConfig() {
        return cachedConfig;
    }
    
    /**
     * 마지막 업데이트 시간 가져오기
     */
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
}
