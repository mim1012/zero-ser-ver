package com.zero.traffic.engine;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.zero.traffic.model.Step;
import com.zero.traffic.model.StepResult;
import com.zero.traffic.util.FingerprintCollector;
import com.zero.traffic.util.Logger;
import com.zero.traffic.util.RandomDelay;

import org.conscrypt.Conscrypt;
import org.json.JSONObject;

import java.security.Security;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 개별 Action 실행기 — WebView 제어
 *
 * 모든 메서드는 워커 스레드에서 호출.
 * WebView 조작은 Handler를 통해 메인 스레드에서 실행.
 */
public class ActionExecutor {
    private final WebView webView;
    private final Handler mainHandler;

    // findMid 폴백용: 원본 키워드로 직접 검색 재시도
    private String taskKeyword = "";
    private String taskMid = "";

    // 캐시된 Chrome UA (메인 스레드에서 설정, IO 스레드에서 읽기)
    private volatile String cachedChromeUA = "";

    // WebView에서 쇼핑 도메인 직접 로드 허용 플래그 (findMid → 상품페이지)
    private volatile boolean allowShoppingInWebView = false;

    // Fingerprint 수집기 (ML 분석용)
    private FingerprintCollector fingerprintCollector;

    // 상품페이지 HTTP 상태코드 (onReceivedHttpError → handleMidFound 연동)
    private volatile int lastProductPageStatus = 0;

    // OkHttp + Conscrypt (BoringSSL TLS) — WebView TLS 핑거프린트 우회
    private volatile OkHttpClient conscryptClient;

    private OkHttpClient getConscryptClient() {
        if (conscryptClient != null) return conscryptClient;
        try {
            // Conscrypt를 최우선 TLS 프로바이더로 설정 (Chrome과 동일한 BoringSSL)
            Security.insertProviderAt(Conscrypt.newProvider(), 1);

            X509TrustManager trustManager = new X509TrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            };
            SSLContext sslCtx = SSLContext.getInstance("TLS", Conscrypt.newProvider());
            sslCtx.init(null, new TrustManager[]{trustManager}, null);

            conscryptClient = new OkHttpClient.Builder()
                .sslSocketFactory(sslCtx.getSocketFactory(), trustManager)
                .protocols(Arrays.asList(Protocol.HTTP_1_1)) // HTTP/2 핑거프린트 회피
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
            Logger.i("★ OkHttp+Conscrypt 클라이언트 초기화 완료");
        } catch (Exception e) {
            Logger.e("★ Conscrypt 초기화 실패: " + e.getMessage());
            conscryptClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        }
        return conscryptClient;
    }

    // ── 쇼핑 도메인 판별 (Chrome Intent 라우팅용) ──

    private static final String[] SHOPPING_DOMAINS = {
        "cr3.shopping.naver.com", "msearch.shopping.naver.com",
        "shopping.naver.com", "smartstore.naver.com", "brand.naver.com"
    };

    private static boolean isShoppingDomain(String url) {
        for (String d : SHOPPING_DOMAINS) {
            if (url.contains(d)) return true;
        }
        return false;
    }

    public ActionExecutor(WebView webView) {
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /** FingerprintCollector 설정 (TrafficService에서 호출) */
    public void setFingerprintCollector(FingerprintCollector collector) {
        this.fingerprintCollector = collector;
    }

    /** ScenarioRunner에서 호출 — 현재 작업의 키워드/MID 전달 */
    public void setTaskContext(String keyword, String mid) {
        this.taskKeyword = keyword != null ? keyword : "";
        this.taskMid = mid != null ? mid : "";
    }

    // ── 봇 감지 우회 스텔스 스크립트 (mobile-stealth.ts 동기화) ──

    public static final String STEALTH_JS =
        // 각 섹션 try-catch 래핑 — 하나 실패해도 나머지 실행
        // ── 1. navigator.webdriver 제거 (봇 감지 1순위) ──
        "try{Object.defineProperty(navigator,'webdriver',{get:()=>false});}catch(e){}" +

        // ── 2. navigator.plugins (Chrome 5개, WebView 0개 → 핵심 탐지 벡터) ──
        // 캐시 참조 반환 — navigator.plugins===navigator.plugins → true
        "try{if(navigator.plugins.length===0){" +
        "var _mkP=function(n,d,f,t){var p={name:n,description:d,filename:f,length:1};" +
        "var m={type:t,suffixes:'',description:d,enabledPlugin:p};p[0]=m;return p;};" +
        "var _ps=[" +
        "_mkP('PDF Viewer','Portable Document Format','internal-pdf-viewer','application/pdf')," +
        "_mkP('Chrome PDF Viewer','Portable Document Format','internal-pdf-viewer','application/pdf')," +
        "_mkP('Chromium PDF Viewer','Portable Document Format','internal-pdf-viewer','application/pdf')," +
        "_mkP('Microsoft Edge PDF Viewer','Portable Document Format','internal-pdf-viewer','application/pdf')," +
        "_mkP('WebKit built-in PDF','Portable Document Format','internal-pdf-viewer','application/pdf')];" +
        "_ps.item=function(i){return _ps[i]||null;};" +
        "_ps.namedItem=function(n){for(var i=0;i<_ps.length;i++){if(_ps[i].name===n)return _ps[i];}return null;};" +
        "_ps.refresh=function(){};" +
        "var _ms=_ps.map(function(p){return p[0];});" +
        "_ms.item=function(i){return _ms[i]||null;};" +
        "_ms.namedItem=function(n){for(var i=0;i<_ms.length;i++){if(_ms[i].type===n)return _ms[i];}return null;};" +
        "Object.defineProperty(navigator,'plugins',{get:function(){return _ps;}});" +
        "Object.defineProperty(navigator,'mimeTypes',{get:function(){return _ms;}});}}catch(e){}" +

        // ── 3. navigator.vendor (WebView='' → Chrome='Google Inc.') ──
        "try{Object.defineProperty(navigator,'vendor',{get:()=>'Google Inc.'});}catch(e){}" +

        // ── 4. navigator.languages (한국어 + 영어) ──
        "try{Object.defineProperty(navigator,'languages',{get:()=>['ko-KR','ko','en-US','en']});}catch(e){}" +

        // ── 5. navigator.connection 모바일 네트워크 ──
        "try{Object.defineProperty(navigator,'connection',{get:()=>({" +
        "effectiveType:'4g',rtt:50,downlink:10,saveData:false,type:'cellular'," +
        "addEventListener:()=>{},removeEventListener:()=>{}})});}catch(e){}" +

        // ── 6. window.chrome 객체 (실제 Chrome 구조 모방) ──
        "try{if(!window.chrome||!window.chrome.runtime){window.chrome={" +
        "app:{isInstalled:false,InstallState:{DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'}," +
        "RunningState:{CANNOT_RUN:'cannot_run',READY_TO_RUN:'ready_to_run',RUNNING:'running'}}," +
        "runtime:{OnInstalledReason:{CHROME_UPDATE:'chrome_update',INSTALL:'install',SHARED_MODULE_UPDATE:'shared_module_update',UPDATE:'update'}," +
        "OnRestartRequiredReason:{APP_UPDATE:'app_update',OS_UPDATE:'os_update',PERIODIC:'periodic'}," +
        "PlatformArch:{ARM:'arm',ARM64:'arm64',MIPS:'mips',MIPS64:'mips64',X86_32:'x86-32',X86_64:'x86-64'}," +
        "PlatformNaclArch:{ARM:'arm',MIPS:'mips',MIPS64:'mips64',X86_32:'x86-32',X86_64:'x86-64'}," +
        "PlatformOs:{ANDROID:'android',CROS:'cros',LINUX:'linux',MAC:'mac',OPENBSD:'openbsd',WIN:'win'}," +
        "RequestUpdateCheckStatus:{NO_UPDATE:'no_update',THROTTLED:'throttled',UPDATE_AVAILABLE:'update_available'}," +
        "connect:function(){},sendMessage:function(){},id:undefined}," +
        "loadTimes:function(){return{}},csi:function(){return{}}};}}catch(e){}" +

        // ── 7. Battery API 모바일화 ──
        "try{if(navigator.getBattery){navigator.getBattery=()=>Promise.resolve({" +
        "charging:true,chargingTime:0,dischargingTime:Infinity," +
        "level:0.85+Math.random()*0.1," +
        "addEventListener:()=>{},removeEventListener:()=>{}});}}catch(e){}" +

        // ── 8. navigator.userAgentData brands: Android WebView → Google Chrome ──
        "try{if(navigator.userAgentData){" +
        "var _oUAD=navigator.userAgentData;" +
        "var _fB=function(b){return(b.brand==='Android WebView'||b.brand==='\"Android WebView\"')" +
        "?{brand:'Google Chrome',version:b.version}:b;};" +
        "Object.defineProperty(navigator,'userAgentData',{get:function(){return{" +
        "brands:(_oUAD.brands||[]).map(_fB)," +
        "mobile:_oUAD.mobile," +
        "platform:_oUAD.platform," +
        "toJSON:function(){return{brands:(_oUAD.brands||[]).map(_fB),mobile:_oUAD.mobile,platform:_oUAD.platform};}," +
        "getHighEntropyValues:function(h){return _oUAD.getHighEntropyValues(h).then(function(v){" +
        "v.brands=(v.brands||[]).map(_fB);" +
        "if(v.fullVersionList)v.fullVersionList=v.fullVersionList.map(_fB);" +
        "return v;})}" +
        "};},configurable:true});}}catch(e){}" +

        // ── 9. Permissions API 정상화 (WebView에서 NotAllowedError 방지) ──
        "try{if(navigator.permissions){var _oQ=navigator.permissions.query.bind(navigator.permissions);" +
        "navigator.permissions.query=function(d){return _oQ(d).catch(function(){return{state:'prompt',onchange:null};})};}}catch(e){}" +

        // ── 10. Notification API (Chrome에 있고 WebView에 없음 — 탐지 벡터) ──
        "try{if(typeof Notification==='undefined'){window.Notification=function(t,o){this.title=t;this.body=(o&&o.body)||'';};" +
        "Notification.permission='default';Notification.requestPermission=function(cb){var p=Promise.resolve('default');if(cb)cb('default');return p;};" +
        "Notification.maxActions=2;}}catch(e){}" +

        // ── 11. SharedArrayBuffer 존재 확인 (Chrome에서 사용 가능) ──
        "try{if(typeof SharedArrayBuffer==='undefined'){window.SharedArrayBuffer=ArrayBuffer;}}catch(e){}" +

        // ── 12. navigator.deviceMemory (Chrome은 보고, WebView는 undefined일 수 있음) ──
        "try{if(!navigator.deviceMemory){Object.defineProperty(navigator,'deviceMemory',{get:()=>4});}}catch(e){}" +

        // ── 13. navigator.hardwareConcurrency 정상화 ──
        "try{if(!navigator.hardwareConcurrency||navigator.hardwareConcurrency<2){" +
        "Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>8});}}catch(e){}";
    /**
     * 쇼핑 도메인 요청을 HttpURLConnection으로 프록시
     * WebView가 자동 추가하는 X-Requested-With 헤더 제거
     */
    /** WebView UA에서 wv/Version 마커 제거 → Chrome UA 생성 */
    private static String toChromeUA(String webviewUA) {
        return webviewUA
            .replace("; wv)", ")")
            .replaceAll("Version/\\d+\\.\\d+\\s*", "");
    }

    private float cachedDpr = 0;

    /** DPR 캐시 조회 */
    private float getDpr() {
        if (cachedDpr <= 0) {
            String s = evalJSSync("(window.devicePixelRatio||3).toString()", 2000);
            try { cachedDpr = Float.parseFloat(s); } catch (Exception e) { cachedDpr = 3.0f; }
        }
        return cachedDpr;
    }

    /** WebView 물리 픽셀 크기 조회 */
    private int[] getWebViewDimensions() {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        mainHandler.post(() -> {
            if (webView != null) {
                future.complete(new int[]{webView.getWidth(), webView.getHeight()});
            } else {
                future.complete(new int[]{1080, 1920});
            }
        });
        try {
            return future.get(1000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return new int[]{1080, 1920};
        }
    }

    /** 스텔스 스크립트 주입 */
    private void injectStealthScript() {
        evalJSSync(STEALTH_JS, 3000);
    }

    /**
     * 터치 스와이프 제스처로 스크롤 (MotionEvent 기반)
     * window.scrollBy 대신 실제 터치 이벤트 — 봇 감지 우회
     *
     * @param cssPixels 스크롤할 CSS 픽셀 거리
     */
    private void simulateSwipe(int cssPixels) {
        float dpr = getDpr();
        int physicalDistance = (int)(cssPixels * dpr);
        int[] dims = getWebViewDimensions();
        int viewWidth = dims[0];
        int viewHeight = dims[1];

        int steps = 8 + (int)(Math.random() * 8);      // 8-16 중간 포인트
        int stepDelayMs = 12 + (int)(Math.random() * 16); // 12-28ms 간격
        int totalMs = (steps + 2) * stepDelayMs;

        // 랜덤 시작점 (화면 중앙 부근)
        final float startX = viewWidth * 0.3f + (float)(Math.random() * viewWidth * 0.4f);
        final float startY = viewHeight * 0.7f + (float)(Math.random() * viewHeight * 0.1f);
        final float endY = startY - physicalDistance;
        final float xJitter = (float)(Math.random() - 0.5) * 20; // 좌우 흔들림

        // DOWN
        mainHandler.post(() -> {
            if (isWebViewDestroyed()) return;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, startY, 0);
            webView.dispatchTouchEvent(down);
            down.recycle();
        });

        // MOVE events (실제 딜레이로 자연스러운 패턴)
        for (int i = 1; i <= steps; i++) {
            final float progress = (float) i / steps;
            final long delay = (long) i * stepDelayMs;
            mainHandler.postDelayed(() -> {
                if (isWebViewDestroyed()) return;
                long now = SystemClock.uptimeMillis();
                float x = startX + xJitter * progress + (float)(Math.random() - 0.5) * 3;
                float y = startY + (endY - startY) * progress;
                MotionEvent move = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, x, y, 0);
                webView.dispatchTouchEvent(move);
                move.recycle();
            }, delay);
        }

        // UP
        final long upDelay = (long)(steps + 1) * stepDelayMs;
        mainHandler.postDelayed(() -> {
            if (isWebViewDestroyed()) return;
            long now = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP,
                startX + xJitter, endY, 0);
            webView.dispatchTouchEvent(up);
            up.recycle();
        }, upDelay);

        // 제스처 완료 대기
        RandomDelay.sleepBetween(totalMs + 50, totalMs + 200);
    }

    // ── navigate ────────────────────────────────────────

    public StepResult navigate(Step step) {
        // 새 네비게이션 시작 시 쇼핑 도메인 직접 로드 플래그 리셋
        allowShoppingInWebView = false;

        String url = step.getString("url");
        if (url.isEmpty()) return StepResult.fail("navigate: url empty");
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return StepResult.fail("navigate: unsupported scheme: " + url);
        }

        Logger.step(step.getId(), "navigate", url);
        CompletableFuture<Void> future = new CompletableFuture<>();
        long timeout = step.getLong("timeout", 30000);

        mainHandler.post(() -> {
            if (isWebViewDestroyed()) {
                future.completeExceptionally(new IllegalStateException("webview destroyed"));
                return;
            }

            // Chrome-like User-Agent 설정 (wv, Version/4.0 제거)
            String defaultUA = webView.getSettings().getUserAgentString();
            String chromeUA = toChromeUA(defaultUA);
            webView.getSettings().setUserAgentString(chromeUA);

            // UA 캐시 (IO 스레드에서 WebView 접근 불가)
            cachedChromeUA = chromeUA;

            // sec-ch-ua 엔진 레벨 오버라이드 (AndroidX WebKit UserAgentMetadata)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                String chromeVer = "131";
                String chromeFullVer = "131.0.0.0";
                int ci = chromeUA.indexOf("Chrome/");
                if (ci >= 0) {
                    String after = chromeUA.substring(ci + 7);
                    int sp = after.indexOf(' ');
                    if (sp > 0) {
                        chromeFullVer = after.substring(0, sp);
                        int dot = chromeFullVer.indexOf('.');
                        if (dot > 0) chromeVer = chromeFullVer.substring(0, dot);
                    }
                }
                UserAgentMetadata uaMetadata = new UserAgentMetadata.Builder()
                    .setBrandVersionList(Arrays.asList(
                        new UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Not(A:Brand").setMajorVersion("8").setFullVersion("8.0.0.0").build(),
                        new UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Chromium").setMajorVersion(chromeVer).setFullVersion(chromeFullVer).build(),
                        new UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Google Chrome").setMajorVersion(chromeVer).setFullVersion(chromeFullVer).build()
                    ))
                    .setMobile(true)
                    .setPlatform("Android")
                    .setPlatformVersion(Build.VERSION.RELEASE + ".0.0")
                    .setModel(Build.MODEL)
                    .setArchitecture("")
                    .setBitness(0)
                    .setWow64(false)
                    .setFullVersion(chromeFullVer)
                    .build();
                WebSettingsCompat.setUserAgentMetadata(webView.getSettings(), uaMetadata);
                Logger.i("★ Chrome UserAgentMetadata 설정 완료: " + chromeVer + " (" + chromeFullVer + ")");
            } else {
                Logger.w("UserAgentMetadata API 미지원 — sec-ch-ua 오버라이드 불가");
            }

            // X-Requested-With 헤더 제거 (WebView 탐지 핵심 차단)
            // WebView는 자동으로 "X-Requested-With: {packageName}" 헤더 추가 → 봇 탐지
            // 열거값: 0 = NO_HEADER, 1 = APP_PACKAGE_NAME (기본값), 2 = CONSTANT_WEBVIEW
            boolean xrwRemoved = false;

            // 전략 0: setRequestedWithHeaderOriginAllowList (빈 set = XRW 제거)
            // AndroidX WebKit 1.15.0 — feature check 우회하고 직접 호출
            try {
                @SuppressWarnings("deprecation")
                boolean ignored = false; // suppress deprecation for next line
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(
                    webView.getSettings(), java.util.Collections.emptySet());
                xrwRemoved = true;
                Logger.i("★ X-Requested-With 제거 완료 (AllowList 빈 set!)");
            } catch (Exception e) {
                Logger.w("★ setRequestedWithHeaderOriginAllowList 실패: " + e.getMessage());
            }

            // 전략 0b: reflection으로 setRequestedWithHeaderMode 검색 (메서드명이 다를 수 있음)
            if (!xrwRemoved) {
                try {
                    for (java.lang.reflect.Method m : WebSettingsCompat.class.getDeclaredMethods()) {
                        if (m.getName().toLowerCase().contains("requestedwith") &&
                            m.getName().toLowerCase().contains("mode")) {
                            Class<?>[] params = m.getParameterTypes();
                            Logger.i("★ 발견: WebSettingsCompat." + m.getName() +
                                " params=" + java.util.Arrays.toString(params));
                            if (params.length == 2 && params[1] == int.class) {
                                m.setAccessible(true);
                                m.invoke(null, webView.getSettings(), 0); // 0 = NO_HEADER
                                xrwRemoved = true;
                                Logger.i("★ X-Requested-With 제거 완료 (reflection " + m.getName() + ")");
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Logger.w("★ reflection setRequestedWithHeaderMode 실패: " + e.getMessage());
                }
            }

            // 전략 3: AwSettings reflection — 난독화된 메서드 탐색
            if (!xrwRemoved) {
                try {
                    android.webkit.WebSettings ws = webView.getSettings();
                    Object awSettings = null;
                    for (java.lang.reflect.Field f : ws.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        Object inner = f.get(ws);
                        if (inner != null && inner.getClass().getSimpleName().contains("AwSettings")) {
                            awSettings = inner;
                            break;
                        }
                    }

                    if (awSettings != null) {
                        // 3a: 정확한 메서드명
                        try {
                            java.lang.reflect.Method m = awSettings.getClass()
                                    .getMethod("setRequestedWithHeaderMode", int.class);
                            m.invoke(awSettings, 0); // 0 = NO_HEADER
                            xrwRemoved = true;
                            Logger.i("★ X-Requested-With 제거 완료 (AwSettings.setRequestedWithHeaderMode)");
                        } catch (NoSuchMethodException e1) {
                            // 3b: 난독화 대응 — 마커 값으로 메서드↔필드 매핑 추적
                            // 핵심: requestedWithHeaderMode 기본값 = 1 (APP_PACKAGE_NAME)
                            //   0 = NO_HEADER, 1 = APP_PACKAGE_NAME, 2 = CONSTANT_WEBVIEW
                            Logger.i("★ 난독화 메서드 매핑 시작 (마커 99999)");

                            // 현재 Integer 필드 값 저장
                            java.util.Map<String, Object> origValues = new java.util.LinkedHashMap<>();
                            for (java.lang.reflect.Field f : awSettings.getClass().getDeclaredFields()) {
                                f.setAccessible(true);
                                try { origValues.put(f.getName(), f.get(awSettings)); } catch (Exception ignored) {}
                            }

                            // 모든 int(1개 파라미터) 메서드 → 필드 매핑 수집
                            java.util.Map<String, String> methodToField = new java.util.LinkedHashMap<>();
                            java.util.Map<String, Integer> methodOrigVal = new java.util.LinkedHashMap<>();
                            for (java.lang.reflect.Method mt : awSettings.getClass().getDeclaredMethods()) {
                                Class<?>[] params = mt.getParameterTypes();
                                if (params.length != 1 || params[0] != int.class) continue;
                                mt.setAccessible(true);
                                try {
                                    mt.invoke(awSettings, 99999);
                                    for (java.lang.reflect.Field f : awSettings.getClass().getDeclaredFields()) {
                                        f.setAccessible(true);
                                        Object val = f.get(awSettings);
                                        if (val instanceof Integer && (Integer) val == 99999) {
                                            Object origVal = origValues.get(f.getName());
                                            int ov = (origVal instanceof Integer) ? (Integer) origVal : -999;
                                            methodToField.put(mt.getName(), f.getName());
                                            methodOrigVal.put(mt.getName(), ov);
                                            Logger.i("★ 매핑: " + mt.getName() + " → " + f.getName() + " (기본값=" + ov + ")");
                                            f.set(awSettings, origVal); // 복원
                                            break;
                                        }
                                    }
                                } catch (Exception ex) {
                                    Logger.w("매핑 실패: " + mt.getName() + " → " + ex.getMessage());
                                }
                            }

                            // ★ 핵심: 기본값=1 메서드 찾기 → requestedWithHeaderMode 후보
                            // (기본값 1 = APP_PACKAGE_NAME → 0 = NO_HEADER 로 변경)
                            java.util.List<String> oneMethods = new java.util.ArrayList<>();
                            for (java.util.Map.Entry<String, Integer> entry : methodOrigVal.entrySet()) {
                                if (entry.getValue() == 1) {
                                    oneMethods.add(entry.getKey());
                                }
                            }

                            Logger.i("★ 기본값=1 메서드 (XRW 후보): " + oneMethods);
                            for (String methodName : oneMethods) {
                                try {
                                    java.lang.reflect.Method target = awSettings.getClass()
                                            .getDeclaredMethod(methodName, int.class);
                                    target.setAccessible(true);
                                    target.invoke(awSettings, 0); // 0 = NO_HEADER
                                    Logger.i("★ " + methodName + "(0) → NO_HEADER 설정 완료");
                                } catch (Exception ex) {
                                    Logger.w("메서드 호출 실패: " + methodName + " → " + ex.getMessage());
                                }
                            }
                            if (!oneMethods.isEmpty()) {
                                xrwRemoved = true;
                                Logger.i("★ X-Requested-With 제거 완료 (기본값=1 메서드에 0 설정)");
                            }

                            // 안전장치: 알려진 세팅 명시적 복원
                            webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
                            webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

                            // 3c: Context.getPackageName() 오버라이드
                            // C++에서 JNI로 Java의 mContext.getPackageName() 호출 → 빈값 반환
                            if (!xrwRemoved) {
                                try {
                                    for (java.lang.reflect.Field f : awSettings.getClass().getDeclaredFields()) {
                                        f.setAccessible(true);
                                        Object val = f.get(awSettings);
                                        if (val instanceof android.content.Context) {
                                            android.content.ContextWrapper fakeCtx =
                                                new android.content.ContextWrapper((android.content.Context) val) {
                                                    @Override public String getPackageName() { return ""; }
                                                };
                                            f.set(awSettings, fakeCtx);
                                            xrwRemoved = true;
                                            Logger.i("★ X-Requested-With 제거: Context→빈 packageName (" + f.getName() + ")");
                                            break;
                                        }
                                    }
                                } catch (Exception ctxEx) {
                                    Logger.w("Context 교체 실패: " + ctxEx.getMessage());
                                }
                            }

                            // 3d: package name 문자열 필드 검색 (최후 fallback)
                            if (!xrwRemoved) {
                                for (java.lang.reflect.Field f : awSettings.getClass().getDeclaredFields()) {
                                    try {
                                        f.setAccessible(true);
                                        Object val = f.get(awSettings);
                                        if (val instanceof String && webView.getContext().getPackageName().equals(val)) {
                                            f.set(awSettings, "");
                                            xrwRemoved = true;
                                            Logger.i("★ X-Requested-With 제거: field→빈값 (" + f.getName() + ")");
                                            break;
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    if (!xrwRemoved) {
                        Logger.w("X-Requested-With 제거 불가 — 모든 전략 실패");
                    }
                } catch (Exception reflectEx) {
                    Logger.w("X-Requested-With reflection 실패: " + reflectEx.getMessage());
                }
            }

            // 네비게이션 타이밍 기록
            if (fingerprintCollector != null) {
                fingerprintCollector.recordNavigationStart();
            }

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String url = request.getUrl() != null ? request.getUrl().toString() : "";
                    // WebView 직접 로드 모드 — 쇼핑 도메인도 WebView에서 처리
                    if (allowShoppingInWebView) return false;
                    // 상품페이지 리디렉션 차단 — Chrome Intent가 처리
                    if (url.contains("msearch.shopping.naver.com")
                            || url.contains("smartstore.naver.com")
                            || url.contains("brand.naver.com")) {
                        Logger.i("★ 상품페이지 리디렉션 차단: " + url.substring(0, Math.min(80, url.length())));
                        return true; // WebView 로딩 차단
                    }
                    return false;
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (request == null) return null;
                    String reqUrl = request.getUrl() != null ? request.getUrl().toString() : "";

                    // 쇼핑 도메인 분기
                    if (request.isForMainFrame() && isShoppingDomain(reqUrl)) {
                        if (allowShoppingInWebView) {
                            // WebView 네이티브 모드 — ncpt JS 챌린지 실행을 위해 WebView가 직접 처리
                            Logger.i("★ 상품페이지 → WebView 네이티브: " + reqUrl.substring(0, Math.min(80, reqUrl.length())));
                        } else if (reqUrl.contains("msearch.shopping.naver.com")
                                || reqUrl.contains("smartstore.naver.com")
                                || reqUrl.contains("brand.naver.com")) {
                            // 기본 모드: WebView는 빈 페이지 (490/418 방지)
                            Logger.i("★ 상품페이지 차단 → WebView 빈 페이지: " + reqUrl.substring(0, Math.min(80, reqUrl.length())));
                            return new WebResourceResponse("text/html", "UTF-8",
                                new java.io.ByteArrayInputStream("<html><body></body></html>".getBytes()));
                        } else {
                            // bridge (cr3.shopping) 등은 네이티브 통과 (트래킹 클릭)
                            Logger.i("★ 쇼핑 bridge → 네이티브 WebView: " + reqUrl.substring(0, Math.min(80, reqUrl.length())));
                        }
                    }

                    // 네이버 메인프레임 요청 헤더 로깅 (디버그)
                    if (request.isForMainFrame() && reqUrl.contains("naver.com")) {
                        Map<String, String> reqHeaders = request.getRequestHeaders();
                        Logger.i("══════ REQUEST HEADERS ══════");
                        Logger.i("URL: " + reqUrl);
                        if (reqHeaders != null) {
                            for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                                Logger.i("  " + entry.getKey() + ": " + entry.getValue());
                            }
                        }
                        Logger.i("══════ END HEADERS ══════");
                    }
                    return null;
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    // 봇 감지 우회: 페이지 스크립트보다 먼저 스텔스 주입
                    view.evaluateJavascript(STEALTH_JS, null);
                }

                @Override
                public void onPageFinished(WebView view, String loadedUrl) {
                    // 네비게이션 완료 기록 + 성공 응답 기록
                    if (fingerprintCollector != null) {
                        fingerprintCollector.recordNavigationEnd();
                        fingerprintCollector.recordHttpSuccess();
                    }


                    if (!future.isDone()) {
                        future.complete(null);
                    }
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request,
                        WebResourceResponse errorResponse) {
                    if (request != null && request.isForMainFrame()) {
                        int statusCode = errorResponse.getStatusCode();
                        Map<String, String> respHeaders = errorResponse.getResponseHeaders();
                        String reqUrl = request.getUrl() != null ? request.getUrl().toString() : "";
                        Logger.w("HTTP error: " + statusCode + " URL: " + reqUrl);

                        // 상품페이지 HTTP 상태코드 기록 (handleMidFound 연동)
                        if (allowShoppingInWebView && isShoppingDomain(reqUrl)) {
                            lastProductPageStatus = statusCode;
                            Logger.w("★ 상품페이지 HTTP " + statusCode + " 감지!");
                        }

                        // 차단 시 응답 헤더도 출력
                        if (respHeaders != null) {
                            Logger.w("══════ RESPONSE HEADERS (HTTP " + statusCode + ") ══════");
                            for (Map.Entry<String, String> entry : respHeaders.entrySet()) {
                                Logger.w("  " + entry.getKey() + ": " + entry.getValue());
                            }
                            Logger.w("══════ END RESPONSE ══════");
                        }

                        // FingerprintCollector에 기록
                        if (fingerprintCollector != null) {
                            fingerprintCollector.recordHttpResponse(statusCode, respHeaders, reqUrl);
                        }
                    }
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request != null && request.isForMainFrame() && !future.isDone()) {
                        CharSequence description = error != null ? error.getDescription() : null;
                        future.completeExceptionally(
                                new IllegalStateException(description != null ? description.toString() : "load error"));
                    }
                }
            });

            webView.loadUrl(url);
        });

        try {
            future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return StepResult.fail("navigate failed: " + e.getMessage());
        }

        // domcontentloaded 대기
        RandomDelay.sleepBetween(300, 700);
        return StepResult.success();
    }

    // ── delay ───────────────────────────────────────────

    public StepResult delay(Step step) {
        int[] ms = step.getIntRange("ms", new int[]{1000, 2000});
        int wait = RandomDelay.between(ms[0], ms[1]);
        Logger.step(step.getId(), "delay", wait + "ms");
        RandomDelay.sleep(wait);
        return StepResult.success();
    }

    // ── tap ──────────────────────────────────────────────

    public StepResult tap(Step step) {
        String selector = step.getString("selector");
        String fallback = step.getString("fallback", "");
        String fallbackText = step.getString("fallbackText", "");
        boolean removeTarget = step.getBoolean("removeTarget", false);
        boolean waitNav = step.getBoolean("waitNav", false);

        Logger.step(step.getId(), "tap", selector);

        // JS로 요소 좌표 가져오기
        String js = buildTapJS(selector, fallback, fallbackText, removeTarget);
        String result = evalJSSync(js, step.getLong("timeout", 10000));

        if (result == null || result.equals("null") || result.isEmpty()) {
            return StepResult.fail("tap: element not found: " + selector);
        }

        try {
            JSONObject rect = new JSONObject(result);
            float x = (float) rect.getDouble("x");
            float y = (float) rect.getDouble("y");

            // MotionEvent 터치 시뮬레이션
            simulateTouch(x, y);

            if (waitNav) {
                long timeout = step.getLong("timeout", 20000);
                RandomDelay.sleepBetween(2000, 3000);
            }
            return StepResult.success();

        } catch (Exception e) {
            return StepResult.fail("tap parse error: " + e.getMessage());
        }
    }

    private String buildTapJS(String selector, String fallback, String fallbackText, boolean removeTarget) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){");
        sb.append("var el=document.querySelector(").append(jsQuote(selector)).append(");");
        if (!fallback.isEmpty()) {
            sb.append("if(!el) el=document.querySelector(").append(jsQuote(fallback)).append(");");
        }
        if (!fallbackText.isEmpty()) {
            sb.append("if(!el){var links=document.querySelectorAll('a');");
            sb.append("for(var i=0;i<links.length;i++){");
            sb.append("if(links[i].textContent.trim().startsWith(").append(jsQuote(fallbackText)).append(")){el=links[i];break;}}}");
        }
        sb.append("if(!el) return 'null';");
        if (removeTarget) {
            sb.append("el.removeAttribute('target');");
        }
        sb.append("var r=el.getBoundingClientRect();");
        sb.append("var dpr=window.devicePixelRatio||1;");
        sb.append("return JSON.stringify({x:(r.x+r.width/2)*dpr,y:(r.y+r.height/2)*dpr});");
        sb.append("})()");
        return sb.toString();
    }

    // ── humanType ───────────────────────────────────────

    public StepResult humanType(Step step) {
        String selector = step.getString("selector");
        String fallback = step.getString("fallback", "");
        String text = step.getString("text");
        boolean clearFirst = step.getBoolean("clearFirst", false);
        int[] charDelay = step.getIntRange("charDelay", new int[]{60, 140});
        int[] gapDelay = step.getIntRange("gapDelay", new int[]{20, 70});

        Logger.step(step.getId(), "humanType", text.length() > 30 ? text.substring(0, 30) + "..." : text);

        // 요소 찾기 + 포커스
        String selectorLiteral = jsQuote(selector);
        String fallbackLiteral = jsQuote(fallback);
        String findJS = "(function(){var el=document.querySelector(" + selectorLiteral + ");" +
                "if(!el && " + fallbackLiteral + ") el=document.querySelector(" + fallbackLiteral + ");" +
                "if(!el) return 'not_found';" +
                "el.click();el.focus();return 'found';})()";

        String found = evalJSSync(findJS, 5000);
        if (!"found".equals(found)) {
            return StepResult.fail("humanType: element not found: " + selector);
        }

        RandomDelay.sleepBetween(200, 400);

        // clearFirst
        if (clearFirst) {
            evalJSSync(
                    "(function(){var el=document.querySelector(" + selectorLiteral + ");" +
                    "if(el){el.value='';el.dispatchEvent(new Event('input',{bubbles:true}));}})()",
                    3000);
            RandomDelay.sleepBetween(100, 200);
        }

        // 한 글자씩 타이핑 (dispatchEvent로 실제 키 이벤트)
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String cLiteral = jsQuote(String.valueOf(c));
            String typeJS = "(function(){var el=document.querySelector(" + selectorLiteral + ");" +
                    "if(el){el.value+=" + cLiteral + ";" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "el.dispatchEvent(new KeyboardEvent('keydown',{key:" + cLiteral + ",bubbles:true}));" +
                    "el.dispatchEvent(new KeyboardEvent('keyup',{key:" + cLiteral + ",bubbles:true}));}})()";

            evalJSSync(typeJS, 2000);
            RandomDelay.sleep(RandomDelay.between(charDelay[0], charDelay[1]));
            if (i < text.length() - 1) {
                RandomDelay.sleep(RandomDelay.between(gapDelay[0], gapDelay[1]));
            }
        }

        return StepResult.success();
    }

    // ── press ───────────────────────────────────────────

    public StepResult press(Step step) {
        String key = step.getString("key");
        boolean waitNav = step.getBoolean("waitNav", false);
        Logger.step(step.getId(), "press", key);

        String keyLiteral = jsQuote(key);
        String js = "document.dispatchEvent(new KeyboardEvent('keydown',{key:" + keyLiteral +
                ",code:" + keyLiteral + ",bubbles:true}));" +
                "document.dispatchEvent(new KeyboardEvent('keyup',{key:" + keyLiteral +
                ",code:" + keyLiteral + ",bubbles:true}));";

        // Enter는 form submit 시뮬레이션
        if ("Enter".equals(key)) {
            js = "(function(){" +
                 "var active=document.activeElement;" +
                 "if(active&&active.form){active.form.submit();}" +
                 "else{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));}" +
                 "})()";
        }

        evalJSSync(js, 3000);

        if (waitNav) {
            RandomDelay.sleepBetween(2000, 3500);
        }
        return StepResult.success();
    }

    // ── scroll ──────────────────────────────────────────

    public StepResult scroll(Step step) {
        int distance = step.getInt("distance", 2000);
        int[] stepRange = step.getIntRange("stepRange", new int[]{150, 350});
        int[] stepDelay = step.getIntRange("stepDelay", new int[]{200, 400});

        Logger.step(step.getId(), "scroll", distance + "px");

        int scrolled = 0;
        while (scrolled < distance) {
            int px = RandomDelay.between(stepRange[0], stepRange[1]);
            simulateSwipe(px);
            scrolled += px;
            RandomDelay.sleep(RandomDelay.between(stepDelay[0], stepDelay[1]));
        }
        return StepResult.success();
    }

    // ── scrollTo ────────────────────────────────────────

    public StepResult scrollTo(Step step) {
        int y = step.getInt("y", 0);
        Logger.step(step.getId(), "scrollTo", "y=" + y);
        evalJSSync(String.format("window.scrollTo(0,%d)", y), 2000);
        RandomDelay.sleepBetween(500, 800);
        return StepResult.success();
    }

    // ── checkStatus ─────────────────────────────────────

    public StepResult checkStatus(Step step) {
        Logger.step(step.getId(), "checkStatus");

        // unified-runner 동기화: 차단 패턴 (쇼핑 제한 포함)
        String js = "(function(){" +
                "var t=document.body?document.body.innerText:'';" +
                "if(t.includes('비정상적인 접근')||t.includes('자동화된 접근')" +
                "||t.includes('접근이 제한')||t.includes('잠시 후 다시')" +
                "||t.includes('비정상적인 요청')||t.includes('이용이 제한')" +
                "||t.includes('일시적으로 제한')||t.includes('쇼핑 제한')" +
                "||t.includes('검색결과가 제한')) return 'blocked';" +
                "if(t.includes('자동입력방지')||(t.includes('보안 확인')&&t.includes('영수증 번호'))) return 'captcha';" +
                "return 'ok';})()";

        String status = evalJSSync(js, 5000);
        Logger.i("checkStatus result: " + status);

        if ("blocked".equals(status)) {
            // 차단 시 페이지 URL + 텍스트 일부 기록 (ML 분석용)
            if (fingerprintCollector != null) {
                String pageUrl = evalJSSync("(function(){return window.location.href;})()", 2000);
                String pageText = evalJSSync(
                        "(function(){return (document.body?document.body.innerText:'').substring(0,300);})()", 2000);
                fingerprintCollector.recordBlockedPage(pageUrl, pageText);
            }

            String onBlocked = step.getString("onBlocked", "");
            if ("abort".equals(onBlocked)) return StepResult.abort("Page blocked");
            return StepResult.blocked();
        }
        if ("captcha".equals(status)) {
            return StepResult.captcha();
        }
        return StepResult.success();
    }

    // ── clickProduct ────────────────────────────────────

    public StepResult clickProduct(Step step) {
        int index = step.getInt("index", 2);
        String mid = step.getString("mid", "");
        String exclude = step.getString("excludePattern", "lst*(A|P|D)");
        boolean useTouch = step.getBoolean("useTouchscreen", true);

        Logger.step(step.getId(), "clickProduct", "#" + index + " mid=" + mid);

        // N번째 상품 찾기
        String findJS = String.format(
                "(function(){var list=[];var seq=0;" +
                "var re=new RegExp(%s);" +
                "document.querySelectorAll('a[data-shp-contents-id]').forEach(function(a){" +
                "if(re.test(a.getAttribute('data-shp-inventory')||''))return;" +
                "seq++;list.push({mid:a.getAttribute('data-shp-contents-id')||'',i:seq});});" +
                "var t=list.find(function(p){return p.i===%d;});" +
                "if(!t)return 'null';" +
                "var el=document.querySelector('a[data-shp-contents-id=\"'+t.mid+'\"]');" +
                "if(!el)return 'null';" +
                "el.scrollIntoView({block:'center',behavior:'smooth'});" +
                "var r=el.getBoundingClientRect();" +
                "var dpr=window.devicePixelRatio||1;" +
                "return JSON.stringify({x:(r.x+r.width/2)*dpr,y:(r.y+r.height/2)*dpr,mid:t.mid});})()",
                jsQuote(exclude), index);

        // 스크롤 후 대기
        RandomDelay.sleepBetween(500, 1000);

        String result = evalJSSync(findJS, 10000);
        if (result == null || result.equals("null") || result.isEmpty()) {
            return StepResult.fail("clickProduct: product #" + index + " not found");
        }

        try {
            JSONObject pos = new JSONObject(result);
            float x = (float) pos.getDouble("x");
            float y = (float) pos.getDouble("y");

            RandomDelay.sleepBetween(300, 700);
            simulateTouch(x, y);

            // 네비게이션 대기 + 스텔스 주입 (3-6초, unified-runner 동일)
            RandomDelay.sleepBetween(3000, 6000);
            injectStealthScript();
            return StepResult.success();

        } catch (Exception e) {
            return StepResult.fail("clickProduct error: " + e.getMessage());
        }
    }

    // ── dwell (체류) ────────────────────────────────────

    public StepResult dwell(Step step) {
        int[] ms = step.getIntRange("ms", new int[]{3000, 6000});
        int scrollDist = step.getInt("scrollDist", 1500);
        int[] scrollCount = step.getIntRange("scrollCount", new int[]{1, 2});

        // 최소 체류 3초 강제 (시나리오 값이 너무 작으면 보정)
        if (ms[0] < 3000) ms[0] = 3000;
        if (ms[1] < 5000) ms[1] = 5000;

        int dwellTime = RandomDelay.between(ms[0], ms[1]);
        Logger.step(step.getId(), "dwell", dwellTime + "ms");

        // 디버그: 체류 시작 시 URL 확인
        String dwellUrl = evalJSSync("(function(){return window.location.href;})()", 3000);
        Logger.i("dwell URL: " + dwellUrl);

        long start = System.currentTimeMillis();

        // 스크롤
        int count = RandomDelay.between(scrollCount[0], scrollCount[1]);
        int perScroll = scrollDist / Math.max(count, 1);
        for (int i = 0; i < count; i++) {
            int px = RandomDelay.between(perScroll - 50, perScroll + 50);
            simulateSwipe(px);
            RandomDelay.sleepBetween(800, 1500);
        }

        // 남은 시간 대기
        long elapsed = System.currentTimeMillis() - start;
        long remaining = dwellTime - elapsed;
        if (remaining > 0) {
            RandomDelay.sleep((int) remaining);
        }

        return StepResult.success();
    }

    // ── report ──────────────────────────────────────────

    public StepResult report(Step step) {
        String status = step.getString("status", "completed");
        Logger.step(step.getId(), "report", status);
        // TaskManager에서 처리 — 여기서는 status 전달만
        return StepResult.success();
    }

    // ── log ─────────────────────────────────────────────

    public StepResult log(Step step) {
        String message = step.getString("message", "");
        Logger.i("[LOG] " + message);
        return StepResult.success();
    }

    // ── runScript (JS 주입) ─────────────────────────────

    public StepResult runScript(Step step, String scriptContent) {
        Logger.step(step.getId(), "runScript", step.getString("scriptName"));
        String result = evalJSSync(scriptContent, step.getLong("timeout", 30000));
        if (result != null && result.startsWith("ERROR:")) {
            return StepResult.fail(result);
        }
        return StepResult.success();
    }

    // ── evalJS (인라인 JS + 변수 주입) ───────────────────

    public StepResult evalJS(Step step) {
        String script = step.getString("script", "");
        if (script.isEmpty()) return StepResult.fail("evalJS: empty script");

        Logger.step(step.getId(), "evalJS");

        // vars 필드가 있으면 JSON으로 직렬화하여 __V 변수로 주입
        JSONObject varsObj = step.getRaw().optJSONObject("vars");
        if (varsObj != null) {
            // JSONObject.toString()은 올바르게 이스케이프된 JSON 출력
            script = "var __V=" + varsObj.toString() + ";" + script;
        }

        String result = evalJSSync(script, step.getLong("timeout", 30000));
        if (result != null && result.startsWith("ERROR:")) {
            return StepResult.fail(result);
        }
        return StepResult.success();
    }

    // ── findMid (3전략 MID 탐색 + 가격비교 페이지네이션) ───────────────

    public StepResult findMid(Step step) {
        String mid = step.getString("mid", "");
        int maxScroll = step.getInt("maxScroll", 10);
        int maxPages = step.getInt("maxPages", 5);
        if (mid.isEmpty()) return StepResult.fail("findMid: mid empty");

        Logger.step(step.getId(), "findMid", "mid=" + mid);

        // 3전략 MID 탐색 JS — 찾기만 (클릭 안 함)
        String midLiteral = mid.replace("'", "\\'");
        String findJS =
            "(function(){var mid='" + midLiteral + "';" +
            "var a1=document.querySelector('a[href*=\"nv_mid='+mid+'\"]');" +
            "if(a1) return JSON.stringify({s:1,href:a1.href});" +
            "var a2=document.querySelector('a[href*=\"/products/'+mid+'\"]');" +
            "if(a2) return JSON.stringify({s:2,href:a2.href});" +
            "var c=document.querySelector('[id=\"nstore_productId_'+mid+'\"]');" +
            "if(c){var a3=c.previousElementSibling;" +
            "while(a3&&a3.tagName!=='A')a3=a3.previousElementSibling;" +
            "if(!a3)a3=c.closest('a');" +
            "if(a3) return JSON.stringify({s:3,href:a3.href});}" +
            "return 'not_found';})()";

        // MID 찾으면 target 제거 + JS click으로 네비게이션
        String clickMidJS =
            "(function(){var mid='" + midLiteral + "';" +
            "var el=document.querySelector('a[href*=\"nv_mid='+mid+'\"]');" +
            "if(!el) el=document.querySelector('a[href*=\"/products/'+mid+'\"]');" +
            "if(!el){var c=document.querySelector('[id=\"nstore_productId_'+mid+'\"]');" +
            "if(c){el=c.previousElementSibling;while(el&&el.tagName!=='A')el=el.previousElementSibling;" +
            "if(!el)el=c.closest('a');}}" +
            "if(!el) return 'not_found';" +
            "el.removeAttribute('target');" +
            "el.scrollIntoView({block:'center',behavior:'smooth'});" +
            "el.click();" +
            "return 'clicked';})()";

        // "다음 페이지" 버튼 JS click (좌표 대신 직접 클릭)
        String nextPageClickJS =
            "(function(){" +
            "var btns=document.querySelectorAll('button');" +
            "for(var i=0;i<btns.length;i++){" +
            "  if(btns[i].disabled)continue;" +
            "  var spans=btns[i].querySelectorAll('span');" +
            "  for(var j=0;j<spans.length;j++){" +
            "    if(spans[j].textContent.trim()==='다음 페이지'){" +
            "      btns[i].scrollIntoView({block:'center',behavior:'smooth'});" +
            "      btns[i].click();" +
            "      return 'clicked';" +
            "    }" +
            "  }" +
            "}" +
            "return 'not_found';})()";

        // 디버그: 페이지에서 발견된 MID 목록 출력
        String debugMidsJS =
            "(function(){var mids=[];" +
            "document.querySelectorAll('a[href*=\"nv_mid=\"]').forEach(function(a){" +
            "var m=a.getAttribute('href').match(/nv_mid=(\\d+)/);if(m)mids.push(m[1]);});" +
            "document.querySelectorAll('a[href*=\"/products/\"]').forEach(function(a){" +
            "var m=a.getAttribute('href').match(/\\/products\\/(\\d+)/);if(m)mids.push(m[1]);});" +
            "return mids.slice(0,10).join(',');})()";

        // ── STEP 1: 전체 페이지 스크롤하며 MID 탐색 (1회만) ──
        for (int i = 0; i < maxScroll; i++) {
            if (i > 0) {
                Logger.i("findMid: scroll " + (i + 1) + "/" + maxScroll);
            }

            RandomDelay.sleepBetween(300, 500);

            String result = evalJSSync(findJS, 5000);
            if (result != null && !result.equals("not_found") && !result.equals("null") && !result.isEmpty()) {
                return handleMidFound(result, clickMidJS, 1);
            }

            // 스크롤 다운 (터치 스와이프)
            int scrollPx = RandomDelay.between(400, 600);
            simulateSwipe(scrollPx);
            RandomDelay.sleepBetween(300, 800);

            // 스크롤 끝 감지
            if (i > 3) {
                String heightCheck = evalJSSync(
                    "(function(){var h=document.body.scrollHeight;var y=window.scrollY+window.innerHeight;return (y>=h-50)?'end':'more';})()",
                    2000);
                if ("end".equals(heightCheck)) {
                    Logger.i("findMid: 스크롤 끝");
                    break;
                }
            }
        }

        // ── STEP 2: 가격비교 "다음 페이지" 버튼으로 2~5페이지 탐색 ──
        // (스크롤 없이 버튼만 클릭 — 가격비교 컴포넌트 내에서만 변경됨)
        String nextResult = evalJSSync(findJS, 3000);
        // 먼저 페이지네이션 존재 확인
        String paginationCheck = evalJSSync(
            "(function(){var btns=document.querySelectorAll('button');" +
            "for(var i=0;i<btns.length;i++){if(btns[i].disabled)continue;" +
            "var spans=btns[i].querySelectorAll('span');" +
            "for(var j=0;j<spans.length;j++){if(spans[j].textContent.trim()==='다음 페이지')return 'found';}}" +
            "return 'not_found';})()", 3000);
        boolean hasPagination = "found".equals(paginationCheck);

        if (hasPagination) {
            Logger.i("findMid: 가격비교 페이지네이션 발견 — 최대 " + maxPages + "페이지 탐색");

            for (int pg = 2; pg <= maxPages; pg++) {
                // "다음 페이지" 버튼 JS click
                String clickResult = evalJSSync(nextPageClickJS, 3000);
                if (!"clicked".equals(clickResult)) {
                    Logger.i("findMid: 가격비교 " + pg + "페이지 — 마지막 페이지");
                    break;
                }
                Logger.i("findMid: 가격비교 " + pg + "페이지로 이동");
                RandomDelay.sleepBetween(1500, 2500);

                // 가격비교 컴포넌트 내에서 MID 체크 (스크롤 불필요)
                String result = evalJSSync(findJS, 5000);
                if (result != null && !result.equals("not_found") && !result.equals("null") && !result.isEmpty()) {
                    return handleMidFound(result, clickMidJS, pg);
                }
            }
        } else {
            Logger.i("findMid: 가격비교 페이지네이션 없음");
        }

        // 디버그: 실패 시 페이지 상태 진단
        String foundMids = evalJSSync(debugMidsJS, 3000);
        if (foundMids != null && !foundMids.isEmpty()) {
            Logger.w("findMid: 페이지 MID 목록: " + foundMids);
        } else {
            Logger.w("findMid: 페이지에 MID 링크 없음");
        }
        // 페이지 제목 + 본문 일부 로깅 (쇼핑 제한 여부 확인)
        String pageInfo = evalJSSync(
            "(function(){var t=document.title||'';" +
            "var b=(document.body?document.body.innerText:'').substring(0,200);" +
            "return t+'|||'+b;})()", 3000);
        if (pageInfo != null) {
            Logger.w("findMid: 페이지 상태: " + pageInfo);
        }

        // ── STEP 3: 폴백 — 원본 키워드로 네이버 직접 검색 후 재시도 ──
        if (!taskKeyword.isEmpty()) {
            Logger.i("findMid: ★ 폴백: 원본 키워드로 직접 검색 → " + taskKeyword);
            try {
                String encodedKw = java.net.URLEncoder.encode(taskKeyword, "UTF-8");
                String directSearchUrl = "https://m.search.naver.com/search.naver?where=m&sm=mtp_sug.top&query=" + encodedKw;
                evalJSSync("window.location.href='" + directSearchUrl + "'", 5000);
                RandomDelay.sleepBetween(4000, 6000);
                injectStealthScript();

                // 폴백 검색에서 MID 찾기 (스크롤 + 페이지네이션)
                for (int i = 0; i < maxScroll; i++) {
                    RandomDelay.sleepBetween(300, 500);
                    String result = evalJSSync(findJS, 5000);
                    if (result != null && !result.equals("not_found") && !result.equals("null") && !result.isEmpty()) {
                        Logger.i("findMid: ★ 폴백 검색에서 MID 발견! ★");
                        return handleMidFound(result, clickMidJS, 1);
                    }
                    int scrollPx = RandomDelay.between(400, 600);
                    simulateSwipe(scrollPx);
                    RandomDelay.sleepBetween(300, 800);
                    if (i > 3) {
                        String heightCheck = evalJSSync(
                            "(function(){var h=document.body.scrollHeight;var y=window.scrollY+window.innerHeight;return (y>=h-50)?'end':'more';})()",
                            2000);
                        if ("end".equals(heightCheck)) break;
                    }
                }

                // 폴백에서도 페이지네이션 시도
                String fbPaginationCheck = evalJSSync(
                    "(function(){var btns=document.querySelectorAll('button');" +
                    "for(var i=0;i<btns.length;i++){if(btns[i].disabled)continue;" +
                    "var spans=btns[i].querySelectorAll('span');" +
                    "for(var j=0;j<spans.length;j++){if(spans[j].textContent.trim()==='다음 페이지')return 'found';}}" +
                    "return 'not_found';})()", 3000);
                if ("found".equals(fbPaginationCheck)) {
                    Logger.i("findMid: 폴백 가격비교 페이지네이션 발견");
                    for (int pg = 2; pg <= maxPages; pg++) {
                        String clickResult = evalJSSync(nextPageClickJS, 3000);
                        if (!"clicked".equals(clickResult)) break;
                        Logger.i("findMid: 폴백 가격비교 " + pg + "페이지");
                        RandomDelay.sleepBetween(1500, 2500);
                        String result = evalJSSync(findJS, 5000);
                        if (result != null && !result.equals("not_found") && !result.equals("null") && !result.isEmpty()) {
                            Logger.i("findMid: ★ 폴백 페이지네이션에서 MID 발견! ★");
                            return handleMidFound(result, clickMidJS, pg);
                        }
                    }
                }

                Logger.w("findMid: 폴백 검색에서도 MID 미발견");
            } catch (Exception e) {
                Logger.w("findMid: 폴백 실패: " + e.getMessage());
            }
        }

        return StepResult.fail("findMid: MID not found after " + maxPages + " pages + fallback");
    }

    /** MID 발견 시 WebView에서 직접 상품페이지 로드 (Chrome/145 UA + XRW 제거) */
    private StepResult handleMidFound(String findResult, String clickMidJS, int page) {
        try {
            JSONObject info = new JSONObject(findResult);
            int strategy = info.optInt("s", 0);
            String href = info.optString("href", "");
            Logger.i("findMid: MID 발견 (전략 " + strategy + ", p" + page + ") → " + href);

            // href 또는 taskMid에서 MID 추출
            String mid = "";
            if (href.contains("nv_mid=")) {
                int idx = href.indexOf("nv_mid=") + 7;
                int end = href.indexOf("&", idx);
                mid = end > 0 ? href.substring(idx, end) : href.substring(idx);
            }
            if (mid.isEmpty()) mid = taskMid;

            if (mid.isEmpty()) {
                Logger.e("findMid: MID 추출 실패");
                return StepResult.fail("findMid: MID 추출 실패");
            }

            // ── 전략: WebView 자연 리디렉트 + ncpt JS 챌린지 통과 ──
            // 490 = nfront JavaScript 챌린지 (ncpt.naver.com)
            // WebView에서 JS 실행하면 챌린지 자동 통과 → 쿠키 설정 → 실제 페이지 리디렉트
            Logger.i("findMid: ★ WebView 자연 리디렉트 + ncpt JS 챌린지 방식 시도");

            // 쇼핑 도메인 차단 해제
            allowShoppingInWebView = true;
            lastProductPageStatus = 0;

            // MID 링크 클릭 → bridge → smartstore (490 → ncpt JS 챌린지)
            RandomDelay.sleepBetween(500, 1000);
            String clickResult = evalJSSync(clickMidJS, 5000);
            if (!"clicked".equals(clickResult)) {
                allowShoppingInWebView = false;
                Logger.w("findMid: MID 클릭 실패: " + clickResult);
                return StepResult.fail("findMid: MID 클릭 실패");
            }

            // ── Phase 1: 리디렉트 대기 (bridge → smartstore) ──
            Logger.i("findMid: 리디렉트 대기 (bridge → smartstore)...");
            RandomDelay.sleepBetween(3000, 5000);

            // 현재 URL 확인
            String currentUrl = evalJSSync("(function(){return window.location.href;})()", 3000);
            Logger.i("findMid: Phase 1 URL: " + (currentUrl != null ? currentUrl.substring(0, Math.min(80, currentUrl.length())) : "null"));

            // 스텔스 스크립트 주입 (ncpt 챌린지 전에)
            injectStealthScript();

            // ── Phase 2: ncpt JS 챌린지 자동 통과 대기 (최대 15초) ──
            // ncpt 챌린지 = JS가 브라우저 환경 검증 → 통과 시 쿠키 설정 + 리디렉트
            Logger.i("findMid: ncpt JS 챌린지 통과 대기 (최대 15초)...");
            boolean challengePassed = false;

            for (int retry = 0; retry < 5; retry++) {
                RandomDelay.sleepBetween(2000, 3000);
                injectStealthScript(); // 매 체크마다 재주입 (페이지 변경 대응)

                String urlCheck = evalJSSync("(function(){return window.location.href;})()", 3000);
                String pageState = evalJSSync(
                    "(function(){" +
                    "var t=(document.body?document.body.innerText:'').substring(0,500);" +
                    "var s=document.title||'';" +
                    "var u=location.href;" +
                    // ncpt 챌린지 통과 확인: 실제 상품 페이지 컨텐츠 존재
                    "if(t.length>50 && (t.includes('구매하기')||t.includes('장바구니')" +
                    "||t.includes('상품정보')||t.includes('리뷰')||t.includes('원')))return 'product:'+s;" +
                    // 아직 챌린지 중 (빈 페이지 또는 ncpt 로딩 중)
                    "if(t.trim().length<30)return 'loading:'+s+'|'+t.trim().substring(0,50);" +
                    // 차단 텍스트
                    "if(t.includes('비정상')||t.includes('접근이 제한'))return 'blocked:'+s;" +
                    "return 'unknown:'+s+'|'+t.substring(0,100);})()", 3000);

                Logger.i("findMid: 챌린지 체크 " + (retry + 1) + "/5: " + pageState);
                Logger.i("findMid:   URL: " + (urlCheck != null ? urlCheck.substring(0, Math.min(80, urlCheck.length())) : "null"));

                if (pageState != null && pageState.startsWith("product:")) {
                    challengePassed = true;
                    Logger.i("findMid: ★★ ncpt 챌린지 통과! 상품페이지 로드 성공! ★★");
                    break;
                }
                if (pageState != null && pageState.startsWith("blocked:")) {
                    Logger.w("findMid: 챌린지 실패 (차단 감지): " + pageState);
                    break;
                }
            }

            if (challengePassed) {
                // ★ 성공! 상품페이지 체류
                RandomDelay.sleepBetween(2000, 4000);
                int scrollPx = RandomDelay.between(300, 600);
                simulateSwipe(scrollPx);
                RandomDelay.sleepBetween(1000, 2000);

                Logger.i("findMid: ★ 상품페이지 진입 + 체류 완료 ★");
                allowShoppingInWebView = false;
                return StepResult.success();
            }

            // 챌린지 미통과 → Chrome 폴백
            Logger.w("findMid: ncpt 챌린지 미통과 → Chrome 폴백");
            allowShoppingInWebView = false;
            mainHandler.post(() -> { if (!isWebViewDestroyed()) webView.stopLoading(); });
            return handleMidChromeFallback(mid);

        } catch (Exception e) {
            allowShoppingInWebView = false;
            Logger.w("findMid: parse error: " + e.getMessage());
            return StepResult.fail("findMid: parse error: " + e.getMessage());
        }
    }

    /** WebView 418 시 Chrome Intent 폴백 */
    private StepResult handleMidChromeFallback(String mid) {
        String directUrl = "https://msearch.shopping.naver.com/product/" + mid;
        Logger.i("findMid: Chrome 폴백 → " + directUrl);

        try {
            android.content.Context ctx = webView.getContext();
            android.content.Intent chromeIntent = new android.content.Intent(
                android.content.Intent.ACTION_VIEW, Uri.parse(directUrl));
            chromeIntent.setPackage("com.android.chrome");
            chromeIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(chromeIntent);
            } catch (android.content.ActivityNotFoundException e) {
                chromeIntent.setPackage(null);
                ctx.startActivity(chromeIntent);
            }
        } catch (Exception e) {
            Logger.e("findMid: Chrome 폴백 실패: " + e.getMessage());
            return StepResult.fail("findMid: Chrome 폴백 실패");
        }

        // Chrome 체류
        RandomDelay.sleepBetween(4000, 8000);
        Logger.i("findMid: Chrome 폴백 체류 완료");

        // 앱 복귀
        try {
            android.content.Context ctx = webView.getContext();
            android.content.Intent returnIntent = new android.content.Intent(ctx,
                Class.forName("com.zero.traffic.MainActivity"));
            returnIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(returnIntent);
        } catch (Exception e) {
            Logger.w("findMid: 앱 복귀 실패 (무시): " + e.getMessage());
        }

        return StepResult.success();
    }

    // ═══════════════════════════════════════════════════
    // 내부 유틸
    // ═══════════════════════════════════════════════════

    /**
     * evaluateJavascript 동기 실행 (워커 스레드에서 호출)
     */
    private String evalJSSync(String js, long timeoutMs) {
        CompletableFuture<String> future = new CompletableFuture<>();

        mainHandler.post(() -> {
            if (isWebViewDestroyed()) {
                future.complete(null);
                return;
            }
            try {
                webView.evaluateJavascript(js, value -> {
                    // value는 JSON 인코딩된 문자열 ("\"result\"" 형태)
                    if (value != null && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                    }
                    future.complete(value);
                });
            } catch (Exception e) {
                future.complete(null);
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Logger.w("evalJS timeout");
            return null;
        }
    }

    /** FingerprintCollector 접근자 (ScenarioRunner에서 사용) */
    public FingerprintCollector getFingerprintCollector() {
        return fingerprintCollector;
    }

    /**
     * MotionEvent 터치 시뮬레이션 (메인 스레드에서 실행)
     */
    private void simulateTouch(float x, float y) {
        // 터치 이벤트 기록 (ML 분석용)
        if (fingerprintCollector != null) {
            fingerprintCollector.recordTouchEvent();
        }

        mainHandler.post(() -> {
            long now = SystemClock.uptimeMillis();

            float screenX = x;
            float screenY = y;

            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, screenX, screenY, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, screenX, screenY, 0);

            webView.dispatchTouchEvent(down);
            webView.dispatchTouchEvent(up);

            down.recycle();
            up.recycle();
        });
        RandomDelay.sleepBetween(100, 200);
    }

    private boolean isWebViewDestroyed() {
        return webView == null;
    }

    private String jsQuote(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }
}
