package com.oemtools.poldock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 차량 부팅(시동) 완료 시 독을 자동으로 띄운다. (설정에서 끌 수 있음) */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        if (!OverlayService.canOverlay(ctx)) return;   // 항상 자동 실행 (설정 없음)
        try {
            ctx.startService(new Intent(ctx, OverlayService.class));
        } catch (Throwable ignored) {
        }
    }
}
