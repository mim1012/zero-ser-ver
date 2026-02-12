package com.zero.traffic.model;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 시나리오 스텝 (JSON DSL 파싱)
 */
public class Step {
    private final JSONObject raw;

    public Step(JSONObject json) {
        this.raw = json;
    }

    public String getId() {
        return raw.optString("id", "");
    }

    public String getAction() {
        return raw.optString("action", "");
    }

    // ── String ──
    public String getString(String key) {
        return raw.optString(key, "");
    }

    public String getString(String key, String def) {
        return raw.optString(key, def);
    }

    // ── Boolean ──
    public boolean getBoolean(String key, boolean def) {
        return raw.optBoolean(key, def);
    }

    // ── Int ──
    public int getInt(String key, int def) {
        return raw.optInt(key, def);
    }

    // ── Int Array [min, max] ──
    public int[] getIntRange(String key, int[] def) {
        JSONArray arr = raw.optJSONArray(key);
        if (arr != null && arr.length() >= 2) {
            return new int[]{arr.optInt(0, def[0]), arr.optInt(1, def[1])};
        }
        return def;
    }

    // ── Long ──
    public long getLong(String key, long def) {
        return raw.optLong(key, def);
    }

    public JSONObject getRaw() {
        return raw;
    }

    @Override
    public String toString() {
        return getId() + ":" + getAction();
    }
}
