package com.zero.traffic;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.zero.traffic.service.ToggleReceiver;
import com.zero.traffic.service.TrafficService;
import com.zero.traffic.util.Logger;

/**
 * 초기 설정 + 서비스 제어 UI
 *
 * - 서버 URL 입력 (초기 1회)
 * - 시작/중지 버튼
 * - 상태 로그 표시
 */
public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "ZeroSettings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final int MAX_LOG_CHARS = 12000;

    private EditText etServerUrl;
    private Button btnStart;
    private Button btnStop;
    private Button btnToggleWebView;
    private TextView tvStatus;
    private TextView tvLog;

    private SharedPreferences prefs;
    private boolean serviceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // 프로그래밍 방식 UI (xml 레이아웃 없이)
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16);
        root.setPadding(pad, pad, pad, pad);

        // 타이틀
        TextView title = new TextView(this);
        title.setText("Zero Traffic v2");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dpToPx(16));
        root.addView(title);

        // 서버 URL
        TextView labelUrl = new TextView(this);
        labelUrl.setText("서버 URL:");
        labelUrl.setTextSize(14);
        root.addView(labelUrl);

        etServerUrl = new EditText(this);
        etServerUrl.setHint("https://zero-server-production.up.railway.app");
        etServerUrl.setSingleLine(true);
        etServerUrl.setTextSize(14);
        String savedUrl = prefs.getString(KEY_SERVER_URL, BuildConfig.SERVER_URL);
        etServerUrl.setText(savedUrl);
        root.addView(etServerUrl);

        // 버튼 행
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dpToPx(12), 0, dpToPx(12));

        btnStart = new Button(this);
        btnStart.setText("▶ 시작");
        btnStart.setOnClickListener(v -> startService());
        btnRow.addView(btnStart);

        btnStop = new Button(this);
        btnStop.setText("■ 중지");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopTrafficService());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stopParams.setMarginStart(dpToPx(8));
        btnStop.setLayoutParams(stopParams);
        btnRow.addView(btnStop);

        btnToggleWebView = new Button(this);
        btnToggleWebView.setText("👁 WebView");
        btnToggleWebView.setOnClickListener(v -> {
            if (ToggleReceiver.serviceRef != null) {
                ToggleReceiver.serviceRef.onToggleWebView();
                appendLog("WebView 토글");
            } else {
                appendLog("서비스 미실행");
            }
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toggleParams.setMarginStart(dpToPx(8));
        btnToggleWebView.setLayoutParams(toggleParams);
        btnRow.addView(btnToggleWebView);

        root.addView(btnRow);

        // 상태
        tvStatus = new TextView(this);
        tvStatus.setText("상태: 대기");
        tvStatus.setTextSize(16);
        tvStatus.setPadding(0, 0, 0, dpToPx(8));
        root.addView(tvStatus);

        // 구분선
        View divider = new View(this);
        divider.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
        divParams.setMargins(0, dpToPx(4), 0, dpToPx(8));
        divider.setLayoutParams(divParams);
        root.addView(divider);

        // 로그
        TextView labelLog = new TextView(this);
        labelLog.setText("로그:");
        labelLog.setTextSize(12);
        root.addView(labelLog);

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTextIsSelectable(true);
        root.addView(tvLog);

        scroll.addView(root);
        setContentView(scroll);

        // 자동 시작 — URL 저장되어 있으면 바로 시작 (빌드→설치→테스트 루프 자동화)
        String savedUrlCheck = prefs.getString(KEY_SERVER_URL, "");
        if (!savedUrlCheck.isEmpty()) {
            // 약간 딜레이 후 자동 시작 (UI 렌더링 완료 대기)
            root.postDelayed(this::startService, 500);
        }
    }

    private void startService() {
        String url = etServerUrl.getText().toString().trim();
        if (url.isEmpty()) {
            tvStatus.setText("상태: 서버 URL을 입력하세요");
            return;
        }

        // 오버레이 권한 확인
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            tvStatus.setText("상태: 오버레이 권한 필요 → 설정에서 허용해주세요");
            Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(overlayIntent);
            return;
        }

        // URL 저장
        prefs.edit().putString(KEY_SERVER_URL, url).apply();

        // 서비스 시작
        Intent intent = new Intent(this, TrafficService.class);
        intent.putExtra("server_url", url);
        startForegroundService(intent);

        serviceRunning = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        etServerUrl.setEnabled(false);
        tvStatus.setText("상태: 실행 중");

        appendLog("서비스 시작: " + url);
        Logger.i("서비스 시작 (UI)");
    }

    private void stopTrafficService() {
        Intent intent = new Intent(this, TrafficService.class);
        stopService(intent);

        serviceRunning = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        etServerUrl.setEnabled(true);
        tvStatus.setText("상태: 중지됨");

        appendLog("서비스 중지");
    }

    private void appendLog(String msg) {
        String current = tvLog.getText().toString();
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String next = "[" + time + "] " + msg + "\n" + current;
        if (next.length() > MAX_LOG_CHARS) {
            next = next.substring(0, MAX_LOG_CHARS);
        }
        tvLog.setText(next);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
