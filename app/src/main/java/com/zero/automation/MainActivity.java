package com.zero.automation;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private boolean isServiceRunning = false;
    private TextView tvStatus;
    private TextView tvLog;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        scrollView = (ScrollView) tvLog.getParent();

        Button startButton = findViewById(R.id.btn_start_service);
        Button stopButton = findViewById(R.id.btn_stop_service);

        startButton.setOnClickListener(v -> startAutomationService());
        stopButton.setOnClickListener(v -> stopAutomationService());

        requestRuntimePermissions();
    }

    private void startAutomationService() {
        if (isServiceRunning) {
            Toast.makeText(this, "서비스가 이미 실행 중입니다", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, TrafficAutomationService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isServiceRunning = true;
        tvStatus.setText("상태: 실행 중");
        appendLog("서비스 시작됨");
        Toast.makeText(this, "자동화 서비스 시작됨", Toast.LENGTH_SHORT).show();
    }

    private void stopAutomationService() {
        if (!isServiceRunning) {
            Toast.makeText(this, "서비스가 실행 중이 아닙니다", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, TrafficAutomationService.class);
        stopService(serviceIntent);

        isServiceRunning = false;
        tvStatus.setText("상태: 중지됨");
        appendLog("서비스 중지됨");
        Toast.makeText(this, "자동화 서비스 중지됨", Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        tvLog.append(String.format("[%s] %s\n", timestamp, message));
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    100
                );
            }
        }

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
                appendLog("알림 권한 허용됨");
            } else {
                appendLog("알림 권한 거부됨");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 200) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    appendLog("오버레이 권한 허용됨");
                } else {
                    appendLog("오버레이 권한 거부됨");
                }
            }
        }
    }
}
