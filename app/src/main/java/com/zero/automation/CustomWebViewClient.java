package com.zero.automation;

import android.graphics.Bitmap;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
        return super.shouldInterceptRequest(view, request);
    }

    public void loadUrlWithServerHeaders(WebView webView, String url) {
        Map<String, String> customHeaders = configManager.getCustomHeaders();
        Map<String, String> headers = new HashMap<>(customHeaders);

        String currentUrl = webView.getUrl();
        if (currentUrl != null && !currentUrl.isEmpty()) {
            headers.put("referer", currentUrl);

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

        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-user", "?1");

        Log.d(TAG, "Loading URL with custom headers: " + url);

        webView.loadUrl(url, headers);
    }

    private boolean isSameSite(String url1, String url2) {
        try {
            return url1.startsWith(getOrigin(url2));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSameDomain(String url1, String url2) {
        try {
            String domain1 = getDomain(url1);
            String domain2 = getDomain(url2);
            return domain1.equals(domain2);
        } catch (Exception e) {
            return false;
        }
    }

    private String getOrigin(String url) {
        int index = url.indexOf("/", url.indexOf("//") + 2);
        if (index > 0) {
            return url.substring(0, index);
        }
        return url;
    }

    private String getDomain(String url) {
        String origin = getOrigin(url);
        return origin.replace("https://", "").replace("http://", "");
    }
}
