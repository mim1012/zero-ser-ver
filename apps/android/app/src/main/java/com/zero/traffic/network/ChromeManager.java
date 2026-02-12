package com.zero.traffic.network;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;

import androidx.core.content.FileProvider;

import com.zero.traffic.server.ApiClient;
import com.zero.traffic.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Chrome APK 관리자
 *
 * 공기계(Play Store 없음)에서 Chrome을 서버로부터 다운받아 설치.
 * Chrome이 WebView 엔진 제공자가 되어 최신 TLS 핑거프린트 사용.
 *
 * 흐름:
 * 1. Chrome 설치 여부 + 버전 체크
 * 2. 서버에서 최소 버전 확인 (/chrome/version)
 * 3. 미달이면 Chrome APK 다운로드 (/chrome/download)
 * 4. PackageInstaller로 설치 (Accessibility Service가 "설치" 버튼 자동 터치)
 */
public class ChromeManager {
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String CHROME_APK_DIR = "chrome_apk";
    private static final int MIN_CHROME_VERSION = 125; // Chrome 125+

    private final Context context;
    private final ApiClient api;
    private final OkHttpClient httpClient;

    public ChromeManager(Context context, ApiClient api) {
        this.context = context;
        this.api = api;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS) // APK 다운로드 시간
                .build();
    }

    /**
     * Chrome 상태 확인 + 필요 시 설치
     * @return true = Chrome 준비 완료
     */
    public boolean ensureChromeReady() {
        int currentVersion = getInstalledChromeVersion();

        if (currentVersion >= MIN_CHROME_VERSION) {
            Logger.i("[Chrome] OK: Chrome " + currentVersion + " 설치됨");
            setWebViewProvider();
            return true;
        }

        if (currentVersion > 0) {
            Logger.w("[Chrome] 버전 낮음: Chrome " + currentVersion + " → " + MIN_CHROME_VERSION + " 필요");
        } else {
            Logger.w("[Chrome] 미설치 → 서버에서 다운로드");
        }

        // 서버에서 Chrome APK 다운로드
        File apkFile = downloadChromeApk();
        if (apkFile == null) {
            Logger.e("[Chrome] 다운로드 실패");
            return currentVersion > 0; // 구버전이라도 있으면 true
        }

        // 설치 시작
        boolean installStarted = installApk(apkFile);
        if (installStarted) {
            Logger.i("[Chrome] 설치 시작됨 — AccessibilityService가 자동 확인");
            // 설치 완료 대기 (최대 60초)
            return waitForInstall(60000);
        }

        return false;
    }

    /**
     * 설치된 Chrome 주 버전 반환 (0 = 미설치)
     */
    public int getInstalledChromeVersion() {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(CHROME_PACKAGE, 0);
            String versionName = info.versionName; // "125.0.6422.165"
            String major = versionName.split("\\.")[0];
            int version = Integer.parseInt(major);
            Logger.i("[Chrome] 설치 버전: " + versionName + " (major=" + version + ")");
            return version;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        } catch (Exception e) {
            Logger.e("[Chrome] 버전 확인 실패: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 서버에서 Chrome APK 다운로드
     */
    private File downloadChromeApk() {
        try {
            String downloadUrl = api.getBaseUrl() + "/chrome/download";
            Logger.i("[Chrome] 다운로드: " + downloadUrl);

            Request request = new Request.Builder()
                    .url(downloadUrl)
                    .build();

            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                Logger.e("[Chrome] 다운로드 HTTP " + response.code());
                return null;
            }

            // APK 저장
            File dir = new File(context.getFilesDir(), CHROME_APK_DIR);
            if (!dir.exists()) dir.mkdirs();

            File apkFile = new File(dir, "chrome.apk");
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(apkFile)) {
                byte[] buffer = new byte[8192];
                int read;
                long total = 0;
                long nextProgressLog = 10L * 1024 * 1024;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                    if (total >= nextProgressLog) { // 10MB 단위 진행 로그
                        Logger.i("[Chrome] 다운로드 중: " + (total / 1024 / 1024) + "MB");
                        nextProgressLog += 10L * 1024 * 1024;
                    }
                }
                Logger.i("[Chrome] 다운로드 완료: " + (total / 1024 / 1024) + "MB");
            }

            return apkFile;

        } catch (Exception e) {
            Logger.e("[Chrome] 다운로드 실패: " + e.getMessage());
            return null;
        }
    }

    /**
     * APK 설치 (시스템 인스톨러)
     * AccessibilityService가 설치 확인 버튼 자동 터치
     */
    private boolean installApk(File apkFile) {
        try {
            // 알 수 없는 소스 설치 허용 확인
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    Logger.w("[Chrome] 알 수 없는 소스 설치 미허용 → 설정 열기");
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    settingsIntent.setData(Uri.parse("package:" + context.getPackageName()));
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(settingsIntent);
                    // AccessibilityService가 "허용" 자동 터치
                    sleep(3000);
                }
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ FileProvider 필요
                apkUri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

            return true;

        } catch (Exception e) {
            Logger.e("[Chrome] 설치 시작 실패: " + e.getMessage());
            return false;
        }
    }

    /**
     * 설치 완료 대기
     */
    private boolean waitForInstall(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (getInstalledChromeVersion() >= MIN_CHROME_VERSION) {
                Logger.i("[Chrome] 설치 완료 확인!");
                setWebViewProvider();
                // 다운로드한 APK 삭제
                cleanupApk();
                return true;
            }
            sleep(2000);
        }
        Logger.w("[Chrome] 설치 대기 타임아웃");
        return false;
    }

    /**
     * Chrome을 WebView 제공자로 설정
     * Android 7+에서 Chrome이 설치되면 자동으로 WebView 제공자가 됨
     */
    private void setWebViewProvider() {
        try {
            // 현재 WebView 제공자 로그
            PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
            if (webViewPackage != null) {
                Logger.i("[Chrome] WebView 제공자: " + webViewPackage.packageName
                        + " v" + webViewPackage.versionName);
            }
        } catch (Exception e) {
            Logger.w("[Chrome] WebView 제공자 확인 실패: " + e.getMessage());
        }
    }

    private void cleanupApk() {
        try {
            File dir = new File(context.getFilesDir(), CHROME_APK_DIR);
            File apk = new File(dir, "chrome.apk");
            if (apk.exists()) apk.delete();
        } catch (Exception ignored) {}
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
