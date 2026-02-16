package com.zero.traffic.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;

import com.zero.traffic.captcha.CaptchaProxy;
import com.zero.traffic.engine.ScenarioManager;
import com.zero.traffic.engine.ScenarioRunner;
import com.zero.traffic.engine.ScriptEngine;
import com.zero.traffic.model.Scenario;
import com.zero.traffic.model.StepResult;
import com.zero.traffic.model.TaskInfo;
import com.zero.traffic.network.ChromeManager;
import com.zero.traffic.network.GroupManager;
import com.zero.traffic.network.NetworkUtils;
import com.zero.traffic.server.ApiClient;
import com.zero.traffic.server.TaskManager;
import com.zero.traffic.util.FingerprintCollector;
import com.zero.traffic.util.Logger;
import com.zero.traffic.util.RandomDelay;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 트래픽 포그라운드 서비스 — 메인 루프
 *
 * 1. 초기화 (API 클라이언트, 시나리오, 스크립트)
 * 2. 루프: 작업 받기 → 시나리오 선택 → 실행 → 결과 보고
 * 3. 자동 업데이트 (1시간마다)
 */
public class TrafficService extends Service {
    private static final String CHANNEL_ID = "zero_traffic";
    private static final int NOTIFICATION_ID = 1;
    private static final String EXTRA_SERVER_URL = "server_url";
    private static final String ACTION_TOGGLE_WEBVIEW = "kr.co.mobilelife.app.TOGGLE_WEBVIEW";

    private ExecutorService worker;
    private volatile boolean running = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile WebView webView;
    private volatile ApiClient api;
    private volatile TaskManager taskManager;
    private volatile ScenarioManager scenarioManager;
    private volatile ScriptEngine scriptEngine;
    private volatile CaptchaProxy captchaProxy;
    private volatile ScenarioRunner runner;
    private volatile GroupManager groupManager;
    private volatile FingerprintCollector fingerprintCollector;
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private volatile boolean webViewVisible = false;

    private String deviceId;
    private String lastNotificationText = "대기 중...";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        try {
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            deviceId = Build.SERIAL != null ? Build.SERIAL : "unknown_" + System.currentTimeMillis();
            Logger.w("ANDROID_ID 접근 불가, fallback: " + deviceId);
        }
        worker = Executors.newSingleThreadExecutor();
        ToggleReceiver.serviceRef = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String serverUrl = intent != null
                ? intent.getStringExtra(EXTRA_SERVER_URL)
                : null;

        if (serverUrl == null || serverUrl.isEmpty()) {
            Logger.e("서버 URL 없음");
            stopSelf();
            return START_NOT_STICKY;
        }

        // 포그라운드 알림
        Notification notification = buildNotification("초기화 중...");
        startForeground(NOTIFICATION_ID, notification);

        // 초기화 + 메인 루프 시작
        running = true;
        worker.execute(() -> {
            try {
                init(serverUrl);
                mainLoop();
            } catch (Exception e) {
                Logger.e("서비스 오류", e);
                stopSelf();
            }
        });

        return START_STICKY;
    }

    /**
     * 초기화
     */
    private void init(String serverUrl) {
        Logger.i("══════════════════════════════════════");
        Logger.i("  Zero Traffic v2 시작");
        Logger.i("  서버: " + serverUrl);
        Logger.i("  기기: " + deviceId);
        Logger.i("══════════════════════════════════════");

        // API 클라이언트
        api = new ApiClient(serverUrl + "/zero/api/v1");

        // 1. 그룹 등록 (대장봇/쫄병봇 역할 할당)
        groupManager = new GroupManager(this, api, deviceId);
        String currentIp = NetworkUtils.getLocalIpAddress();
        groupManager.register(currentIp);

        if (groupManager.isRegistered()) {
            Logger.i(String.format("그룹: %s | 역할: %s",
                    groupManager.getGroupName(),
                    groupManager.isLeader() ? "대장봇 ★" : "쫄병봇"));

            // 2. 네트워크 설정 (대장봇=핫스팟, 쫄병봇=WiFi 연결)
            groupManager.setupNetwork();
            updateNotification(groupManager.isLeader() ? "대장봇 — 핫스팟 ON" : "쫄병봇 — WiFi 연결");
            RandomDelay.sleepBetween(3000, 5000); // 네트워크 안정화 대기
        } else {
            Logger.w("그룹 미등록 — 독립 모드로 실행");
        }

        // 3. Chrome 확인/설치 (WebView TLS 핑거프린트 최신화)
        ChromeManager chromeManager = new ChromeManager(this, api);
        if (chromeManager.ensureChromeReady()) {
            Logger.i("Chrome 준비 완료 (v" + chromeManager.getInstalledChromeVersion() + ")");
        } else {
            Logger.w("Chrome 미준비 — 기본 WebView로 진행");
        }

        // 4. WebView 초기화 (메인 스레드에서)
        if (!initWebView()) {
            throw new IllegalStateException("WebView 초기화 실패");
        }

        // 5. 매니저 초기화
        taskManager = new TaskManager(api, deviceId);
        scenarioManager = new ScenarioManager(this, api, deviceId);
        scriptEngine = new ScriptEngine(this, api);
        captchaProxy = new CaptchaProxy(api, deviceId);
        runner = new ScenarioRunner(webView, captchaProxy, scriptEngine);

        // 6. FingerprintCollector 초기화 + ScenarioRunner에 연결
        fingerprintCollector = new FingerprintCollector(this);
        runner.setAnalytics(fingerprintCollector, taskManager);

        // 7. 서버 동기화
        scenarioManager.sync();
        scriptEngine.sync();

        Logger.i("초기화 완료. 시나리오: " + scenarioManager.getCount() + "개");
        updateNotification(String.format("%s | %s | %d개 시나리오",
                groupManager.isLeader() ? "★대장" : "쫄병",
                groupManager.getGroupName(),
                scenarioManager.getCount()));
    }

    /**
     * WebView 초기화 (메인 스레드) + WindowManager 오버레이 표시
     */
    private boolean initWebView() {
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] created = {false};

        mainHandler.post(() -> {
            try {
                webView = new WebView(TrafficService.this);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setAllowFileAccess(false);
                settings.setAllowContentAccess(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    settings.setAllowFileAccessFromFileURLs(false);
                    settings.setAllowUniversalAccessFromFileURLs(false);
                }
                // WebView 기본 UA 사용 (실제 WebView 버전과 일치시킴)
                // 하드코딩 UA는 WebView 버전과 불일치하여 봇 감지됨
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                        super.onPageStarted(view, url, favicon);
                        // 페이지 로드 시작 즉시 스텔스 주입 (봇 감지 선제 차단)
                        view.evaluateJavascript(
                            com.zero.traffic.engine.ActionExecutor.STEALTH_JS, null);
                    }
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        return false; // 모든 URL을 WebView 내부에서 처리
                    }
                });

                // WindowManager 오버레이로 WebView를 화면에 붙이기
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                // 완전 투명 오버레이 (렌더링 정상, 사용자에게 안 보임)
                overlayParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                );
                overlayParams.gravity = Gravity.TOP | Gravity.START;
                overlayParams.alpha = 0f;  // 완전 투명

                windowManager.addView(webView, overlayParams);
                Logger.i("WebView 오버레이 표시 완료");

                created[0] = true;
            } catch (Exception e) {
                Logger.e("WebView 초기화 실패: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                Logger.e("WebView 초기화 타임아웃");
                return false;
            }
            return created[0] && webView != null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.w("WebView 초기화 인터럽트");
            return false;
        }
    }

    /**
     * 메인 루프
     */
    private void mainLoop() {
        long lastSync = System.currentTimeMillis();

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // 자동 업데이트 (1시간마다)
                if (System.currentTimeMillis() - lastSync > 3600_000) {
                    Logger.i("자동 업데이트 중...");
                    scenarioManager.sync();
                    scriptEngine.sync();
                    lastSync = System.currentTimeMillis();
                }

                // 1. 작업 받기
                TaskInfo task = taskManager.claimWork();
                if (task == null) {
                    Logger.i("작업 없음 — 60초 대기");
                    RandomDelay.sleepBetween(50000, 70000);
                    continue;
                }

                Logger.i("작업 수신: #" + task.getTrafficId() + " " + task.getKeyword());
                updateNotification("실행 중: " + task.getKeyword());

                // 2. 시나리오 선택 (가중치 기반)
                Scenario scenario = scenarioManager.selectScenario();
                if (scenario == null) {
                    Logger.e("시나리오 없음");
                    taskManager.fail(task.getTrafficId(), task.getSlotId(), "No scenario available");
                    RandomDelay.sleepBetween(30000, 60000);
                    continue;
                }

                // 3. 실행
                StepResult result = runner.execute(scenario, task);

                // 4. 결과 보고 (실패 시 fingerprint 데이터 첨부)
                if (result.isSuccess()) {
                    taskManager.complete(task.getTrafficId(), task.getSlotId());
                } else {
                    org.json.JSONObject fp = fingerprintCollector != null
                            ? fingerprintCollector.collect(webView) : null;
                    taskManager.fail(task.getTrafficId(), task.getSlotId(), result.getMessage(), fp);
                }

                // 5. 세션 종료 → WebView 초기화 + fingerprint 리셋
                updateNotification("세션 종료...");
                if (fingerprintCollector != null) {
                    fingerprintCollector.reset();
                }
                resetWebView();
                // 6. IP 변경 (루팅폰 — su로 데이터 토글)
                Logger.i("SCENARIO_DONE — IP 로테이션 시작");
                updateNotification("IP 변경 중...");
                rotateIP();

            } catch (Exception e) {
                Logger.e("루프 오류: " + e.getMessage());
                if (Thread.currentThread().isInterrupted()) {
                    Logger.i("워커 인터럽트 감지 — 루프 종료");
                    break;
                }
                RandomDelay.sleepBetween(30000, 60000);
            }
        }
    }

    // ── IP 변경 (모바일 데이터 토글) ─────────────────────

    /**
     * 공인 IP 조회 (api.ipify.org)
     * @return IP 문자열 or null
     */
    private String getPublicIP() {
        // IPv6 우선 (api64), 실패 시 IPv4 (api) — Naver는 IPv6로 접근하므로 IPv6 변경이 핵심
        String[] urls = {"https://api64.ipify.org", "https://api.ipify.org"};
        for (String url : urls) {
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build();
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(url)
                        .build();
                try (okhttp3.Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        return resp.body().string().trim();
                    }
                }
            } catch (Exception e) {
                Logger.w("IP 조회 실패 (" + url + "): " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 모바일 데이터 토글로 IP 변경 (루팅폰 전용 — su 사용)
     * 전략: su -c "svc data disable" → 20초 대기 → su -c "svc data enable" → 10초 복구
     * 최대 2회 재시도
     */
    private void rotateIP() {
        String oldIP = getPublicIP();
        Logger.i("══ IP 변경 시작 — 현재 IP: " + (oldIP != null ? oldIP : "조회실패") + " ══");

        for (int attempt = 1; attempt <= 2; attempt++) {
            Logger.i("[IP변경] 시도 " + attempt + "/2 — 데이터 OFF 20초");
            try {
                // 1. 데이터 OFF (su로 root 권한 실행)
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "svc data disable"});
                p.waitFor();
                Logger.i("[IP변경] svc data disable 완료 (su)");

                // 2. 20초 대기 (캐리어 IPv6 세션 해제)
                RandomDelay.sleepBetween(20000, 22000);

                // 3. 데이터 ON
                Process p2 = Runtime.getRuntime().exec(new String[]{"su", "-c", "svc data enable"});
                p2.waitFor();
                Logger.i("[IP변경] svc data enable 완료 (su) — 네트워크 복구 대기 10초");

                // 4. 네트워크 복구 대기
                RandomDelay.sleepBetween(10000, 12000);

                // 5. IP 변경 확인 (최대 10초)
                for (int i = 0; i < 5; i++) {
                    RandomDelay.sleepBetween(2000, 2000);
                    String newIP = getPublicIP();
                    if (newIP != null) {
                        if (oldIP == null || !newIP.equals(oldIP)) {
                            Logger.i("══ IP 변경 완료: " + (oldIP != null ? oldIP : "?") + " → " + newIP + " ══");
                            return;
                        }
                        Logger.w("[IP변경] 미변경: " + newIP);
                    }
                }
                Logger.w("[IP변경] 시도 " + attempt + " 실패 — 재시도");
            } catch (Exception e) {
                Logger.e("[IP변경] 오류: " + e.getMessage());
                // 안전장치: 데이터 ON 보장
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c", "svc data enable"}).waitFor();
                } catch (Exception ignored) {}
            }
        }

        Logger.w("══ IP 미변경 — 같은 IP로 계속 진행 ══");
    }

    // ── WebView 초기화 (쿠키/캐시 클리어) ──────────────

    /** Naver 쿠키 유지 대상 도메인 */
    private static final String[] NAVER_COOKIE_DOMAINS = {
        ".naver.com", "naver.com",
        ".shopping.naver.com", "shopping.naver.com",
        ".smartstore.naver.com", "smartstore.naver.com",
        ".search.naver.com", "search.naver.com",
        ".msearch.shopping.naver.com"
    };

    private void resetWebView() {
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                if (webView != null) {
                    webView.stopLoading();
                    webView.clearCache(true);
                    webView.clearHistory();

                    // Naver 쿠키는 유지, 나머지만 삭제
                    // (Chrome은 Naver 쿠키(NNB, NID 등) 유지 → "신뢰된 사용자" 인식)
                    android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                    String[] domains = {
                        "https://www.naver.com",
                        "https://m.naver.com",
                        "https://search.naver.com",
                        "https://m.search.naver.com",
                        "https://shopping.naver.com",
                        "https://msearch.shopping.naver.com",
                        "https://smartstore.naver.com",
                        "https://cr3.shopping.naver.com"
                    };

                    // Naver 도메인 쿠키 백업
                    java.util.Map<String, String> naverCookies = new java.util.LinkedHashMap<>();
                    for (String domain : domains) {
                        String cookies = cm.getCookie(domain);
                        if (cookies != null && !cookies.isEmpty()) {
                            naverCookies.put(domain, cookies);
                        }
                    }

                    // 전체 쿠키 삭제
                    cm.removeAllCookies(null);

                    // Naver 쿠키 복원
                    int restored = 0;
                    for (java.util.Map.Entry<String, String> entry : naverCookies.entrySet()) {
                        String domain = entry.getKey();
                        String[] cookieParts = entry.getValue().split(";");
                        for (String cookie : cookieParts) {
                            String trimmed = cookie.trim();
                            if (!trimmed.isEmpty()) {
                                cm.setCookie(domain, trimmed);
                                restored++;
                            }
                        }
                    }
                    cm.flush();

                    webView.loadUrl("about:blank");
                    Logger.i("WebView 초기화 완료 (Naver 쿠키 " + restored + "개 유지, 나머지 삭제)");
                }
            } catch (Exception e) {
                Logger.w("WebView 초기화 실패: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── WebView 보이기/숨기기 토글 ────────────────────────

    /** ToggleReceiver에서 호출 */
    public void onToggleWebView() {
        toggleWebViewVisibility();
    }

    private void toggleWebViewVisibility() {
        mainHandler.post(() -> {
            if (webView == null || windowManager == null || overlayParams == null) return;
            webViewVisible = !webViewVisible;
            overlayParams.alpha = webViewVisible ? 1.0f : 0f;
            try {
                windowManager.updateViewLayout(webView, overlayParams);
            } catch (Exception e) {
                Logger.w("WebView 토글 실패: " + e.getMessage());
            }
            Logger.i("WebView " + (webViewVisible ? "보이기" : "숨기기"));
            updateNotification(lastNotificationText);
        });
    }

    // ── 알림 ───────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Zero Traffic", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // 보이기/숨기기 토글 액션 버튼
        Intent toggleIntent = new Intent(ACTION_TOGGLE_WEBVIEW);
        toggleIntent.setClass(this, ToggleReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent togglePending = PendingIntent.getBroadcast(this, 0, toggleIntent, flags);
        String toggleLabel = webViewVisible ? "\uD83D\uDC41 숨기기" : "\uD83D\uDC41 보이기";

        builder.setContentTitle("Zero Traffic")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n\n[WebView: " + (webViewVisible ? "ON" : "OFF") + "]"))
                .addAction(android.R.drawable.ic_menu_view, toggleLabel, togglePending);

        return builder.build();
    }

    private void updateNotification(String text) {
        lastNotificationText = text;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        ToggleReceiver.serviceRef = null;
        if (worker != null) worker.shutdownNow();
        if (runner != null) runner.cancel();
        if (groupManager != null) groupManager.cleanup();
        WebView currentWebView = webView;
        if (currentWebView != null) {
            CountDownLatch destroyLatch = new CountDownLatch(1);
            mainHandler.post(() -> {
                try {
                    currentWebView.stopLoading();
                    currentWebView.loadUrl("about:blank");
                    currentWebView.clearHistory();
                    currentWebView.removeAllViews();
                    // WindowManager에서 제거
                    if (windowManager != null) {
                        windowManager.removeView(currentWebView);
                    }
                    currentWebView.destroy();
                } catch (Exception e) {
                    Logger.w("WebView 해제 실패: " + e.getMessage());
                } finally {
                    destroyLatch.countDown();
                }
            });
            try {
                destroyLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
