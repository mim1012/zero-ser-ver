package com.zero.traffic.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 시나리오 모델 (JSON DSL)
 */
public class Scenario {
    private final String id;
    private final String name;
    private final int version;
    private final Map<String, String> variables;
    private final List<Step> steps;

    public Scenario(JSONObject json) {
        this.id = json.optString("id", "");
        this.name = json.optString("name", "");
        this.version = json.optInt("version", 1);

        // variables
        this.variables = new HashMap<>();
        JSONObject vars = json.optJSONObject("variables");
        if (vars != null) {
            Iterator<String> keys = vars.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                variables.put(k, vars.optString(k, ""));
            }
        }

        // steps
        this.steps = new ArrayList<>();
        JSONArray stepsArr = json.optJSONArray("steps");
        if (stepsArr != null) {
            for (int i = 0; i < stepsArr.length(); i++) {
                JSONObject stepJson = stepsArr.optJSONObject(i);
                if (stepJson != null) {
                    steps.add(new Step(stepJson));
                }
            }
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public Map<String, String> getVariables() { return variables; }
    public List<Step> getSteps() { return steps; }
}
