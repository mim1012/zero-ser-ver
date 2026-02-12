package com.zero.traffic.engine;

import com.zero.traffic.model.Scenario;
import com.zero.traffic.model.Step;
import com.zero.traffic.model.TaskInfo;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {{변수}} 치환기
 *
 * 시나리오 variables + TaskInfo 데이터를 결합하여
 * 스텝의 모든 string 값에서 {{keyword}}, {{mid}} 등을 실제 값으로 치환
 */
public class VariableResolver {
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(.+?)\\}\\}");

    private final Map<String, String> vars = new HashMap<>();

    public VariableResolver(Scenario scenario, TaskInfo task) {
        // 1. 시나리오 variables 먼저 등록
        //    예: "keyword": "{{task.product_name}}"
        //    → 아직 치환 안 됨 (task. prefix는 아래에서 처리)
        vars.putAll(scenario.getVariables());

        // 2. task 데이터 등록
        vars.put("task.product_name", task.getKeyword());
        vars.put("task.nv_mid", task.getNvMid());
        vars.put("task.target_index", String.valueOf(task.getTargetIndex()));
        vars.put("task.traffic_id", String.valueOf(task.getTrafficId()));
        vars.put("task.short_keyword", task.getShortKeyword());
        vars.put("task.target_url", task.getTargetUrl() != null ? task.getTargetUrl() : "");

        // 3. 시나리오 변수 중 {{task.*}} 참조를 실제 값으로 치환
        for (Map.Entry<String, String> entry : scenario.getVariables().entrySet()) {
            String val = entry.getValue();
            val = resolveString(val);
            vars.put(entry.getKey(), val);
        }
    }

    /**
     * Step의 모든 string 값에서 {{변수}}를 치환한 새 Step 반환
     * 중첩된 JSONObject도 재귀적으로 치환
     */
    public Step resolve(Step step) {
        JSONObject resolved = resolveObject(step.getRaw());
        return new Step(resolved);
    }

    /**
     * JSONObject의 모든 string 값을 재귀적으로 치환
     */
    private JSONObject resolveObject(JSONObject original) {
        JSONObject resolved = new JSONObject();
        try {
            Iterator<String> keys = original.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = original.opt(key);
                if (val instanceof String) {
                    resolved.put(key, resolveString((String) val));
                } else if (val instanceof JSONObject) {
                    resolved.put(key, resolveObject((JSONObject) val));
                } else {
                    resolved.put(key, val);
                }
            }
        } catch (org.json.JSONException e) {
            return original;
        }
        return resolved;
    }

    /**
     * 문자열 내 {{변수}} 치환
     */
    public String resolveString(String input) {
        if (input == null || !input.contains("{{")) return input;

        Matcher m = VAR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String varName = m.group(1).trim();
            String replacement = vars.getOrDefault(varName, m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
