package com.zero.traffic.engine;

import android.webkit.WebView;

import com.zero.traffic.captcha.CaptchaProxy;
import com.zero.traffic.model.Scenario;
import com.zero.traffic.model.Step;
import com.zero.traffic.model.StepResult;
import com.zero.traffic.model.TaskInfo;
import com.zero.traffic.util.Logger;

/**
 * 시나리오 실행기 — JSON DSL 파싱 → Action 순차 실행
 *
 * 워커 스레드에서 호출. WebView 조작은 ActionExecutor가 Handler로 처리.
 */
public class ScenarioRunner {
    private static final int MAX_CAPTCHA_RETRIES_PER_STEP = 3;

    private final WebView webView;
    private final ActionExecutor executor;
    private final CaptchaProxy captchaProxy;
    private final ScriptEngine scriptEngine;

    private volatile boolean cancelled = false;

    public ScenarioRunner(WebView webView, CaptchaProxy captchaProxy, ScriptEngine scriptEngine) {
        this.webView = webView;
        this.executor = new ActionExecutor(webView);
        this.captchaProxy = captchaProxy;
        this.scriptEngine = scriptEngine;
    }

    /**
     * 시나리오 실행
     *
     * @param scenario JSON DSL 시나리오
     * @param task     서버에서 받은 작업 정보
     * @return 최종 결과 (success/fail)
     */
    public StepResult execute(Scenario scenario, TaskInfo task) {
        Logger.i("═══ 시나리오 시작: " + scenario.getName() + " ═══");
        Logger.i("  키워드: " + task.getKeyword());
        Logger.i("  MID: " + task.getNvMid());
        Logger.i("  스텝: " + scenario.getSteps().size() + "개");

        // 변수 치환기
        VariableResolver resolver = new VariableResolver(scenario, task);
        int captchaRetryCount = 0;

        for (int i = 0; i < scenario.getSteps().size(); i++) {
            if (cancelled) {
                return StepResult.abort("Cancelled");
            }

            Step rawStep = scenario.getSteps().get(i);
            Step step = resolver.resolve(rawStep);

            Logger.step(step.getId(), step.getAction());

            StepResult result = executeStep(step);

            // 결과 처리
            if (result.isFailed()) {
                if (result.isCaptcha()) {
                    // CAPTCHA 발견 → 서버 프록시로 해결 시도
                    String onCaptcha = step.getString("onCaptcha", "");
                    if ("solveCaptcha".equals(onCaptcha) || step.getAction().equals("checkStatus")) {
                        captchaRetryCount++;
                        if (captchaRetryCount > MAX_CAPTCHA_RETRIES_PER_STEP) {
                            Logger.e("CAPTCHA 최대 재시도 초과 (" + MAX_CAPTCHA_RETRIES_PER_STEP + "회) at " + step.getId());
                            return StepResult.fail("CAPTCHA 재시도 초과 at " + step.getId());
                        }
                        Logger.w("CAPTCHA 감지 → 해결 시도 (" + captchaRetryCount + "/" + MAX_CAPTCHA_RETRIES_PER_STEP + ")");
                        boolean solved = captchaProxy.solve(webView);
                        if (!solved) {
                            Logger.e("CAPTCHA 해결 실패");
                            return StepResult.fail("CAPTCHA 해결 실패 at " + step.getId());
                        }
                        Logger.i("CAPTCHA 해결 성공");
                        i--; // 현재 스텝을 다시 실행
                        continue;
                    }
                }
                if (result.isBlocked() || result.isAbort()) {
                    Logger.e("중단: " + result.getMessage());
                    return result;
                }
                // 일반 실패
                Logger.e("스텝 실패: " + step.getId() + " → " + result.getMessage());
                return result;
            }
            // 스텝 성공 시 CAPTCHA 카운터 리셋
            captchaRetryCount = 0;
        }

        Logger.i("═══ 시나리오 완료: " + scenario.getName() + " ═══");
        return StepResult.success();
    }

    /**
     * 개별 스텝 실행 (action별 분기)
     */
    private StepResult executeStep(Step step) {
        switch (step.getAction()) {
            case "navigate":
                return executor.navigate(step);
            case "delay":
                return executor.delay(step);
            case "tap":
                return executor.tap(step);
            case "humanType":
                return executor.humanType(step);
            case "press":
                return executor.press(step);
            case "scroll":
                return executor.scroll(step);
            case "scrollTo":
                return executor.scrollTo(step);
            case "checkStatus":
                return executor.checkStatus(step);
            case "clickProduct":
                return executor.clickProduct(step);
            case "dwell":
                return executor.dwell(step);
            case "report":
                return executor.report(step);
            case "log":
                return executor.log(step);
            case "evalJS":
                return executor.evalJS(step);
            case "findMid":
                return executor.findMid(step);
            case "runScript":
                String name = step.getString("scriptName", "");
                String content = scriptEngine.getScript(name);
                if (content == null) return StepResult.fail("Script not found: " + name);
                return executor.runScript(step, content);
            default:
                Logger.w("Unknown action: " + step.getAction());
                return StepResult.fail("Unknown action: " + step.getAction());
        }
    }

    /**
     * 실행 취소
     */
    public void cancel() {
        cancelled = true;
    }
}
