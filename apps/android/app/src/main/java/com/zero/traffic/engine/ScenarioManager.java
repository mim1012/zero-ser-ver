package com.zero.traffic.engine;

import android.content.Context;
import android.content.SharedPreferences;

import com.zero.traffic.model.Scenario;
import com.zero.traffic.server.ApiClient;
import com.zero.traffic.util.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 시나리오 다운로드/캐시/버전관리
 *
 * - 서버에서 활성 시나리오 목록 조회
 * - 로컬 캐시 (SharedPreferences)
 * - 가중치 기반 시나리오 선택
 */
public class ScenarioManager {
    private static final String PREFS = "ZeroScenarios";

    private final Context context;
    private final ApiClient api;
    private final SharedPreferences prefs;
    private final String deviceId;

    /** id → Scenario */
    private final Map<String, Scenario> cache = new HashMap<>();
    /** id → weight */
    private final Map<String, Integer> weights = new HashMap<>();
    /** id → version (서버) */
    private final Map<String, Integer> versions = new HashMap<>();

    private final Random rng = new Random();

    public ScenarioManager(Context context, ApiClient api, String deviceId) {
        this.context = context;
        this.api = api;
        this.deviceId = deviceId;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadFromCache();
    }

    /**
     * 서버에서 활성 시나리오 동기화
     * 변경된 것만 다운로드
     */
    public void sync() {
        try {
            JSONObject resp = api.getJSON("/scenario/active?device_id=" + deviceId);
            JSONArray arr = resp.optJSONArray("scenarios");
            if (arr == null) return;
            Set<String> activeIds = new HashSet<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                String id = s.getString("id");
                int serverVer = s.optInt("version", 1);
                int weight = s.optInt("weight", 1);
                activeIds.add(id);

                weights.put(id, weight);

                // 캐시 버전과 비교
                int localVer = versions.getOrDefault(id, 0);
                if (serverVer > localVer || !cache.containsKey(id)) {
                    // 시나리오 상세 다운로드
                    JSONObject detail = api.getJSON("/scenario/" + id);
                    Scenario scenario = new Scenario(detail);
                    cache.put(id, scenario);
                    versions.put(id, serverVer);
                    saveToCache(id, detail.toString(), serverVer);
                    Logger.i("시나리오 업데이트: " + id + " v" + serverVer);
                }
            }

            // 서버에서 제거된 시나리오는 로컬 캐시/버전에서도 정리
            List<String> staleIds = new ArrayList<>();
            for (String id : cache.keySet()) {
                if (!activeIds.contains(id)) {
                    staleIds.add(id);
                }
            }
            if (!staleIds.isEmpty()) {
                SharedPreferences.Editor editor = prefs.edit();
                for (String id : staleIds) {
                    cache.remove(id);
                    weights.remove(id);
                    versions.remove(id);
                    editor.remove("scenario_" + id);
                    editor.remove("version_" + id);
                }
                editor.apply();
                Logger.i("시나리오 정리: " + staleIds.size() + "개 제거");
            }

            Logger.i("시나리오 동기화 완료: " + cache.size() + "개");

        } catch (Exception e) {
            Logger.e("시나리오 동기화 실패 (캐시 사용)", e);
        }
    }

    /**
     * 가중치 기반 랜덤 시나리오 선택
     * weight=3:1 → 75% route1, 25% route2
     */
    public Scenario selectScenario() {
        if (cache.isEmpty()) return null;

        List<String> ids = new ArrayList<>(cache.keySet());
        int totalWeight = 0;
        for (String id : ids) {
            totalWeight += weights.getOrDefault(id, 1);
        }

        int pick = rng.nextInt(totalWeight);
        int cumulative = 0;
        for (String id : ids) {
            cumulative += weights.getOrDefault(id, 1);
            if (pick < cumulative) {
                Logger.i("시나리오 선택: " + id);
                return cache.get(id);
            }
        }
        return cache.values().iterator().next();
    }

    /**
     * 특정 시나리오 가져오기
     */
    public Scenario getScenario(String id) {
        return cache.get(id);
    }

    public int getCount() {
        return cache.size();
    }

    // ── 캐시 ───────────────────────────────────────────

    private void saveToCache(String id, String json, int version) {
        prefs.edit()
                .putString("scenario_" + id, json)
                .putInt("version_" + id, version)
                .apply();
    }

    private void loadFromCache() {
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("scenario_") && entry.getValue() instanceof String) {
                String id = key.substring(9);
                try {
                    JSONObject json = new JSONObject((String) entry.getValue());
                    cache.put(id, new Scenario(json));
                    versions.put(id, prefs.getInt("version_" + id, 1));
                    weights.put(id, 1);
                } catch (Exception e) {
                    Logger.w("캐시 로드 실패: " + id);
                }
            }
        }
        if (!cache.isEmpty()) {
            Logger.i("캐시에서 " + cache.size() + "개 시나리오 로드");
        }
    }
}
