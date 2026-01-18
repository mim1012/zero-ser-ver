package com.sec.android.app.sbrowser.pattern.common;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.sec.android.app.sbrowser.ActivityMCloud;
import com.sec.android.app.sbrowser.pattern.action.UpdateAction;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * WebView 업데이트 패턴 메시지 (Option C 버전)
 * 서버에서 기기별 WebView 설정을 동적으로 받아옵니다.
 */
public class WebviewUpdatePatternMessage extends UpdatePatternMessage {

    private static final String TAG = WebviewUpdatePatternMessage.class.getSimpleName();
    
    // 서버 URL (실제 배포된 서버 주소로 변경 필요)
    private static final String SERVER_URL = "https://your-server.railway.app";
    
    public WebviewUpdatePatternMessage(Context context) {
        super(context);

        _killAppBeforeUpdate = false;
        UpdateAction updateAction = new UpdateAction();

        try {
            // 서버에서 WebView 설정 가져오기
            JSONObject webviewConfig = fetchWebViewConfigFromServer();
            
            if (webviewConfig != null) {
                // 서버에서 받은 설정 적용
                int appType = webviewConfig.optInt("app_type", 5);
                String packageName = webviewConfig.optString("package_name", ActivityMCloud.PACKAGE_NAME_SYSTEM_WEBVIEW);
                String description = webviewConfig.optString("description", "시스템 웹뷰");
                
                updateAction.setAppType(appType);
                updateAction.setPackageName(packageName);
                setLogHeader(description);
                
                Log.i(TAG, "WebView config loaded from server: " + description + " (appType=" + appType + ")");
            } else {
                // 서버 연결 실패 시 기존 로직 사용 (폴백)
                useFallbackLogic(updateAction);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading WebView config from server, using fallback", e);
            // 예외 발생 시 기존 로직 사용
            useFallbackLogic(updateAction);
        }

        setUpdateAction(updateAction);
    }
    
    /**
     * 서버에서 WebView 설정을 가져옵니다.
     */
    private JSONObject fetchWebViewConfigFromServer() {
        try {
            String deviceModel = Build.MODEL;
            String url = SERVER_URL + "/config/webview?model=" + deviceModel;
            
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();
            
            Response response = client.newCall(request).execute();
            
            if (response.isSuccessful() && response.body() != null) {
                String jsonString = response.body().string();
                return new JSONObject(jsonString);
            } else {
                Log.w(TAG, "Server returned error: " + response.code());
                return null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch WebView config from server", e);
            return null;
        }
    }
    
    /**
     * 서버 연결 실패 시 사용하는 기존 로직 (폴백)
     */
    private void useFallbackLogic(UpdateAction updateAction) {
        if (Build.MODEL.contains("G906")) {
            updateAction.setAppType(13);
            setLogHeader("시스템웹뷰 G906 (Fallback)");
        } else if (Build.MODEL.contains("G900")) {
            updateAction.setAppType(13);
            setLogHeader("시스템웹뷰 G900 (Fallback)");
        } else if (Build.MODEL.contains("G930")) {
            updateAction.setAppType(9);
            setLogHeader("시스템웹뷰 G930 (Fallback)");
        } else {
            updateAction.setAppType(5);
            setLogHeader("시스템웹뷰 S4 (Fallback)");
        }
        
        updateAction.setPackageName(ActivityMCloud.PACKAGE_NAME_SYSTEM_WEBVIEW);
        
        Log.i(TAG, "Using fallback WebView logic for model: " + Build.MODEL);
    }
}
