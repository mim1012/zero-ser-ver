package com.zero.traffic.engine;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.zero.traffic.model.Step;
import com.zero.traffic.model.StepResult;
import com.zero.traffic.util.Logger;
import com.zero.traffic.util.RandomDelay;

import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    public ActionExecutor(WebView webView) {
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /** ScenarioRunner에서 호출 — 현재 작업의 키워드/MID 전달 */
    public void setTaskContext(String keyword, String mid) {
        this.taskKeyword = keyword != null ? keyword : "";
        this.taskMid = mid != null ? mid : "";
    }

    // ── 봇 감지 우회 스텔스 스크립트 (mobile-stealth.ts 동기화) ──

    public static final String STEALTH_JS =
        // navigator.webdriver 제거 (핵심 — 봇 감지 1순위)
        "Object.defineProperty(navigator,'webdriver',{get:()=>false});" +
        // navigator.connection 모바일 네트워크
        "Object.defineProperty(navigator,'connection',{get:()=>({" +
        "effectiveType:'4g',rtt:50,downlink:10,saveData:false,type:'cellular'," +
        "addEventListener:()=>{},removeEventListener:()=>{}})});" +
        // window.chrome 객체
        "window.chrome={runtime:{},loadTimes:function(){},csi:function(){},app:{}};" +
        // Battery API 모바일화
        "if(navigator.getBattery){navigator.getBattery=()=>Promise.resolve({" +
        "charging:true,chargingTime:0,dischargingTime:Infinity," +
        "level:0.85+Math.random()*0.1," +
        "addEventListener:()=>{},removeEventListener:()=>{}});}";

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

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return false;
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    // 봇 감지 우회: 페이지 스크립트보다 먼저 스텔스 주입
                    view.evaluateJavascript(STEALTH_JS, null);
                }

                @Override
                public void onPageFinished(WebView view, String loadedUrl) {
                    if (!future.isDone()) {
                        future.complete(null);
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

    /** MID 발견 시 상세페이지 직접 이동 (bridge 418 회피) */
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

            // MID 링크 클릭 (트래킹용 — 네이버에 클릭 시그널 전달)
            RandomDelay.sleepBetween(500, 1000);
            evalJSSync(clickMidJS, 5000);

            // bridge 대기 없이 즉시 상품 상세페이지로 직접 이동
            RandomDelay.sleepBetween(500, 1000);
            String directUrl = "https://msearch.shopping.naver.com/product/" + mid;
            Logger.i("findMid: 상품페이지 직접 이동 → " + directUrl);
            evalJSSync("window.location.href='" + directUrl + "'", 5000);
            RandomDelay.sleepBetween(4000, 6000);

            String afterUrl = evalJSSync("(function(){return window.location.href;})()", 3000);
            if (afterUrl == null) afterUrl = "";
            Logger.i("findMid: 이동 후 URL: " + afterUrl);

            if (afterUrl.contains("smartstore.naver.com") || afterUrl.contains("brand.naver.com")
                    || afterUrl.contains("shopping.naver.com/product")) {
                Logger.i("findMid: ★ 상세페이지 진입 성공 ★");
                injectStealthScript();
                return StepResult.success();
            }

            Logger.e("findMid: 상세페이지 진입 실패 — 최종 URL: " + afterUrl);
            return StepResult.fail("findMid: 상세페이지 진입 실패");

        } catch (Exception e) {
            Logger.w("findMid: parse error: " + e.getMessage());
            return StepResult.fail("findMid: parse error: " + e.getMessage());
        }
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

    /**
     * MotionEvent 터치 시뮬레이션 (메인 스레드에서 실행)
     */
    private void simulateTouch(float x, float y) {
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
