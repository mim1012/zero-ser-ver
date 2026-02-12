package com.zero.traffic.server;

import com.zero.traffic.model.TaskInfo;
import com.zero.traffic.util.Logger;

import org.json.JSONObject;

/**
 * 작업 관리 — claim-work / complete / fail
 * 기존 traffic API 연동
 */
public class TaskManager {
    private final ApiClient api;
    private final String deviceId;

    public TaskManager(ApiClient api, String deviceId) {
        this.api = api;
        this.deviceId = deviceId;
    }

    /**
     * 서버에서 다음 작업 가져오기
     * @return TaskInfo or null
     */
    public TaskInfo claimWork() {
        try {
            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);

            JSONObject resp = api.postJSON("/traffic/claim-work", body);
            return new TaskInfo(resp);

        } catch (Exception e) {
            Logger.w("작업 없음 또는 서버 오류: " + e.getMessage());
            return null;
        }
    }

    /**
     * 작업 완료 보고
     */
    public void complete(int trafficId, int slotId) {
        try {
            JSONObject body = new JSONObject();
            body.put("traffic_id", trafficId);
            body.put("slot_id", slotId);
            body.put("device_id", deviceId);

            api.postJSON("/traffic/complete", body);
            Logger.i("작업 완료 보고: #" + trafficId + " (slot=" + slotId + ")");

        } catch (Exception e) {
            Logger.e("완료 보고 실패: " + e.getMessage());
        }
    }

    /**
     * 작업 실패 보고
     */
    public void fail(int trafficId, int slotId, String errorMessage) {
        try {
            JSONObject body = new JSONObject();
            body.put("traffic_id", trafficId);
            body.put("slot_id", slotId);
            body.put("device_id", deviceId);
            body.put("error_message", errorMessage);

            api.postJSON("/traffic/fail", body);
            Logger.w("작업 실패 보고: #" + trafficId + " (slot=" + slotId + ") → " + errorMessage);

        } catch (Exception e) {
            Logger.e("실패 보고 실패: " + e.getMessage());
        }
    }

    /**
     * 액션 로그 전송
     */
    public void logAction(int trafficId, String action, String message) {
        try {
            JSONObject body = new JSONObject();
            body.put("traffic_id", trafficId);
            body.put("device_id", deviceId);
            body.put("action", action);
            body.put("message", message);

            api.postJSON("/traffic/log", body);

        } catch (Exception e) {
            // 로그 실패는 무시
        }
    }
}
