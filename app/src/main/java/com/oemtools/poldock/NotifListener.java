package com.oemtools.poldock;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 알림 리스너. MediaSessionManager.getActiveSessions() 용 토큰이자,
 * 현재 떠 있는 알림의 패키지 집합을 추적해 특정 앱(예: TMAP) 실행 여부를 알려준다.
 * (포그라운드 권한 없이 '그 앱이 떠 있는지'를 알 수 있는 경로)
 */
public class NotifListener extends NotificationListenerService {

    private static final Set<String> active =
            Collections.synchronizedSet(new HashSet<String>());

    /** 현재 알림이 떠 있는 패키지 집합(복사본) */
    static Set<String> activePackages() {
        synchronized (active) {
            return new HashSet<>(active);
        }
    }

    static boolean hasPackage(String pkg) {
        if (pkg == null) return false;
        synchronized (active) {
            return active.contains(pkg);
        }
    }

    private void refreshAll() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            Set<String> s = new HashSet<>();
            if (all != null) for (StatusBarNotification sbn : all) s.add(sbn.getPackageName());
            synchronized (active) {
                active.clear();
                active.addAll(s);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onListenerConnected() {
        refreshAll();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null) active.add(sbn.getPackageName());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 같은 패키지의 다른 알림이 남아 있을 수 있으니 전체 재조회
        refreshAll();
    }
}
