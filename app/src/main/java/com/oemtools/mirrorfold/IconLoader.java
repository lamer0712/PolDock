package com.oemtools.mirrorfold;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.util.HashMap;

/**
 * 바로가기 아이콘 해석.
 * 1) iconPkg/iconRes 지정 → 그 패키지의 드로어블을 런타임 로드 (OEM 기능 아이콘)
 * 2) 앱 실행(pkg) → 그 앱 아이콘
 * 3) 그 외 → 인텐트가 가리키는 액티비티/앱 아이콘
 */
public class IconLoader {

    private static final HashMap<String, Drawable> cache = new HashMap<>();

    static Drawable load(Context ctx, Shortcut s) {
        String ck = s.iconPkg + "/" + s.iconRes + "/" + s.pkg + "/" + s.action + s.extraValue;
        Drawable c = cache.get(ck);
        if (c != null) return c;
        Drawable d = resolve(ctx, s);
        if (d != null) cache.put(ck, d);
        return d;
    }

    private static Drawable resolve(Context ctx, Shortcut s) {
        // 1) 지정된 패키지 리소스 (OEM 아이콘)
        if (!TextUtils.isEmpty(s.iconPkg) && !TextUtils.isEmpty(s.iconRes)) {
            try {
                Context pc = ctx.createPackageContext(s.iconPkg, 0);
                int id = pc.getResources().getIdentifier(s.iconRes, "drawable", s.iconPkg);
                if (id != 0) return pc.getResources().getDrawable(id, null);
            } catch (Throwable ignored) {
            }
        }
        PackageManager pm = ctx.getPackageManager();
        // 2) 앱 아이콘
        if (!TextUtils.isEmpty(s.pkg)) {
            try {
                return pm.getApplicationIcon(s.pkg);
            } catch (Throwable ignored) {
            }
        }
        // 3) 인텐트 대상 아이콘
        try {
            Intent i = s.toIntent(ctx);
            if (i != null) {
                ResolveInfo ri = pm.resolveActivity(i, 0);
                if (ri != null) return ri.loadIcon(pm);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
