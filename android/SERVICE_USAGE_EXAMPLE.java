/**
 * TrafficAutomationService 사용 예제
 *
 * 이 파일은 네이버 쇼핑 자동화 서비스를 시작/중지하는 방법을 보여줍니다.
 */

package com.zero.automation;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/**
 * MainActivity - TrafficAutomationService 제어
 */
public class MainActivity extends AppCompatActivity {

    private boolean isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startButton = findViewById(R.id.btn_start_service);
        Button stopButton = findViewById(R.id.btn_stop_service);

        // 서비스 시작 버튼
        startButton.setOnClickListener(v -> {
            startAutomationService();
        });

        // 서비스 중지 버튼
        stopButton.setOnClickListener(v -> {
            stopAutomationService();
        });

        // 런타임 권한 요청 (Android 13+: Notification, Android 6+: Overlay)
        requestRuntimePermissions();
    }

    /**
     * 자동화 서비스 시작
     */
    private void startAutomationService() {
        if (isServiceRunning) {
            Toast.makeText(this, "서비스가 이미 실행 중입니다", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, TrafficAutomationService.class);

        // Android 8.0+ (API 26+)에서는 startForegroundService() 필수
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isServiceRunning = true;
        Toast.makeText(this, "자동화 서비스 시작됨", Toast.LENGTH_SHORT).show();
    }

    /**
     * 자동화 서비스 중지
     */
    private void stopAutomationService() {
        if (!isServiceRunning) {
            Toast.makeText(this, "서비스가 실행 중이 아닙니다", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, TrafficAutomationService.class);
        stopService(serviceIntent);

        isServiceRunning = false;
        Toast.makeText(this, "자동화 서비스 중지됨", Toast.LENGTH_SHORT).show();
    }

    /**
     * 런타임 권한 요청
     */
    private void requestRuntimePermissions() {
        // Android 13+ (API 33+): POST_NOTIFICATIONS 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    100
                );
            }
        }

        // Android 6.0+ (API 23+): SYSTEM_ALERT_WINDOW 권한 (백그라운드 WebView용)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this,
                    "백그라운드 WebView를 위해 '다른 앱 위에 표시' 권한이 필요합니다",
                    Toast.LENGTH_LONG).show();

                Intent intent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(intent, 200);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 100) {
            if (grantResults.length > 0 &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "알림 권한이 허용되었습니다", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                    "알림 권한이 거부되었습니다. 앱 설정에서 허용해주세요",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 200) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "'다른 앱 위에 표시' 권한이 허용되었습니다", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                        "'다른 앱 위에 표시' 권한이 필요합니다. 백그라운드 WebView가 제대로 작동하지 않을 수 있습니다",
                        Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}


/**
 * ============================================================
 * layout/activity_main.xml 레이아웃 예제
 * ============================================================
 *
 * <?xml version="1.0" encoding="utf-8"?>
 * <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:orientation="vertical"
 *     android:padding="16dp"
 *     android:gravity="center">
 *
 *     <TextView
 *         android:layout_width="wrap_content"
 *         android:layout_height="wrap_content"
 *         android:text="네이버 쇼핑 자동화"
 *         android:textSize="24sp"
 *         android:textStyle="bold"
 *         android:layout_marginBottom="32dp" />
 *
 *     <TextView
 *         android:layout_width="wrap_content"
 *         android:layout_height="wrap_content"
 *         android:text="서버에서 작업을 폴링하여 자동으로 실행합니다\n(5분 간격)"
 *         android:textSize="14sp"
 *         android:gravity="center"
 *         android:layout_marginBottom="32dp" />
 *
 *     <Button
 *         android:id="@+id/btn_start_service"
 *         android:layout_width="match_parent"
 *         android:layout_height="wrap_content"
 *         android:text="서비스 시작"
 *         android:textSize="18sp"
 *         android:layout_marginBottom="16dp" />
 *
 *     <Button
 *         android:id="@+id/btn_stop_service"
 *         android:layout_width="match_parent"
 *         android:layout_height="wrap_content"
 *         android:text="서비스 중지"
 *         android:textSize="18sp" />
 *
 * </LinearLayout>
 */


/**
 * ============================================================
 * 자동 시작 (Boot Receiver) - 선택 사항
 * ============================================================
 */

/*
package com.zero.automation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed, starting TrafficAutomationService");

            Intent serviceIntent = new Intent(context, TrafficAutomationService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
*/


/**
 * ============================================================
 * AndroidManifest.xml에 추가할 내용
 * ============================================================
 *
 * <!-- 권한 -->
 * <uses-permission android:name="android.permission.INTERNET" />
 * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 * <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 * <!-- 서비스 -->
 * <service
 *     android:name="com.zero.automation.TrafficAutomationService"
 *     android:enabled="true"
 *     android:exported="false"
 *     android:foregroundServiceType="dataSync"
 *     android:stopWithTask="false" />
 *
 * <!-- Boot Receiver (선택 사항) -->
 * <receiver
 *     android:name="com.zero.automation.BootReceiver"
 *     android:enabled="true"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *     </intent-filter>
 * </receiver>
 */
