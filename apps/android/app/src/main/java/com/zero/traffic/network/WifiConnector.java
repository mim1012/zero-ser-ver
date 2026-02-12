package com.zero.traffic.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;

import com.zero.traffic.util.Logger;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 쫄병봇 WiFi 연결
 *
 * 대장봇의 핫스팟 SSID에 자동 연결
 * Android 버전별 분기:
 * - Android 9 이하: WifiManager.addNetwork
 * - Android 10+: WifiNetworkSpecifier + NetworkRequest
 */
public class WifiConnector {
    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connManager;

    private Network connectedNetwork;
    private ConnectivityManager.NetworkCallback networkCallback;

    public WifiConnector(Context context) {
        this.context = context;
        this.wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        this.connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * 핫스팟에 연결
     *
     * @param ssid      대장봇 SSID (예: "zero-g1")
     * @param password  비밀번호
     * @param timeoutMs 연결 대기 시간
     * @return 성공 여부
     */
    public boolean connectToHotspot(String ssid, String password, long timeoutMs) {
        Logger.i("[WiFi] 연결 시도: " + ssid);
        disconnect(); // 이전 콜백/바인딩 정리

        // WiFi 켜기
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
            sleep(2000);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+
            return connectAndroid10(ssid, password, timeoutMs);
        } else {
            // Android 9 이하
            return connectLegacy(ssid, password, timeoutMs);
        }
    }

    /**
     * 연결 해제
     */
    public void disconnect() {
        try {
            if (networkCallback != null && connManager != null) {
                connManager.unregisterNetworkCallback(networkCallback);
                networkCallback = null;
            }
            if (connectedNetwork != null && connManager != null) {
                connManager.bindProcessToNetwork(null);
                connectedNetwork = null;
            }
            Logger.i("[WiFi] 연결 해제");
        } catch (Exception e) {
            Logger.w("[WiFi] 해제 실패: " + e.getMessage());
        }
    }

    // ── Android 10+ (WifiNetworkSpecifier) ──────────────

    private boolean connectAndroid10(String ssid, String password, long timeoutMs) {
        try {
            WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build();

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build();

            CountDownLatch latch = new CountDownLatch(1);
            final boolean[] result = {false};

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Logger.i("[WiFi] 네트워크 연결됨: " + ssid);
                    connectedNetwork = network;
                    // 이 네트워크를 기본으로 바인딩 (트래픽이 이 경로로 나감)
                    connManager.bindProcessToNetwork(network);
                    result[0] = true;
                    latch.countDown();
                }

                @Override
                public void onUnavailable() {
                    Logger.w("[WiFi] 네트워크 사용 불가: " + ssid);
                    result[0] = false;
                    latch.countDown();
                }

                @Override
                public void onLost(Network network) {
                    Logger.w("[WiFi] 네트워크 끊김: " + ssid);
                    connectedNetwork = null;
                    connManager.bindProcessToNetwork(null);
                }
            };

            connManager.requestNetwork(request, networkCallback);

            // 결과 대기
            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            boolean success = completed && result[0];
            if (!success) {
                cleanupNetworkCallback();
            }
            return success;

        } catch (Exception e) {
            Logger.e("[WiFi] Android 10+ 연결 실패: " + e.getMessage());
            cleanupNetworkCallback();
            return false;
        }
    }

    // ── Android 9 이하 (Legacy WifiManager) ─────────────

    @SuppressWarnings("deprecation")
    private boolean connectLegacy(String ssid, String password, long timeoutMs) {
        try {
            // 기존 설정 제거
            List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
            if (configs != null) {
                for (WifiConfiguration config : configs) {
                    if (config.SSID != null && config.SSID.equals("\"" + ssid + "\"")) {
                        wifiManager.removeNetwork(config.networkId);
                    }
                }
            }

            // 새 설정 추가
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + ssid + "\"";
            config.preSharedKey = "\"" + password + "\"";
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);

            int netId = wifiManager.addNetwork(config);
            if (netId == -1) {
                Logger.e("[WiFi] addNetwork 실패");
                return false;
            }

            wifiManager.disconnect();
            boolean enabled = wifiManager.enableNetwork(netId, true);
            wifiManager.reconnect();

            if (!enabled) {
                Logger.e("[WiFi] enableNetwork 실패");
                return false;
            }

            // 연결 대기
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < timeoutMs) {
                android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getSSID() != null
                        && info.getSSID().equals("\"" + ssid + "\"")
                        && info.getIpAddress() != 0) {
                    Logger.i("[WiFi] Legacy 연결 성공: " + ssid);
                    return true;
                }
                sleep(1000);
            }

            Logger.w("[WiFi] Legacy 연결 타임아웃");
            return false;

        } catch (Exception e) {
            Logger.e("[WiFi] Legacy 연결 실패: " + e.getMessage());
            return false;
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanupNetworkCallback() {
        try {
            if (networkCallback != null && connManager != null) {
                connManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {
        } finally {
            networkCallback = null;
        }
    }

    public boolean isConnected() {
        return connectedNetwork != null;
    }
}
