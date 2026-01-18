package com.sec.android.app.sbrowser.engine;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.sec.android.app.sbrowser.config.ConfigManager;

import org.json.JSONObject;

/**
 * Option C: 서버 설정을 기반으로 WebView를 초기화하는 헬퍼 클래스
 */
public class WebViewHelper {
    private static final String TAG = "WebViewHelper";
    
    /**
     * 서버 설정을 적용하여 WebView 초기화
     * 
     * @param context Context
     * @param webView WebView 인스턴스
     * @param configManager ConfigManager 인스턴스
     */
    public static void initializeWebView(Context context, WebView webView, ConfigManager configManager) {
        WebSettings settings = webView.getSettings();
        
        // 서버에서 User-Agent 가져오기
        String userAgent = configManager.getUserAgent();
        settings.setUserAgentString(userAgent);
        Log.i(TAG, "User-Agent set: " + userAgent);
        
        // 서버에서 WebView 설정 가져오기
        JSONObject webViewSettings = configManager.getWebViewSettings();
        applyWebViewSettings(settings, webViewSettings);
        
        // 쿠키 관리자 설정
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }
        
        // CustomWebViewClient 설정
        CustomWebViewClient webViewClient = new CustomWebViewClient(configManager);
        webView.setWebViewClient(webViewClient);
        
        Log.i(TAG, "WebView initialized with server configuration");
    }
    
    /**
     * 서버 설정을 WebSettings에 적용
     */
    private static void applyWebViewSettings(WebSettings settings, JSONObject webViewSettings) {
        if (webViewSettings == null) {
            // 기본 설정 적용
            applyDefaultSettings(settings);
            return;
        }
        
        try {
            // JavaScript 활성화
            boolean jsEnabled = webViewSettings.optBoolean("javascript_enabled", true);
            settings.setJavaScriptEnabled(jsEnabled);
            
            // DOM Storage 활성화
            boolean domStorageEnabled = webViewSettings.optBoolean("dom_storage_enabled", true);
            settings.setDomStorageEnabled(domStorageEnabled);
            
            // Database 활성화
            boolean dbEnabled = webViewSettings.optBoolean("database_enabled", true);
            settings.setDatabaseEnabled(dbEnabled);
            
            // Mixed Content Mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                String mixedContentMode = webViewSettings.optString("mixed_content_mode", "MIXED_CONTENT_ALWAYS_ALLOW");
                if (mixedContentMode.equals("MIXED_CONTENT_ALWAYS_ALLOW")) {
                    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                } else if (mixedContentMode.equals("MIXED_CONTENT_COMPATIBILITY_MODE")) {
                    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                } else {
                    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                }
            }
            
            // Cache Mode
            String cacheMode = webViewSettings.optString("cache_mode", "LOAD_DEFAULT");
            if (cacheMode.equals("LOAD_DEFAULT")) {
                settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            } else if (cacheMode.equals("LOAD_CACHE_ELSE_NETWORK")) {
                settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
            } else if (cacheMode.equals("LOAD_NO_CACHE")) {
                settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            } else if (cacheMode.equals("LOAD_CACHE_ONLY")) {
                settings.setCacheMode(WebSettings.LOAD_CACHE_ONLY);
            }
            
            // File Access
            boolean allowFileAccess = webViewSettings.optBoolean("allow_file_access", false);
            settings.setAllowFileAccess(allowFileAccess);
            
            boolean allowContentAccess = webViewSettings.optBoolean("allow_content_access", true);
            settings.setAllowContentAccess(allowContentAccess);
            
            // Zoom 설정
            boolean supportZoom = webViewSettings.optBoolean("support_zoom", false);
            settings.setSupportZoom(supportZoom);
            
            boolean builtInZoomControls = webViewSettings.optBoolean("builtin_zoom_controls", false);
            settings.setBuiltInZoomControls(builtInZoomControls);
            
            boolean displayZoomControls = webViewSettings.optBoolean("display_zoom_controls", false);
            settings.setDisplayZoomControls(displayZoomControls);
            
            // Viewport 설정
            boolean useWideViewPort = webViewSettings.optBoolean("use_wide_view_port", true);
            settings.setUseWideViewPort(useWideViewPort);
            
            boolean loadWithOverviewMode = webViewSettings.optBoolean("load_with_overview_mode", true);
            settings.setLoadWithOverviewMode(loadWithOverviewMode);
            
            // Safe Browsing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                boolean safeBrowsingEnabled = webViewSettings.optBoolean("safe_browsing_enabled", false);
                settings.setSafeBrowsingEnabled(safeBrowsingEnabled);
            }
            
            Log.i(TAG, "WebView settings applied from server configuration");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply WebView settings, using defaults", e);
            applyDefaultSettings(settings);
        }
    }
    
    /**
     * 기본 WebView 설정 적용
     */
    private static void applyDefaultSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }
        
        Log.i(TAG, "Default WebView settings applied");
    }
}
