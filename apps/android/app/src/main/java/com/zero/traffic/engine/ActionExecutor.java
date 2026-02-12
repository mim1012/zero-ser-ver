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

    public ActionExecutor(WebView webView) {
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
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
                    // 모든 URL을 WebView 내부에서 처리 (외부 브라우저 실행 방지)
                    return false;
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
            evalJSSync(String.format("window.scrollBy({top:%d,behavior:'smooth'})", px), 2000);
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

        String js = "(function(){" +
                "var t=document.body?document.body.innerText:'';" +
                "if(t.includes('비정상적인 접근')||t.includes('일시적으로 제한')||(t.includes('접근이 제한')&&t.includes('잠시 후'))) return 'blocked';" +
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

            // 네비게이션 대기
            RandomDelay.sleepBetween(2000, 3500);
            return StepResult.success();

        } catch (Exception e) {
            return StepResult.fail("clickProduct error: " + e.getMessage());
        }
    }

    // ── dwell (체류) ────────────────────────────────────

    public StepResult dwell(Step step) {
        int[] ms = step.getIntRange("ms", new int[]{3000, 5000});
        int scrollDist = step.getInt("scrollDist", 1500);
        int[] scrollCount = step.getIntRange("scrollCount", new int[]{1, 2});

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
            evalJSSync(String.format("window.scrollBy({top:%d,behavior:'smooth'})", px), 2000);
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

    // ── findMid (3전략 MID 탐색 + 스크롤) ───────────────

    public StepResult findMid(Step step) {
        String mid = step.getString("mid", "");
        int maxScroll = step.getInt("maxScroll", 10);
        int maxPages = step.getInt("maxPages", 5);
        if (mid.isEmpty()) return StepResult.fail("findMid: mid empty");

        Logger.step(step.getId(), "findMid", "mid=" + mid);

        // 3전략 MID 탐색 JS (unified-runner 동일)
        String midLiteral = mid.replace("'", "\\'");
        String findJS =
            "(function(){var mid='" + midLiteral + "';" +
            "var a1=document.querySelector('a[href*=\"nv_mid='+mid+'\"]');" +
            "if(a1){a1.scrollIntoView({block:'center',behavior:'smooth'});" +
            "var r=a1.getBoundingClientRect();var d=window.devicePixelRatio||1;" +
            "return JSON.stringify({x:(r.x+r.width/2)*d,y:(r.y+r.height/2)*d,s:1});}" +
            "var a2=document.querySelector('a[href*=\"/products/'+mid+'\"]');" +
            "if(a2){a2.scrollIntoView({block:'center',behavior:'smooth'});" +
            "var r2=a2.getBoundingClientRect();var d2=window.devicePixelRatio||1;" +
            "return JSON.stringify({x:(r2.x+r2.width/2)*d2,y:(r2.y+r2.height/2)*d2,s:2});}" +
            "var c=document.querySelector('[id=\"nstore_productId_'+mid+'\"]');" +
            "if(c){var a3=c.previousElementSibling;" +
            "while(a3&&a3.tagName!=='A')a3=a3.previousElementSibling;" +
            "if(!a3)a3=c.closest('a');" +
            "if(a3){a3.scrollIntoView({block:'center',behavior:'smooth'});" +
            "var r3=a3.getBoundingClientRect();var d3=window.devicePixelRatio||1;" +
            "return JSON.stringify({x:(r3.x+r3.width/2)*d3,y:(r3.y+r3.height/2)*d3,s:3});}}" +
            "return 'not_found';})()";

        // "다음 페이지" 버튼 찾기 JS (unified-runner 동일: button > span 텍스트)
        String nextPageJS =
            "(function(){" +
            "var btns=document.querySelectorAll('button');" +
            "for(var i=0;i<btns.length;i++){" +
            "  if(btns[i].disabled)continue;" +
            "  var spans=btns[i].querySelectorAll('span');" +
            "  for(var j=0;j<spans.length;j++){" +
            "    if(spans[j].textContent.trim()==='다음 페이지'){" +
            "      btns[i].scrollIntoView({block:'center',behavior:'smooth'});" +
            "      var r=btns[i].getBoundingClientRect();" +
            "      var d=window.devicePixelRatio||1;" +
            "      return JSON.stringify({x:(r.x+r.width/2)*d,y:(r.y+r.height/2)*d});" +
            "    }" +
            "  }" +
            "}" +
            "return 'not_found';})()";

        // 페이지 순회 (1페이지 = 현재 + 다음 페이지 버튼으로 2~maxPages)
        for (int page = 1; page <= maxPages; page++) {
            if (page > 1) {
                Logger.i("findMid: " + page + "페이지 탐색");
            }

            // 페이지 상단으로 스크롤 (2페이지부터)
            if (page > 1) {
                evalJSSync("window.scrollTo(0,0)", 2000);
                RandomDelay.sleepBetween(500, 800);
            }

            // 현재 페이지에서 스크롤하며 MID 탐색
            for (int i = 0; i < maxScroll; i++) {
                if (i > 0) {
                    Logger.i("findMid: scroll " + (i + 1) + "/" + maxScroll + " (p" + page + ")");
                }

                RandomDelay.sleepBetween(300, 500);

                String result = evalJSSync(findJS, 5000);
                if (result != null && !result.equals("not_found") && !result.equals("null") && !result.isEmpty()) {
                    try {
                        JSONObject pos = new JSONObject(result);
                        float x = (float) pos.getDouble("x");
                        float y = (float) pos.getDouble("y");
                        int strategy = pos.optInt("s", 0);
                        Logger.i("findMid: MID 발견 (전략 " + strategy + ", p" + page + ")");

                        RandomDelay.sleepBetween(500, 1000);
                        simulateTouch(x, y);

                        // 클릭 후 페이지 전환 대기
                        RandomDelay.sleepBetween(2000, 3500);

                        // 디버그: 클릭 후 URL 확인
                        String afterUrl = evalJSSync("(function(){return window.location.href;})()", 3000);
                        Logger.i("findMid: 클릭 후 URL: " + afterUrl);

                        return StepResult.success();

                    } catch (Exception e) {
                        Logger.w("findMid: parse error: " + e.getMessage());
                    }
                }

                // 스크롤 다운
                int scrollPx = RandomDelay.between(400, 600);
                evalJSSync(String.format("window.scrollBy({top:%d,behavior:'smooth'})", scrollPx), 2000);
                RandomDelay.sleepBetween(500, 800);

                // 스크롤 끝 감지
                if (i > 3) {
                    String heightCheck = evalJSSync(
                        "(function(){var h=document.body.scrollHeight;var y=window.scrollY+window.innerHeight;return (y>=h-50)?'end':'more';})()",
                        2000);
                    if ("end".equals(heightCheck)) {
                        Logger.i("findMid: scroll 끝 (p" + page + ")");
                        break;
                    }
                }
            }

            // 현재 페이지에서 못 찾음 → "다음 페이지" 버튼 클릭
            if (page < maxPages) {
                String nextResult = evalJSSync(nextPageJS, 3000);
                if (nextResult == null || nextResult.equals("not_found") || nextResult.equals("null")) {
                    Logger.i("findMid: 페이지네이션 없음 — 탐색 종료");
                    break;
                }

                try {
                    JSONObject btnPos = new JSONObject(nextResult);
                    float bx = (float) btnPos.getDouble("x");
                    float by = (float) btnPos.getDouble("y");
                    Logger.i("findMid: 다음 페이지 버튼 클릭");
                    simulateTouch(bx, by);
                    RandomDelay.sleepBetween(1500, 2500);
                } catch (Exception e) {
                    Logger.w("findMid: 다음 페이지 버튼 클릭 실패: " + e.getMessage());
                    break;
                }
            }
        }

        return StepResult.fail("findMid: MID not found after " + maxPages + " pages");
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
