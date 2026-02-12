package com.zero.traffic.util;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 통합 로거 (Logcat + 서버 전송 가능)
 */
public class Logger {
    private static final String TAG = "ZeroTraffic";
    private static final ThreadLocal<SimpleDateFormat> TIME_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.getDefault()));

    public static void i(String msg) {
        Log.i(TAG, format(msg));
    }

    public static void w(String msg) {
        Log.w(TAG, format(msg));
    }

    public static void e(String msg) {
        Log.e(TAG, format(msg));
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, format(msg), t);
    }

    public static void step(String stepId, String action) {
        Log.d(TAG, String.format("[STEP] %s → %s", stepId, action));
    }

    public static void step(String stepId, String action, String detail) {
        Log.d(TAG, String.format("[STEP] %s → %s: %s", stepId, action, detail));
    }

    private static String format(String msg) {
        return "[" + TIME_FMT.get().format(new Date()) + "] " + msg;
    }
}
