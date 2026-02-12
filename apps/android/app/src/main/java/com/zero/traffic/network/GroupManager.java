package com.zero.traffic.network;

import android.content.Context;
import android.content.SharedPreferences;

import com.zero.traffic.server.ApiClient;
import com.zero.traffic.util.Logger;

import org.json.JSONObject;

/**
 * 그룹 등록 + 역할 관리
 *
 * 서버 /devices/register 호출 → group_id, role(leader/follower) 받음
 * 역할에 따라 HotspotManager 또는 WifiConnector 실행
 */
public class GroupManager {
    private static final String PREFS = "ZeroGroup";

    private final Context context;
    private final ApiClient api;
    private final String deviceId;
    private final SharedPreferences prefs;

    private int groupId;
    private String groupName;
    private String role; // "leader" or "follower"
    private String leaderDeviceId;

    private HotspotManager hotspotManager;
    private WifiConnector wifiConnector;

    public GroupManager(Context context, ApiClient api, String deviceId) {
        this.context = context;
        this.api = api;
        this.deviceId = deviceId;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // 캐시에서 로드
        this.groupId = prefs.getInt("group_id", -1);
        this.role = prefs.getString("role", "");
        this.groupName = prefs.getString("group_name", "");
    }

    /**
     * 서버에 기기 등록 → 그룹/역할 할당받기
     */
    public boolean register(String currentIp) {
        try {
            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);
            if (currentIp != null) body.put("current_ip", currentIp);

            JSONObject resp = api.postJSON("/devices/register", body);

            groupId = resp.optInt("group_id", -1);
            groupName = resp.optString("group_name", "");
            role = resp.optString("role", "follower");

            // 캐시 저장
            prefs.edit()
                    .putInt("group_id", groupId)
                    .putString("role", role)
                    .putString("group_name", groupName)
                    .apply();

            Logger.i(String.format("그룹 등록: %s (Group %d) role=%s",
                    groupName, groupId, role));
            return true;

        } catch (Exception e) {
            Logger.e("그룹 등록 실패: " + e.getMessage());
            // 캐시된 값 사용
            return groupId > 0;
        }
    }

    /**
     * 역할에 따라 네트워크 설정
     * - leader: 핫스팟 ON
     * - follower: 대장봇 핫스팟에 WiFi 연결
     */
    public void setupNetwork() {
        if ("leader".equals(role)) {
            setupAsLeader();
        } else {
            setupAsFollower();
        }
    }

    private void setupAsLeader() {
        Logger.i("═══ 대장봇: 핫스팟 설정 ═══");
        hotspotManager = new HotspotManager(context);

        String ssid = "zero-g" + groupId;
        String password = buildGroupPassword();

        boolean success = hotspotManager.enableHotspot(ssid, password);
        if (success) {
            Logger.i("핫스팟 ON: SSID=" + ssid);
            // 서버에 핫스팟 IP 보고
            reportHotspotIp();
        } else {
            Logger.e("핫스팟 켜기 실패 — 수동 설정 필요");
        }
    }

    private void setupAsFollower() {
        Logger.i("═══ 쫄병봇: WiFi 연결 ═══");
        wifiConnector = new WifiConnector(context);

        String ssid = "zero-g" + groupId;
        String password = buildGroupPassword();

        // 연결 시도 (최대 30초)
        boolean connected = wifiConnector.connectToHotspot(ssid, password, 30000);
        if (connected) {
            Logger.i("WiFi 연결 성공: " + ssid);
        } else {
            Logger.w("WiFi 연결 실패 — 기존 네트워크 사용");
        }
    }

    private void reportHotspotIp() {
        try {
            String ip = NetworkUtils.getLocalIpAddress();
            if (ip != null) {
                JSONObject body = new JSONObject();
                body.put("device_id", deviceId);
                body.put("current_ip", ip);
                api.postJSON("/devices/heartbeat", body);
                Logger.i("핫스팟 IP 보고: " + ip);
            }
        } catch (Exception e) {
            Logger.w("IP 보고 실패: " + e.getMessage());
        }
    }

    private String buildGroupPassword() {
        String password = "zero" + String.format("%04d", Math.max(groupId, 0));
        if (password.length() < 8) {
            password = (password + "00000000").substring(0, 8);
        }
        return password;
    }

    /**
     * 정리 (서비스 종료 시)
     */
    public void cleanup() {
        if (hotspotManager != null) {
            hotspotManager.disableHotspot();
        }
        if (wifiConnector != null) {
            wifiConnector.disconnect();
        }
    }

    // ── Getters ────────────────────────────────────────

    public int getGroupId() { return groupId; }
    public String getRole() { return role; }
    public String getGroupName() { return groupName; }
    public boolean isLeader() { return "leader".equals(role); }
    public boolean isRegistered() { return groupId > 0 && !role.isEmpty(); }
}
