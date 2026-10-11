package com.oemtools.poldock;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 포그라운드 앱 패키지를 감지해 홈 화면/다른 앱을 구분한다.
 * (사용정보 접근 대신 사용 — 접근성 설정에서 켜면 동작)
 */
public class FgAccessibilityService extends AccessibilityService {

    static volatile boolean running;
    static volatile String lastPackage;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String s = p.toString();
        // 자기 자신(독 오버레이)·시스템 UI 창 변경은 무시 — 실제 앱 전환만 반영
        if (s.isEmpty() || s.equals(getPackageName())) return;
        if (s.equals("android") || s.contains("systemui") || s.contains("inputmethod")) return;
        lastPackage = s;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
