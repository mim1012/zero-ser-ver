package com.zero.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

/**
 * TrafficAutomationService - 네이버 쇼핑 자동화 백그라운드 서비스
 *
 * Foreground Service로 실행되어:
 * 1. WebView 초기화 및 m.naver.com 로드
 * 2. TaskExecutor로 작업 폴링
 * 3. JavaScriptInterface로 JS ↔ Android 브릿지
 * 4. Notification으로 진행 상황 표시
 */
public class TrafficAutomationService extends Service {
    private static final String TAG = "TrafficAutomationService";

    // Notification 설정
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "TrafficAutomationChannel";
    private static final String CHANNEL_NAME = "Traffic Automation";

    // 서버 설정 (TODO: 환경변수나 설정 파일로 분리)
    private static final String SERVER_URL = "https://zero-ser-ver-production.up.railway.app";

    // 컴포넌트
    private WebView webView;
    private TaskExecutor taskExecutor;
    private JavaScriptInterface jsInterface;
    private ConfigManager configManager;
    private WindowManager windowManager;

    // 상태
    private volatile String deviceId;
    private volatile int currentTaskCount = 0;
    private volatile int completedTaskCount = 0;
    private volatile int failedTaskCount = 0;

    // Notification
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Service onCreate()");

        try {
            // 1. Notification 채널 생성
            createNotificationChannel();

            // 2. Foreground Service 시작
            startForeground(NOTIFICATION_ID, createNotification("초기화 중...", "서비스를 시작합니다"));

            // 3. Device ID 가져오기
            deviceId = getDeviceId();

            // 4. ConfigManager 초기화
            configManager = new ConfigManager(this, SERVER_URL);

            // 5. WebView 초기화 (백그라운드에서)
            initializeWebView();

            // 6. JavaScript Interface 생성
            jsInterface = new JavaScriptInterface(this, webView, SERVER_URL, deviceId);
            jsInterface.setEventListener(new JavaScriptInterface.EventListener() {
                @Override
                public void onTaskStarted(int trafficId) {
                    currentTaskCount++;
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
                        Log.i(TAG, String.format("Task completed: %d (total: %d)",
                            trafficId, completedTaskCount));
                    }

                    updateNotification("대기 중",
                        String.format("완료: %d, 실패: %d", completedTaskCount, failedTaskCount));
                }

                @Override
                public void onTaskError(int trafficId, String error) {
                    failedTaskCount++;
                    Log.e(TAG, String.format("Task error: %d - %s", trafficId, error));

                    updateNotification("대기 중",
                        String.format("완료: %d, 실패: %d (마지막 에러: %s)",
                            completedTaskCount, failedTaskCount, error));
                }
            });

            // WebView에 JavaScript Interface 추가
            webView.addJavascriptInterface(jsInterface, "AndroidInterface");

            // 7. TaskExecutor 초기화 및 시작
            taskExecutor = new TaskExecutor(this, SERVER_URL, deviceId);
            taskExecutor.setTaskExecutionListener(new TaskExecutor.TaskExecutionListener() {
                @Override
                public void onExecuteTask(int trafficId, String productName, String nvMid,
                                        String shortKeyword, String script) {
                    Log.i(TAG, String.format("Executing task: traffic_id=%d, product=%s, mid=%s",
                        trafficId, productName, nvMid));

                    // JavaScript Interface에 현재 작업 설정
                    jsInterface.setCurrentTask(trafficId, productName, nvMid);

                    // 스크립트 주입 (이미 로드되어 있으면 스킵)
                    injectScript(script, () -> {
                        // 자동화 시작
                        jsInterface.startAutomation(productName, nvMid, shortKeyword);
                    });
                }

                @Override
                public void onNoWork() {
                    Log.d(TAG, "No work available, waiting...");
                    updateNotification("대기 중",
                        String.format("작업 대기 중 (완료: %d, 실패: %d)",
                            completedTaskCount, failedTaskCount));
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "TaskExecutor error: " + error);
                    updateNotification("에러 발생", error);
                }
            });

            taskExecutor.startPolling();

            // 8. 초기 페이지 로드 (m.naver.com)
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
        Log.i(TAG, "Service onStartCommand()");
        return START_STICKY; // 강제 종료 시 재시작
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.i(TAG, "Service onDestroy()");

        // TaskExecutor 중지
        if (taskExecutor != null) {
            taskExecutor.stopPolling();
            taskExecutor = null;
        }

        // WebView 정리: WindowManager에서 제거 후 destroy
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

        Log.i(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Bound Service가 아님
    }

    /**
     * WebView 초기화 (백그라운드에서)
     */
    private void initializeWebView() {
        webView = new WebView(this);

        // WebView 설정 초기화
        WebViewHelper.initializeWebView(this, webView, configManager);

        // CustomWebViewClient 설정
        CustomWebViewClient webViewClient = new CustomWebViewClient(configManager);
        webView.setWebViewClient(webViewClient);

        // 백그라운드에서 WebView 렌더링하기 위한 설정
        // (화면에 보이지 않지만 동작은 함)
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            1, 1,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        try {
            windowManager.addView(webView, params);
            Log.d(TAG, "WebView added to WindowManager (background)");
        } catch (Exception e) {
            Log.w(TAG, "Failed to add WebView to WindowManager: " + e.getMessage());
            // 백그라운드 렌더링 실패해도 계속 진행 (일부 기능만 동작)
        }
    }

    /**
     * JavaScript 스크립트 주입
     */
    private void injectScript(String script, Runnable onComplete) {
        webView.evaluateJavascript(script, result -> {
            Log.d(TAG, "Script injected successfully");

            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    /**
     * Device ID 가져오기
     */
    private String getDeviceId() {
        // SharedPreferences에서 device_id 로드
        // (ConfigManager에 이미 구현되어 있을 수 있음)
        return android.provider.Settings.Secure.getString(
            getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        );
    }

    /**
     * Notification 채널 생성 (Android 8.0+)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // 소리 없이
            );
            channel.setDescription("네이버 쇼핑 트래픽 자동화 서비스");

            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Notification 생성
     */
    private Notification createNotification(String title, String content) {
        // TODO: MainActivity로 이동하는 PendingIntent 추가
        // Intent notificationIntent = new Intent(this, MainActivity.class);
        // PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: 실제 아이콘으로 변경
            // .setContentIntent(pendingIntent)
            .setOngoing(true) // 사용자가 스와이프로 제거 불가
            .setPriority(NotificationCompat.PRIORITY_LOW);

        return notificationBuilder.build();
    }

    /**
     * Notification 업데이트
     */
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
