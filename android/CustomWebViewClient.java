package com.sec.android.app.sbrowser.engine;

import android.graphics.Bitmap;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.sec.android.app.sbrowser.config.ConfigManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Option C: 서버에서 받은 설정을 WebView에 적용하는 커스텀 WebViewClient
 */
public class CustomWebViewClient extends WebViewClient {
    private static final String TAG = "CustomWebViewClient";
    private ConfigManager configManager;
    
    public CustomWebViewClient(ConfigManager configManager) {
        this.configManager = configManager;
    }
    
    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        Log.d(TAG, "Page started: " + url);
    }
    
    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        Log.d(TAG, "Page finished: " + url);
    }
    
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        // 여기서 요청을 가로채서 커스텀 헤더를 추가할 수 있습니다
        // 하지만 WebView의 제약으로 인해 모든 헤더를 추가할 수는 없습니다
        return super.shouldInterceptRequest(view, request);
    }
    
    /**
     * 서버에서 받은 커스텀 헤더를 포함하여 URL 로드
     * 
     * @param webView WebView 인스턴스
     * @param url 로드할 URL
     */
    public void loadUrlWithServerHeaders(WebView webView, String url) {
        // ConfigManager에서 커스텀 헤더 가져오기
        Map<String, String> customHeaders = configManager.getCustomHeaders();
        
        // 추가 헤더 설정
        Map<String, String> headers = new HashMap<>(customHeaders);
        
        // Referer 동적 설정 (현재 URL 기반)
        String currentUrl = webView.getUrl();
        if (currentUrl != null && !currentUrl.isEmpty()) {
            headers.put("referer", currentUrl);
            
            // Sec-Fetch-Site 동적 설정
            if (isSameSite(currentUrl, url)) {
                headers.put("sec-fetch-site", "same-origin");
            } else if (isSameDomain(currentUrl, url)) {
                headers.put("sec-fetch-site", "same-site");
            } else {
                headers.put("sec-fetch-site", "cross-site");
            }
        } else {
            headers.put("sec-fetch-site", "none");
        }
        
        // Sec-Fetch-Mode 설정 (기본값: navigate)
        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-user", "?1");
        
        Log.d(TAG, "Loading URL with custom headers: " + url);
        Log.d(TAG, "Headers: " + headers.toString());
        
        // 헤더와 함께 URL 로드
        webView.loadUrl(url, headers);
    }
    
    /**
     * 두 URL이 같은 사이트인지 확인
     */
    private boolean isSameSite(String url1, String url2) {
        try {
            return url1.startsWith(getOrigin(url2));
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 두 URL이 같은 도메인인지 확인
     */
    private boolean isSameDomain(String url1, String url2) {
        try {
            String domain1 = getDomain(url1);
            String domain2 = getDomain(url2);
            return domain1.equals(domain2);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * URL에서 Origin 추출
     */
    private String getOrigin(String url) {
        int index = url.indexOf("/", url.indexOf("//") + 2);
        if (index > 0) {
            return url.substring(0, index);
        }
        return url;
    }
    
    /**
     * URL에서 도메인 추출
     */
    private String getDomain(String url) {
        String origin = getOrigin(url);
        return origin.replace("https://", "").replace("http://", "");
    }
}
