package com.zero.traffic.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.zero.traffic.network.NetworkUtils;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 디바이스 실행 컨텍스트를 JSON으로 수집하는 유틸리티.
 * ML 분석용 피처 데이터를 서버에 전송하기 위해 사용.
 */
public class FingerprintCollector {
    private final Context context;

    // HTTP 응답 캡처 데이터 (ActionExecutor에서 설정)
    private volatile int lastHttpStatusCode = 0;
    private volatile JSONObject lastResponseHeaders;
    private volatile String lastBlockedUrl = "";
    private volatile String lastPageText = "";

    // 서버 config 기반 피처 (setConfigHeaders에서 설정)
    private volatile boolean secChUaPresent = false;
    private volatile String acceptEncoding = "";
    private volatile String secChUaPlatformVersion = "";

    // 터치 패턴 통계
    private final java.util.List<Long> touchIntervals = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile long lastTouchTime = 0;

    // 네비게이션 타이밍
    private volatile long navigationStartTime = 0;
    private volatile long navigationEndTime = 0;

    public FingerprintCollector(Context context) {
        this.context = context;
    }

    /**
     * HTTP 응답 정보 기록 (ActionExecutor.onReceivedHttpError에서 호출)
     */
    public void recordHttpResponse(int statusCode, Map<String, String> headers, String url) {
        this.lastHttpStatusCode = statusCode;
        this.lastBlockedUrl = url != null ? url : "";
        if (headers != null) {
            try {
                this.lastResponseHeaders = new JSONObject(headers);
            } catch (Exception e) {
                this.lastResponseHeaders = new JSONObject();
            }
        }
    }

    /**
     * 성공 응답 기록 (이전 차단 데이터 클리어)
     */
    public void recordHttpSuccess() {
        this.lastHttpStatusCode = 200;
        this.lastResponseHeaders = null;
        this.lastBlockedUrl = "";
        this.lastPageText = "";
    }

    /**
     * 차단 페이지 텍스트 기록 (checkStatus에서 호출)
     */
    public void recordBlockedPage(String url, String pageText) {
        this.lastBlockedUrl = url != null ? url : "";
        this.lastPageText = pageText != null ? pageText : "";
    }

    /**
     * 터치 이벤트 기록 (터치 패턴 분산 계산용)
     */
    public void recordTouchEvent() {
        long now = System.currentTimeMillis();
        if (lastTouchTime > 0) {
            touchIntervals.add(now - lastTouchTime);
        }
        lastTouchTime = now;
    }

    /**
     * 서버 config headers 정보 설정 (TrafficService에서 config pull 후 호출)
     */
    public void setConfigHeaders(boolean hasSecChUa, String acceptEnc, String platformVersion) {
        this.secChUaPresent = hasSecChUa;
        this.acceptEncoding = acceptEnc != null ? acceptEnc : "";
        this.secChUaPlatformVersion = platformVersion != null ? platformVersion : "";
    }

    /**
     * 네비게이션 시작 기록
     */
    public void recordNavigationStart() {
        this.navigationStartTime = System.currentTimeMillis();
    }

    /**
     * 네비게이션 완료 기록
     */
    public void recordNavigationEnd() {
        this.navigationEndTime = System.currentTimeMillis();
    }

    /**
     * 전체 피처 데이터를 JSON으로 수집
     */
    public JSONObject collect(WebView webView) {
        JSONObject features = new JSONObject();
        try {
            // 디바이스 기본 정보
            features.put("device_model", Build.MODEL);
            features.put("device_manufacturer", Build.MANUFACTURER);
            features.put("android_version", Build.VERSION.RELEASE);
            features.put("sdk_int", Build.VERSION.SDK_INT);
            features.put("build_fingerprint", Build.FINGERPRINT);

            // 화면 정보
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getMetrics(dm);
                features.put("screen_width", dm.widthPixels);
                features.put("screen_height", dm.heightPixels);
                features.put("screen_density", dm.density);
                features.put("screen_dpi", dm.densityDpi);
            }

            // WebView/Chrome 정보 (WebView는 UI 스레드 전용이므로 안전하게 접근)
            if (webView != null) {
                try {
                    // getSettings()는 스레드 세이프하지 않으므로 try-catch로 보호
                    WebSettings settings = webView.getSettings();
                    String ua = settings.getUserAgentString();
                    features.put("user_agent", ua != null ? ua : "");
                    features.put("webview_version", extractWebViewVersion(ua));
                    features.put("chrome_version", extractChromeVersion(ua));
                } catch (Exception e) {
                    // 워커 스레드에서 접근 시 실패 가능 — 빈 값으로 폴백
                    features.put("user_agent", "");
                    features.put("webview_version", "");
                    features.put("chrome_version", "");
                    Logger.w("FingerprintCollector: WebView 정보 수집 실패 (스레드 이슈): " + e.getMessage());
                }
            }

            // 네트워크 정보
            features.put("current_ip", NetworkUtils.getLocalIpAddress());
            features.put("connection_type", getConnectionType());

            // HTTP 응답 정보
            features.put("http_status_code", lastHttpStatusCode);
            if (lastResponseHeaders != null) {
                features.put("response_headers", lastResponseHeaders);
            }
            if (!lastBlockedUrl.isEmpty()) {
                features.put("blocked_url", lastBlockedUrl);
            }
            if (!lastPageText.isEmpty()) {
                features.put("blocked_page_text", lastPageText.length() > 500
                        ? lastPageText.substring(0, 500) : lastPageText);
            }

            // 네비게이션 타이밍
            if (navigationStartTime > 0 && navigationEndTime > navigationStartTime) {
                features.put("navigation_timing_ms", navigationEndTime - navigationStartTime);
            }

            // 터치 패턴 분산
            features.put("touch_pattern_variance", calculateTouchVariance());
            features.put("touch_sample_count", touchIntervals.size());

            // 서버 config 기반 피처 (ML 분석에 중요)
            features.put("sec_ch_ua_present", secChUaPresent);
            features.put("accept_encoding", acceptEncoding);
            features.put("sec_ch_ua_platform_version", secChUaPlatformVersion);

            // TLS 정보 (Android API level로 추정)
            features.put("tls_version", Build.VERSION.SDK_INT >= 29 ? "1.3" : "1.2");

            // 타임스탬프
            features.put("collected_at", System.currentTimeMillis());

        } catch (Exception e) {
            Logger.e("FingerprintCollector: 수집 실패: " + e.getMessage());
        }
        return features;
    }

    /**
     * 수집 데이터 초기화 (세션 간 리셋)
     */
    public void reset() {
        lastHttpStatusCode = 0;
        lastResponseHeaders = null;
        lastBlockedUrl = "";
        lastPageText = "";
        touchIntervals.clear();
        lastTouchTime = 0;
        navigationStartTime = 0;
        navigationEndTime = 0;
    }

    // ── 내부 유틸 ──

    private String extractChromeVersion(String ua) {
        if (ua == null) return "";
        int idx = ua.indexOf("Chrome/");
        if (idx < 0) return "";
        int end = ua.indexOf(' ', idx);
        return end > 0 ? ua.substring(idx + 7, end) : ua.substring(idx + 7);
    }

    private String extractWebViewVersion(String ua) {
        // WebView UA에서 "Chrome/xxx.x.xxxx.xxx" 부분 추출
        return extractChromeVersion(ua);
    }

    private String getConnectionType() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "unknown";
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return "none";
            switch (info.getType()) {
                case ConnectivityManager.TYPE_WIFI:
                    return "wifi";
                case ConnectivityManager.TYPE_MOBILE:
                    return "cellular";
                default:
                    return "other";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    private double calculateTouchVariance() {
        // synchronized 블록으로 ConcurrentModificationException 방지
        long[] copy;
        synchronized (touchIntervals) {
            if (touchIntervals.size() < 2) return 0.0;
            copy = new long[touchIntervals.size()];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = touchIntervals.get(i);
            }
        }
        double sum = 0;
        for (long interval : copy) {
            sum += interval;
        }
        double mean = sum / copy.length;
        double variance = 0;
        for (long interval : copy) {
            variance += (interval - mean) * (interval - mean);
        }
        return variance / copy.length;
    }
}
