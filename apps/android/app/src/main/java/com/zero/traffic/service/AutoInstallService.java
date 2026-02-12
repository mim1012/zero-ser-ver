package com.zero.traffic.service;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.zero.traffic.util.Logger;

import java.util.List;

/**
 * AccessibilityService — APK 자동 설치 + 브라우저 자동화
 *
 * 역할:
 * 1. Chrome APK 설치 시 "설치" / "완료" 버튼 자동 터치
 * 2. "알 수 없는 소스" 허용 자동 터치
 * 3. 향후: Chrome 브라우저 UI 제어 (ADB 없이)
 */
public class AutoInstallService extends AccessibilityService {

    // 패키지 인스톨러 패키지명들
    private static final String[] INSTALLER_PACKAGES = {
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.android.settings"
    };

    // 자동 클릭할 버튼 텍스트 (한국어 + 영어)
    private static final String[] INSTALL_BUTTONS = {
            "설치", "Install", "INSTALL",
            "허용", "Allow", "ALLOW",
            "완료", "Done", "DONE",
            "다음", "Next", "NEXT",
            "업데이트", "Update", "UPDATE",
            "확인", "OK", "ok"
    };

    // 거부해야 할 버튼
    private static final String[] DENY_BUTTONS = {
            "열기", "Open", "OPEN"  // 설치 후 열기는 우리 APK가 관리
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null
                ? event.getPackageName().toString() : "";

        // 패키지 인스톨러 이벤트만 처리
        if (!isInstallerPackage(packageName)) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleInstallerEvent();
        }
    }

    private void handleInstallerEvent() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // "설치" / "허용" / "완료" 버튼 찾아서 클릭
            for (String buttonText : INSTALL_BUTTONS) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(buttonText);
                if (nodes != null && !nodes.isEmpty()) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node.isClickable() && node.isEnabled()) {
                            Logger.i("[AutoInstall] 자동 클릭: " + buttonText);
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return;
                        }
                        // 부모가 클릭 가능한 경우
                        AccessibilityNodeInfo parent = node.getParent();
                        if (parent != null && parent.isClickable() && parent.isEnabled()) {
                            Logger.i("[AutoInstall] 자동 클릭 (부모): " + buttonText);
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.w("[AutoInstall] 이벤트 처리 실패: " + e.getMessage());
        } finally {
            root.recycle();
        }
    }

    private boolean isInstallerPackage(String packageName) {
        for (String pkg : INSTALLER_PACKAGES) {
            if (pkg.equals(packageName)) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        Logger.w("[AutoInstall] 서비스 중단됨");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Logger.i("[AutoInstall] AccessibilityService 연결됨");
    }
}
