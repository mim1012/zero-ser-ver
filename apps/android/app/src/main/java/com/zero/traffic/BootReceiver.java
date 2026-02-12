package com.zero.traffic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.zero.traffic.service.TrafficService;
import com.zero.traffic.util.Logger;

/**
 * 부팅 시 자동 시작
 * 서버 URL이 저장되어 있으면 자동으로 TrafficService 시작
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Logger.i("부팅 감지 → 자동 시작 체크");

            SharedPreferences prefs = context.getSharedPreferences("ZeroSettings", Context.MODE_PRIVATE);
            String serverUrl = prefs.getString("server_url", "");

            if (!serverUrl.isEmpty()) {
                Logger.i("서버 URL 있음 → TrafficService 자동 시작");
                Intent serviceIntent = new Intent(context, TrafficService.class);
                serviceIntent.putExtra("server_url", serverUrl);
                context.startForegroundService(serviceIntent);
            }
        }
    }
}
