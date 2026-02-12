package com.zero.traffic.engine;

import android.content.Context;
import android.content.SharedPreferences;

import com.zero.traffic.server.ApiClient;
import com.zero.traffic.util.Logger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * JS 스크립트 다운로드/캐시/실행 관리
 *
 * - 서버에서 스크립트 버전 체크 → 변경분만 다운로드
 * - SharedPreferences에 캐시
 * - evaluateJavascript에 사용할 JS 코드 반환
 */
public class ScriptEngine {
    private static final String PREFS = "ZeroScripts";

    private final Context context;
    private final ApiClient api;
    private final SharedPreferences prefs;

    /** name → JS content */
    private final Map<String, String> cache = new HashMap<>();
    /** name → hash */
    private final Map<String, String> hashes = new HashMap<>();

    public ScriptEngine(Context context, ApiClient api) {
        this.context = context;
        this.api = api;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadFromCache();
    }

    /**
     * 서버와 버전 동기화
     */
    public void sync() {
        try {
            JSONObject resp = api.getJSON("/script/version");
            JSONObject scripts = resp.optJSONObject("scripts");
            if (scripts == null) return;

            Iterator<String> keys = scripts.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject info = scripts.getJSONObject(name);
                String serverHash = info.optString("hash", "");

                String localHash = hashes.getOrDefault(name, "");

                if (!serverHash.equals(localHash)) {
                    // 변경됨 → 다운로드
                    String content = api.getText("/script/" + name);
                    if (content != null && !content.isEmpty()) {
                        cache.put(name, content);
                        hashes.put(name, serverHash);
                        saveToCache(name, content, serverHash);
                        Logger.i("스크립트 업데이트: " + name);
                    }
                }
            }
            Logger.i("스크립트 동기화 완료: " + cache.size() + "개");

        } catch (Exception e) {
            Logger.e("스크립트 동기화 실패 (캐시 사용)", e);
        }
    }

    /**
     * 스크립트 내용 가져오기 (캐시)
     */
    public String getScript(String name) {
        return cache.get(name);
    }

    // ── 캐시 ───────────────────────────────────────────

    private void saveToCache(String name, String content, String hash) {
        prefs.edit()
                .putString("script_" + name, content)
                .putString("hash_" + name, hash)
                .apply();
    }

    private void loadFromCache() {
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("script_") && entry.getValue() instanceof String) {
                String name = key.substring(7);
                cache.put(name, (String) entry.getValue());
                hashes.put(name, prefs.getString("hash_" + name, ""));
            }
        }
        if (!cache.isEmpty()) {
            Logger.i("캐시에서 " + cache.size() + "개 스크립트 로드");
        }
    }
}
