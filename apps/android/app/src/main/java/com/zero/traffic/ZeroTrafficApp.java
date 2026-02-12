package com.zero.traffic;

import android.app.Application;

import com.zero.traffic.util.Logger;

public class ZeroTrafficApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Logger.i("ZeroTrafficApp 시작");
    }
}
