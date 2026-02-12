package com.zero.traffic.network;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * 네트워크 유틸리티
 */
public class NetworkUtils {

    /**
     * 현재 기기의 로컬 IP 주소
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(
                    NetworkInterface.getNetworkInterfaces());

            for (NetworkInterface ni : interfaces) {
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 핫스팟 게이트웨이 IP (보통 192.168.43.1)
     */
    public static String getHotspotGatewayIp() {
        return "192.168.43.1";
    }
}
