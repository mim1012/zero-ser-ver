/**
 * Option C 통합 예제 코드
 * 
 * 기존 Zero 앱의 Activity에서 서버 기반 설정을 사용하는 방법
 */

package com.sec.android.app.sbrowser;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;

import com.sec.android.app.sbrowser.config.ConfigManager;
import com.sec.android.app.sbrowser.engine.WebViewHelper;

public class ActivityMCloud extends AppCompatActivity {
    
    private static final String SERVER_URL = "https://your-server-url.com"; // 실제 서버 URL로 변경
    private WebView webView;
    private ConfigManager configManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcloud);
        
        // 1. ConfigManager 초기화
        configManager = ConfigManager.getInstance(this, SERVER_URL);
        
        // 2. 서버에서 최신 설정 다운로드
        String deviceModel = Build.MODEL; // 예: "SM-G998N"
        String chromeVersion = "143"; // 현재 WebView의 Chrome 버전
        
        // 백그라운드 스레드에서 설정 다운로드
        new Thread(() -> {
            boolean success = configManager.updateFromServer(deviceModel, chromeVersion);
            if (success) {
                runOnUiThread(() -> {
                    // 3. WebView 초기화 (서버 설정 적용)
                    initializeWebView();
                });
            } else {
                // 서버 연결 실패 시에도 캐시된 설정으로 진행
                runOnUiThread(() -> {
                    initializeWebView();
                });
            }
        }).start();
    }
    
    private void initializeWebView() {
        webView = findViewById(R.id.webView);
        
        // 4. WebViewHelper를 사용하여 서버 설정 적용
        WebViewHelper.initializeWebView(this, webView, configManager);
        
        // 5. URL 로드 (커스텀 헤더 포함)
        CustomWebViewClient client = (CustomWebViewClient) webView.getWebViewClient();
        client.loadUrlWithServerHeaders(webView, "https://m.shopping.naver.com/");
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // 6. 1시간마다 자동 업데이트
        if (configManager != null) {
            String deviceModel = Build.MODEL;
            String chromeVersion = "143";
            
            new Thread(() -> {
                configManager.autoUpdateIfNeeded(deviceModel, chromeVersion);
            }).start();
        }
    }
}


/**
 * build.gradle 설정
 * 
 * dependencies {
 *     implementation 'com.squareup.okhttp3:okhttp:4.12.0'
 * }
 */


/**
 * AndroidManifest.xml 권한 추가
 * 
 * <uses-permission android:name="android.permission.INTERNET" />
 * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 */
