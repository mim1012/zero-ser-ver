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
import android.os.IBinder;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

/**
 * TrafficAutomationService v2 - 배치 작업 처리 지원
 *
 * 주요 변경사항:
 * 1. TaskCompletionCallback 저장 및 호출
 * 2. 작업 완료 시 다음 작업 자동 트리거
 * 3. 큐 크기 표시
 */
public class TrafficAutomationService extends Service {
    private static final String TAG = "TrafficAutomationService";

    // Notification 설정
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "TrafficAutomationChannel";
    private static final String CHANNEL_NAME = "Traffic Automation";

    // 서버 설정 (TODO: 환경변수나 설정 파일로 분리)
    private static final String SERVER_URL = "https://zero-server.railway.app";

    // 컴포넌트
    private WebView webView;
    private TaskExecutor taskExecutor;
    private JavaScriptInterface jsInterface;
    private ConfigManager configManager;
    private WindowManager windowManager;

    // 배치 처리 관련
    private TaskExecutor.TaskCompletionCallback currentCallback;

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

        Log.i(TAG, "Service onCreate() - Batch mode enabled");

        try {
            // 1. Notification 채널 생성
            createNotificationChannel();

            // 2. Foreground Service 시작
            startForeground(NOTIFICATION_ID, createNotification("초기화 중...", "배치 처리 모드로 시작합니다"));

            // 3. Device ID 가져오기
            deviceId = getDeviceId();

            // 4. ConfigManager 초기화 (singleton)
            configManager = ConfigManager.getInstance(this, SERVER_URL);

            // 5. WebView 초기화 (백그라운드에서)
            initializeWebView();

            // 6. JavaScript Interface 생성
            jsInterface = new JavaScriptInterface(this, webView, SERVER_URL, deviceId);
            jsInterface.setEventListener(new JavaScriptInterface.EventListener() {
                @Override
                public void onTaskStarted(int trafficId) {
                    currentTaskCount++;
                    int queueSize = taskExecutor != null ? taskExecutor.getQueueSize() : 0;
                    updateNotification("작업 실행 중",
                        String.format("Traffic ID: %d (큐: %d, 완료: %d, 실패: %d)",
                            trafficId, queueSize, completedTaskCount, failedTaskCount));
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

                    // 배치 모드: Callback 호출하여 다음 작업 트리거
                    if (currentCallback != null) {
                        Log.d(TAG, "Triggering next task via callback...");
                        TaskExecutor.TaskCompletionCallback callback = currentCallback;
                        currentCallback = null; // Clear before calling
                        callback.onComplete();
                    } else {
                        int queueSize = taskExecutor != null ? taskExecutor.getQueueSize() : 0;
                        updateNotification("대기 중",
                            String.format("큐: %d, 완료: %d, 실패: %d",
                                queueSize, completedTaskCount, failedTaskCount));
                    }
                }

                @Override
                public void onTaskError(int trafficId, String error) {
                    failedTaskCount++;
                    Log.e(TAG, String.format("Task error: %d - %s", trafficId, error));

                    // 배치 모드: 에러 발생해도 다음 작업 계속 처리
                    if (currentCallback != null) {
                        Log.d(TAG, "Error occurred, but continuing to next task...");
                        TaskExecutor.TaskCompletionCallback callback = currentCallback;
                        currentCallback = null;
                        callback.onComplete();
                    } else {
                        int queueSize = taskExecutor != null ? taskExecutor.getQueueSize() : 0;
                        updateNotification("대기 중",
                            String.format("큐: %d, 완료: %d, 실패: %d (마지막 에러: %s)",
                                queueSize, completedTaskCount, failedTaskCount, error));
                    }
                }
            });

            // WebView에 JavaScript Interface 추가
            webView.addJavascriptInterface(jsInterface, "AndroidInterface");

            // 7. TaskExecutor 초기화 및 시작 (배치 모드)
            taskExecutor = new TaskExecutor(this, SERVER_URL, deviceId);
            taskExecutor.setTaskExecutionListener(new TaskExecutor.TaskExecutionListener() {
                @Override
                public void onExecuteTask(int trafficId, String productName, String nvMid,
                                        String shortKeyword, String script,
                                        TaskExecutor.TaskCompletionCallback callback) {
                    Log.i(TAG, String.format("Executing task [%d]: product=%s, mid=%s (queue_size=%d)",
                        trafficId, productName, nvMid, taskExecutor.getQueueSize()));

                    // Callback 저장 (작업 완료 시 호출됨)
                    currentCallback = callback;

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

            // 8. 폴링 시작
            taskExecutor.startPolling();

            // 9. 초기 페이지 로드
            webView.loadUrl("https://m.naver.com/");

            Log.i(TAG, "Service initialization completed. Batch polling started.");

        } catch (Exception e) {
            Log.e(TAG, "Error initializing service", e);
            stopSelf();
        }
    }

    /**
     * WebView 초기화 (백그라운드에서)
     */
    private void initializeWebView() {
        webView = new WebView(this);

        // WebViewHelper로 초기화
        WebViewHelper.initializeWebView(this, webView, configManager);

        // 백그라운드에서도 실행되도록 WindowManager에 추가 (화면 크기 1x1)
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            1, 1,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        windowManager.addView(webView, params);

        Log.i(TAG, "WebView initialized in background");
    }

    /**
     * JavaScript 스크립트 주입
     */
    private void injectScript(String script, Runnable onComplete) {
        webView.evaluateJavascript(
            "(function() { return typeof window.NaverShoppingAutomation !== 'undefined'; })();",
            result -> {
                if ("true".equals(result)) {
                    Log.d(TAG, "Script already loaded, skipping injection");
                    if (onComplete != null) {
                        onComplete.run();
                    }
                } else {
                    Log.d(TAG, "Injecting script...");
                    webView.evaluateJavascript(script, scriptResult -> {
                        Log.i(TAG, "Script injected successfully");
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    });
                }
            }
        );
    }

    /**
     * Device ID 가져오기
     */
    private String getDeviceId() {
        // TODO: 실제 디바이스 ID 로직 구현
        // 예: Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)
        return "DEVICE_" + android.os.Build.MODEL + "_" + System.currentTimeMillis();
    }

    /**
     * Notification 채널 생성
     */
    private void createNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("네이버 쇼핑 트래픽 자동화 서비스");
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Notification 생성
     */
    private Notification createNotification(String title, String text) {
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);

        return notificationBuilder.build();
    }

    /**
     * Notification 업데이트
     */
    private void updateNotification(String title, String text) {
        if (notificationBuilder != null) {
            notificationBuilder.setContentTitle(title);
            notificationBuilder.setContentText(text);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
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
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
