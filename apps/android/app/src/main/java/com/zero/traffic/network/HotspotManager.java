package com.zero.traffic.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.zero.traffic.util.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 대장봇 핫스팟 관리
 *
 * Android 버전별 분기:
 * - Android 7 이하: WifiManager reflection
 * - Android 8+: ConnectivityManager.startTethering (reflection)
 * - Android 11+: TetheringManager (reflection, 제조사 커스텀 ROM 의존)
 *
 * ※ 실제 환경에서는 WRITE_SETTINGS, TETHER_PRIVILEGED 권한 필요
 *   시스템 앱이거나 제조사 ROM에서 허용해야 동작
 *   실패 시 → 사용자가 수동으로 핫스팟 켜야 함
 */
public class HotspotManager {
    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connManager;

    private boolean hotspotEnabled = false;

    public HotspotManager(Context context) {
        this.context = context;
        this.wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        this.connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * 핫스팟 켜기
     *
     * @param ssid     SSID (예: "zero-g1")
     * @param password 비밀번호 (예: "zero0001")
     * @return 성공 여부
     */
    public boolean enableHotspot(String ssid, String password) {
        Logger.i("[Hotspot] 켜기 시도: " + ssid);

        // WiFi가 켜져있으면 먼저 끄기 (핫스팟과 동시 사용 불가)
        if (wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(false);
            sleep(1000);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8+ : startTethering
            return enableHotspotOreo(ssid, password);
        } else {
            // Android 7 이하 : WifiManager reflection
            return enableHotspotLegacy(ssid, password);
        }
    }

    /**
     * 핫스팟 끄기
     */
    public void disableHotspot() {
        if (!hotspotEnabled) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // stopTethering
                Method stopMethod = connManager.getClass()
                        .getDeclaredMethod("stopTethering", int.class);
                stopMethod.setAccessible(true);
                stopMethod.invoke(connManager, 0); // TETHERING_WIFI = 0
            } else {
                // legacy
                Method setMethod = wifiManager.getClass()
                        .getDeclaredMethod("setWifiApEnabled",
                                android.net.wifi.WifiConfiguration.class, boolean.class);
                setMethod.setAccessible(true);
                setMethod.invoke(wifiManager, null, false);
            }
            hotspotEnabled = false;
            Logger.i("[Hotspot] 끔");
        } catch (Exception e) {
            Logger.e("[Hotspot] 끄기 실패: " + e.getMessage());
        }
    }

    // ── Android 8+ (Oreo) ───────────────────────────────

    private boolean enableHotspotOreo(String ssid, String password) {
        try {
            // SSID/Password 설정은 Android 8+에서 제한적
            // 대부분의 커스텀 ROM에서는 시스템 설정을 통해야 함
            // 여기서는 기본 핫스팟만 켜고, SSID는 시스템 기본값 사용

            Class<?> callbackClass = Class.forName(
                    "android.net.ConnectivityManager$OnStartTetheringCallback");

            // startTethering(int type, boolean showProvisioningUi, callback, handler)
            Method startMethod = connManager.getClass().getDeclaredMethod(
                    "startTethering", int.class, boolean.class,
                    callbackClass, Handler.class);
            startMethod.setAccessible(true);

            Handler handler = new Handler(Looper.getMainLooper());
            // OnStartTetheringCallback은 hidden abstract class라 동적 Proxy 생성 불가.
            // null callback으로 시작 요청 후 tether 상태를 polling으로 확인한다.
            startMethod.invoke(connManager, 0, false, null, handler);

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
            hotspotEnabled = false;
            while (System.currentTimeMillis() < deadline) {
                if (isTetheringActive()) {
                    hotspotEnabled = true;
                    break;
                }
                sleep(500);
            }

            if (hotspotEnabled) {
                Logger.i("[Hotspot] Oreo+ 핫스팟 ON");
                // SSID 변경 시도 (reflection, 실패해도 무시)
                trySetSsid(ssid, password);
            } else {
                Logger.w("[Hotspot] Oreo+ 시작 요청 후 활성화 확인 실패");
            }
            return hotspotEnabled;

        } catch (Exception e) {
            Logger.e("[Hotspot] Oreo+ 실패: " + e.getMessage());
            Logger.i("[Hotspot] 수동 설정 필요: 설정 → 핫스팟 → SSID: " + ssid + " / PW: " + password);
            return false;
        }
    }

    private boolean isTetheringActive() {
        try {
            Method getMethod = connManager.getClass().getDeclaredMethod("getTetheredIfaces");
            getMethod.setAccessible(true);
            Object result = getMethod.invoke(connManager);
            if (result instanceof String[]) {
                return ((String[]) result).length > 0;
            }
        } catch (Exception e) {
            Logger.w("[Hotspot] 상태 확인 실패: " + e.getMessage());
        }
        return false;
    }

    // ── Android 7 이하 (Legacy) ─────────────────────────

    @SuppressWarnings("deprecation")
    private boolean enableHotspotLegacy(String ssid, String password) {
        try {
            android.net.wifi.WifiConfiguration config = new android.net.wifi.WifiConfiguration();
            config.SSID = ssid;
            config.preSharedKey = password;
            config.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK);

            Method setMethod = wifiManager.getClass()
                    .getDeclaredMethod("setWifiApEnabled",
                            android.net.wifi.WifiConfiguration.class, boolean.class);
            setMethod.setAccessible(true);

            hotspotEnabled = (boolean) setMethod.invoke(wifiManager, config, true);
            if (hotspotEnabled) {
                Logger.i("[Hotspot] Legacy 핫스팟 ON: " + ssid);
            }
            return hotspotEnabled;

        } catch (Exception e) {
            Logger.e("[Hotspot] Legacy 실패: " + e.getMessage());
            return false;
        }
    }

    // ── SSID 변경 시도 ─────────────────────────────────

    private void trySetSsid(String ssid, String password) {
        try {
            // Android 8+ SoftApConfiguration (Android 11+)
            if (Build.VERSION.SDK_INT >= 30) {
                Class<?> sapConfigBuilder = Class.forName(
                        "android.net.wifi.SoftApConfiguration$Builder");
                Object builder = sapConfigBuilder.newInstance();

                Method setSsid = sapConfigBuilder.getDeclaredMethod("setSsid", String.class);
                setSsid.invoke(builder, ssid);

                Method setPassphrase = sapConfigBuilder.getDeclaredMethod(
                        "setPassphrase", String.class, int.class);
                setPassphrase.invoke(builder, password, 2); // SECURITY_TYPE_WPA2_PSK

                Method buildMethod = sapConfigBuilder.getDeclaredMethod("build");
                Object sapConfig = buildMethod.invoke(builder);

                Method setSapConfig = wifiManager.getClass().getDeclaredMethod(
                        "setSoftApConfiguration", sapConfig.getClass());
                setSapConfig.invoke(wifiManager, sapConfig);

                Logger.i("[Hotspot] SSID 설정 성공: " + ssid);
            }
        } catch (Exception e) {
            Logger.w("[Hotspot] SSID 변경 실패 (기본 SSID 사용): " + e.getMessage());
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isHotspotEnabled() {
        return hotspotEnabled;
    }
}
