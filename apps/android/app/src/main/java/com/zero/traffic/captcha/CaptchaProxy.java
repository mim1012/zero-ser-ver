package com.zero.traffic.captcha;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebView;

import com.zero.traffic.server.ApiClient;
import com.zero.traffic.util.Logger;
import com.zero.traffic.util.RandomDelay;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * CAPTCHA 프록시 — 서버 경유 Claude Vision 호출
 *
 * 1. WebView에서 CAPTCHA 이미지 + 질문 추출 (JS)
 * 2. 서버 /captcha/solve 호출
 * 3. 답 입력 + 제출
 * 4. 최대 3회 재시도 (findMid에서도 retry하므로 줄임)
 */
public class CaptchaProxy {
    private static final int MAX_ATTEMPTS = 3;

    private final ApiClient api;
    private final String deviceId;
    private final Handler mainHandler;

    public CaptchaProxy(ApiClient api, String deviceId) {
        this.api = api;
        this.deviceId = deviceId;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * CAPTCHA 해결 시도
     * @return true=해결됨, false=실패
     */
    public boolean solve(WebView webView) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                RandomDelay.sleepBetween(1000 * attempt, 1000 * attempt + 1000);
            }
            Logger.i(String.format("═══ CAPTCHA %d/%d ═══", attempt, MAX_ATTEMPTS));

            // 1. 페이지 상태 확인
            String status = evalJSSync(webView, CHECK_STATUS_JS, 5000);
            if ("ok".equals(status)) return true;
            if ("blocked".equals(status)) return false;

            // 2. 질문 추출
            String question = evalJSSync(webView, EXTRACT_QUESTION_JS, 5000);
            if (question == null || question.isEmpty()) {
                Logger.w("질문 추출 실패");
                continue;
            }
            Logger.i("질문: " + question);

            // 3. 이미지 캡처 (base64)
            String imageBase64 = captureReceiptImage(webView);
            if (imageBase64 == null || imageBase64.isEmpty()) {
                Logger.w("이미지 캡처 실패 → 전체 스크린샷");
                imageBase64 = captureFullPage(webView);
            }
            if (imageBase64 == null || imageBase64.isEmpty()) {
                Logger.w("이미지 캡처 완전 실패 — 스킵");
                continue;
            }

            // 4. 서버에 CAPTCHA 해결 요청
            String answer;
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", deviceId);
                body.put("image_base64", imageBase64);
                body.put("question", question);

                JSONObject resp = api.postJSON("/captcha/solve", body);
                answer = resp.optString("answer", "");
                String confidence = resp.optString("confidence", "low");

                Logger.i("답: \"" + answer + "\" (신뢰도: " + confidence + ")");

            } catch (Exception e) {
                Logger.e("CAPTCHA 서버 호출 실패: " + e.getMessage());
                continue;
            }

            if (answer.isEmpty()) continue;

            // 5. 답 입력 (캐릭터별 딜레이)
            RandomDelay.sleepBetween(500, 1000);
            inputAnswer(webView, answer);
            RandomDelay.sleepBetween(300, 600);

            // 6. 확인 버튼 클릭
            clickSubmit(webView);
            RandomDelay.sleepBetween(2000, 3000);

            // 7. 결과 확인
            String afterStatus = evalJSSync(webView, CHECK_STATUS_JS, 5000);
            if ("ok".equals(afterStatus)) {
                Logger.i("CAPTCHA 해결 성공!");
                return true;
            }
        }

        Logger.e("CAPTCHA " + MAX_ATTEMPTS + "회 시도 실패");
        return false;
    }

    // ── 이미지 캡처 ────────────────────────────────────

    private String captureReceiptImage(WebView webView) {
        String[] selectors = {
            "#rcpt_img", "#captchaImage", "#captchaImg",
            ".captcha_img", ".captcha_img_cover img", ".captcha_box img",
            "img[alt='캡차이미지']", "img[alt*='영수증']",
            "img[src*='captcha']", "img[src*='receipt']",
            ".captcha_image img", ".receipt_image img",
            ".security_area img", "[class*='security'] img",
            "[class*='captcha'] img", "[class*='receipt'] img"
        };

        for (String sel : selectors) {
            String js = String.format(
                "(function(){try{var el=document.querySelector('%s');" +
                "if(!el)return null;" +
                "var c=document.createElement('canvas');" +
                "c.width=el.naturalWidth||el.width;c.height=el.naturalHeight||el.height;" +
                "c.getContext('2d').drawImage(el,0,0);" +
                "try{return c.toDataURL('image/png');}catch(e){return null;}" +
                "}catch(e){return null;}})()",
                sel.replace("'", "\\'"));

            String dataUrl = evalJSSync(webView, js, 5000);
            if (dataUrl != null && dataUrl.startsWith("data:image")) {
                int comma = dataUrl.indexOf(",");
                if (comma > 0) {
                    Logger.i("이미지 캡처: " + sel);
                    return dataUrl.substring(comma + 1);
                }
            }
        }
        return null;
    }

    private String captureFullPage(WebView webView) {
        CompletableFuture<String> future = new CompletableFuture<>();
        mainHandler.post(() -> {
            try {
                int width = webView.getWidth();
                int height = webView.getHeight();
                if (width <= 0 || height <= 0) {
                    future.complete(null);
                    return;
                }

                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(bmp);
                webView.draw(canvas);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 85, out);
                bmp.recycle();

                String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                future.complete(b64);
            } catch (Exception e) {
                future.complete(null);
            }
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 답 입력 (캐릭터별 키보드 이벤트 + 딜레이) ──────

    private void inputAnswer(WebView webView, String answer) {
        // 입력창 포커스
        String focusJS =
            "(function(){" +
            "var sels=['input#rcpt_answer','input#captcha_input','input#confirm_input'," +
            "'input[placeholder*=\"정답\"]','input[placeholder*=\"입력\"]','input[placeholder*=\"답\"]'," +
            "'input[name*=\"answer\"]','input[name*=\"captcha\"]'," +
            "'input[id*=\"answer\"]','input[id*=\"rcpt\"]'," +
            "'.captcha_input input','#captcha_answer','.security_area input'," +
            "'input[type=\"number\"]','input[type=\"tel\"]'];" +
            "var inp=null;" +
            "for(var i=0;i<sels.length;i++){inp=document.querySelector(sels[i]);if(inp)break;}" +
            "if(!inp)return 'no_input';" +
            "inp.click();inp.focus();inp.value='';" +
            "return 'ok';})()";

        String focusResult = evalJSSync(webView, focusJS, 5000);
        if ("no_input".equals(focusResult)) {
            Logger.w("CAPTCHA 입력창 미발견");
            return;
        }
        RandomDelay.sleepBetween(200, 400);

        // 캐릭터별 입력 (keydown/input/keyup 이벤트 포함)
        for (int i = 0; i < answer.length(); i++) {
            String c = JSONObject.quote(String.valueOf(answer.charAt(i)));
            String typeJS =
                "(function(){var sels=['input#rcpt_answer','input#captcha_input'," +
                "'input[placeholder*=\"정답\"]','input[placeholder*=\"입력\"]'," +
                "'input[name*=\"answer\"]','input[id*=\"answer\"]'," +
                "'.captcha_input input','#captcha_answer'];" +
                "var inp=null;" +
                "for(var i=0;i<sels.length;i++){inp=document.querySelector(sels[i]);if(inp)break;}" +
                "if(!inp)return;" +
                "var ch=" + c + ";" +
                "inp.dispatchEvent(new KeyboardEvent('keydown',{key:ch,bubbles:true}));" +
                "inp.value+=ch;" +
                "inp.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:ch}));" +
                "inp.dispatchEvent(new KeyboardEvent('keyup',{key:ch,bubbles:true}));})()";
            evalJSSync(webView, typeJS, 2000);
            RandomDelay.sleepBetween(60, 160);
        }

        // change 이벤트
        evalJSSync(webView,
            "(function(){var inp=document.querySelector('input#rcpt_answer')||" +
            "document.querySelector('input[placeholder*=\"정답\"]')||" +
            "document.querySelector('input[name*=\"answer\"]');" +
            "if(inp)inp.dispatchEvent(new Event('change',{bubbles:true}));})()", 2000);
    }

    // ── 제출 ───────────────────────────────────────────

    private void clickSubmit(WebView webView) {
        String js = "(function(){" +
            "var sels=['button#confirm','#confirm','button[type=\"submit\"]','input[type=\"submit\"]'," +
            "'.confirm_btn','.submit_btn','.btn_submit','.btn_confirm','.captcha_btn'," +
            "'button.btn_naver','button[class*=\"confirm\"]','button[class*=\"submit\"]'];" +
            "for(var i=0;i<sels.length;i++){var b=document.querySelector(sels[i]);if(b){b.click();return 'clicked';}}" +
            "var btns=document.querySelectorAll('button');" +
            "for(var j=0;j<btns.length;j++){var txt=btns[j].textContent.trim();" +
            "if(txt.indexOf('확인')>=0||txt.indexOf('제출')>=0||txt.indexOf('완료')>=0){btns[j].click();return 'clicked';}}" +
            "return 'not_found';})()";

        String result = evalJSSync(webView, js, 5000);
        if ("not_found".equals(result)) {
            // activeElement에서 Enter 폴백
            evalJSSync(webView,
                "(function(){var ae=document.activeElement;" +
                "if(ae&&ae.form){ae.form.submit();}" +
                "else if(ae){ae.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));}" +
                "else{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));}})()",
                3000);
        }
    }

    // ── JS 유틸 ────────────────────────────────────────

    private String evalJSSync(WebView webView, String js, long timeoutMs) {
        CompletableFuture<String> future = new CompletableFuture<>();
        mainHandler.post(() -> {
            if (webView == null) {
                future.complete(null);
                return;
            }
            try {
                webView.evaluateJavascript(js, value -> {
                    if (value != null && value.startsWith("\"") && value.endsWith("\"")) {
                        try {
                            value = new org.json.JSONArray("[" + value + "]").getString(0);
                        } catch (Exception e) {
                            value = value.substring(1, value.length() - 1)
                                .replace("\\\"", "\"").replace("\\\\", "\\");
                        }
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
            return null;
        }
    }

    // ── JS 상수 ────────────────────────────────────────

    // CHECK_STATUS: '보안 확인' AND ('영수증' OR '무엇입니까') — 단독 '영수증' 제거 (false-positive 방지)
    private static final String CHECK_STATUS_JS =
        "(function(){var t=document.body?document.body.innerText:'';" +
        "if(t.includes('비정상적인 접근')||t.includes('일시적으로 제한')" +
        "||t.includes('자동화된 접근')||t.includes('접근이 제한')" +
        "||t.includes('잠시 후 다시')||t.includes('이용이 제한'))return 'blocked';" +
        "if((t.includes('보안 확인')&&(t.includes('영수증')||t.includes('무엇입니까')))" +
        "||t.includes('자동입력방지'))return 'captcha';" +
        "return 'ok';})()";

    private static final String EXTRACT_QUESTION_JS =
        "(function(){var t=document.body?document.body.innerText:'';" +
        "var m1=t.match(/.+무엇입니까\\??/);if(m1)return m1[0].trim();" +
        "var m2=t.match(/영수증의\\s+.+?\\s+\\[?\\?\\]?\\s*입니다/);if(m2)return m2[0].trim();" +
        "var ps=[/가게\\s*위치는\\s*.+?\\s*\\[?\\?\\]?\\s*입니다/," +
        "/가게\\s*이름은\\s*.+?\\s*\\[?\\?\\]?\\s*입니다/," +
        "/전화번호는?\\s*.+?\\s*\\[?\\?\\]?\\s*입니다/," +
        "/합계\\s*금액은?\\s*.+?\\s*\\[?\\?\\]?\\s*입니다/," +
        "/날짜는?\\s*.+?\\s*\\[?\\?\\]?\\s*입니다/," +
        "/시간은?\\s*.+?\\s*\\[?\\?\\]?\\s*입니다/," +
        "/.+번째\\s*숫자는\\s*무엇입니까/," +
        "/.+번째\\s*글자는\\s*무엇입니까/," +
        "/앞에서\\s*.+번째\\s*숫자는\\s*무엇입니까/," +
        "/앞에서\\s*.+번째\\s*글자는\\s*무엇입니까/];" +
        "for(var i=0;i<ps.length;i++){var m=t.match(ps[i]);if(m)return m[0].trim();}" +
        "return '';})()";
}
