package com.zero.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

public class TrafficAutomationService extends Service {
    private static final String TAG = "TrafficAutomationService";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "TrafficAutomationChannel";
    private static final String CHANNEL_NAME = "Traffic Automation";

    private static final String SERVER_URL = "https://zero-server.railway.app";

    private WebView webView;
    private TaskExecutor taskExecutor;
    private JavaScriptInterface jsInterface;
    private ConfigManager configManager;
    private WindowManager windowManager;

    private volatile String deviceId;
    private volatile int completedTaskCount = 0;
    private volatile int failedTaskCount = 0;

    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Service onCreate()");

        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification("초기화 중...", "서비스를 시작합니다"));

            deviceId = obtainDeviceId();
            configManager = new ConfigManager(this, SERVER_URL);

            initializeWebView();

            jsInterface = new JavaScriptInterface(this, webView, SERVER_URL, deviceId);
            jsInterface.setEventListener(new JavaScriptInterface.EventListener() {
                @Override
                public void onTaskStarted(int trafficId) {
                    updateNotification("작업 실행 중",
                        String.format("Traffic ID: %d (완료: %d, 실패: %d)",
                            trafficId, completedTaskCount, failedTaskCount));
                }

                @Override
                public void onTaskProgress(String status, String data) {
                    Log.d(TAG, String.format("Task progress: %s", status));
                }

                @Override
                public void onTaskCompleted(int trafficId, boolean success) {
                    if (success) {
                        completedTaskCount++;
                    }
                    updateNotification("대기 중",
                        String.format("완료: %d, 실패: %d", completedTaskCount, failedTaskCount));
                }

                @Override
                public void onTaskError(int trafficId, String error) {
                    failedTaskCount++;
                    updateNotification("대기 중",
                        String.format("완료: %d, 실패: %d (마지막 에러: %s)",
                            completedTaskCount, failedTaskCount, error));
                }
            });

            webView.addJavascriptInterface(jsInterface, "AndroidInterface");

            taskExecutor = new TaskExecutor(this, SERVER_URL, deviceId);
            taskExecutor.setTaskExecutionListener(new TaskExecutor.TaskExecutionListener() {
                @Override
                public void onExecuteTask(int trafficId, String productName, String nvMid,
                                        String shortKeyword, String script) {
                    jsInterface.setCurrentTask(trafficId, productName, nvMid);
                    injectScript(script, () -> {
                        jsInterface.startAutomation(productName, nvMid, shortKeyword);
                    });
                }

                @Override
                public void onNoWork() {
                    updateNotification("대기 중",
                        String.format("작업 대기 중 (완료: %d, 실패: %d)",
                            completedTaskCount, failedTaskCount));
                }

                @Override
                public void onError(String error) {
                    updateNotification("에러 발생", error);
                }
            });

            taskExecutor.startPolling();

            webView.loadUrl("https://m.naver.com/");

            updateNotification("실행 중", "작업 폴링 시작 (5분 간격)");
            Log.i(TAG, "Service started successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            Toast.makeText(this, "서비스 시작 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service onDestroy()");

        if (taskExecutor != null) {
            taskExecutor.stopPolling();
            taskExecutor = null;
        }

        if (webView != null) {
            try {
                if (windowManager != null) {
                    windowManager.removeView(webView);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove WebView from WindowManager: " + e.getMessage());
            }
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initializeWebView() {
        webView = new WebView(this);
        WebViewHelper.initializeWebView(this, webView, configManager);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        try {
            windowManager.addView(webView, params);
        } catch (Exception e) {
            Log.w(TAG, "Failed to add WebView to WindowManager: " + e.getMessage());
        }
    }

    private void injectScript(String script, Runnable onComplete) {
        webView.evaluateJavascript(script, result -> {
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    private String obtainDeviceId() {
        return android.provider.Settings.Secure.getString(
            getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("네이버 쇼핑 트래픽 자동화 서비스");

            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String title, String content) {
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        return notificationBuilder.build();
    }

    private void updateNotification(String title, String content) {
        if (notificationBuilder == null) {
            return;
        }

        notificationBuilder.setContentTitle(title)
                          .setContentText(content);

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }
}
