package com.zero.automation;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

/**
 * Option C: 서버 설정을 기반으로 WebView를 초기화하는 헬퍼 클래스
 */
public class WebViewHelper {
    private static final String TAG = "WebViewHelper";

    public static void initializeWebView(Context context, WebView webView, ConfigManager configManager) {
        WebSettings settings = webView.getSettings();

        String userAgent = configManager.getUserAgent();
        settings.setUserAgentString(userAgent);
        Log.i(TAG, "User-Agent set: " + userAgent);

        JSONObject webViewSettings = configManager.getWebViewSettings();
        applyWebViewSettings(settings, webViewSettings);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        CustomWebViewClient webViewClient = new CustomWebViewClient(configManager);
        webView.setWebViewClient(webViewClient);

        Log.i(TAG, "WebView initialized with server configuration");
    }

    private static void applyWebViewSettings(WebSettings settings, JSONObject webViewSettings) {
        if (webViewSettings == null) {
            applyDefaultSettings(settings);
            return;
        }

        try {
            settings.setJavaScriptEnabled(webViewSettings.optBoolean("javascript_enabled", true));
            settings.setDomStorageEnabled(webViewSettings.optBoolean("dom_storage_enabled", true));
            settings.setDatabaseEnabled(webViewSettings.optBoolean("database_enabled", true));

            String mixedContentMode = webViewSettings.optString("mixed_content_mode", "MIXED_CONTENT_ALWAYS_ALLOW");
            if (mixedContentMode.equals("MIXED_CONTENT_ALWAYS_ALLOW")) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            } else if (mixedContentMode.equals("MIXED_CONTENT_COMPATIBILITY_MODE")) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            } else {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            }

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

            settings.setAllowFileAccess(webViewSettings.optBoolean("allow_file_access", false));
            settings.setAllowContentAccess(webViewSettings.optBoolean("allow_content_access", true));
            settings.setSupportZoom(webViewSettings.optBoolean("support_zoom", false));
            settings.setBuiltInZoomControls(webViewSettings.optBoolean("builtin_zoom_controls", false));
            settings.setDisplayZoomControls(webViewSettings.optBoolean("display_zoom_controls", false));
            settings.setUseWideViewPort(webViewSettings.optBoolean("use_wide_view_port", true));
            settings.setLoadWithOverviewMode(webViewSettings.optBoolean("load_with_overview_mode", true));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.setSafeBrowsingEnabled(webViewSettings.optBoolean("safe_browsing_enabled", false));
            }

            Log.i(TAG, "WebView settings applied from server configuration");

        } catch (Exception e) {
            Log.e(TAG, "Failed to apply WebView settings, using defaults", e);
            applyDefaultSettings(settings);
        }
    }

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
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        Log.i(TAG, "Default WebView settings applied");
    }
}
