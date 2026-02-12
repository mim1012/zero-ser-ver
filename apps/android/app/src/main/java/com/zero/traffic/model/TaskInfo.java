package com.zero.traffic.model;

import org.json.JSONObject;

/**
 * 서버에서 받은 작업 정보 (claim-work 응답)
 */
public class TaskInfo {
    private final int trafficId;
    private final int slotId;
    private final String productName;
    private final String nvMid;
    private final String shortKeyword;
    private final String targetUrl;
    private int targetIndex;

    public TaskInfo(JSONObject json) {
        this.trafficId = json.optInt("traffic_id", 0);
        this.slotId = json.optInt("slot_id", 0);
        this.productName = json.optString("product_name", "");
        this.nvMid = json.optString("nv_mid", "");
        this.shortKeyword = json.optString("short_keyword", "");
        this.targetUrl = json.optString("target_url", "");
        this.targetIndex = json.optInt("target_index", 2);
    }

    public int getTrafficId() { return trafficId; }
    public int getSlotId() { return slotId; }
    public String getProductName() { return productName; }
    public String getNvMid() { return nvMid; }
    public String getShortKeyword() { return shortKeyword; }
    public String getTargetUrl() { return targetUrl; }
    public int getTargetIndex() { return targetIndex; }
    public void setTargetIndex(int idx) { this.targetIndex = idx; }

    /**
     * 검색 키워드 (short_keyword 우선, 없으면 product_name 앞 50자)
     */
    public String getKeyword() {
        String keyword = shortKeyword != null ? shortKeyword.trim() : "";
        if (!keyword.isEmpty()) {
            return keyword;
        }
        String product = productName != null ? productName.trim() : "";
        if (product.isEmpty()) {
            return nvMid != null ? nvMid : "";
        }
        if (product.length() > 50) {
            return product.substring(0, 50);
        }
        return product;
    }
}
