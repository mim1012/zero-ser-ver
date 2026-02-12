package com.zero.traffic.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 알림 액션 버튼용 BroadcastReceiver — WebView 보이기/숨기기 토글
 */
public class ToggleReceiver extends BroadcastReceiver {
    public static volatile TrafficService serviceRef;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (serviceRef != null) {
            serviceRef.onToggleWebView();
        }
    }
}
