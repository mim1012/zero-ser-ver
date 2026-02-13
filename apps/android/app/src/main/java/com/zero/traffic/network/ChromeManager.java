package com.zero.traffic.network;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.webkit.WebView;

import com.zero.traffic.server.ApiClient;
import com.zero.traffic.util.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

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
 * 2. 미달이면 Chrome APK 다운로드 (/chrome/download)
 * 3. pm install -r 사일런트 설치 (shell 권한)
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

        // pm install 사일런트 설치
        boolean installed = installApkSilent(apkFile);
        cleanupApk();
        if (installed) {
            setWebViewProvider();
            return true;
        }

        return currentVersion > 0;
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
     * pm install -r 사일런트 설치 (shell 권한)
     * UI 없이 백그라운드에서 즉시 설치
     */
    private boolean installApkSilent(File apkFile) {
        try {
            String cmd = "pm install -r " + apkFile.getAbsolutePath();
            Logger.i("[Chrome] 사일런트 설치: " + cmd);

            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            String result = output.toString().trim();
            Logger.i("[Chrome] pm install 결과: " + result + " (exit=" + exitCode + ")");

            if (result.contains("Success")) {
                Logger.i("[Chrome] 설치 완료!");
                return true;
            } else {
                Logger.e("[Chrome] 설치 실패: " + result);
                return false;
            }

        } catch (Exception e) {
            Logger.e("[Chrome] 사일런트 설치 실패: " + e.getMessage());
            return false;
        }
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
