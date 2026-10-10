package com.oemtools.poldock;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;

/**
 * 도크 바로가기 하나. 뚫린 exported 통로(딥링크 인텐트)나 앱 실행을 표현한다.
 *
 * - action 이 있으면 암시적 인텐트로 실행 (딥링크).
 * - action 이 비고 pkg 만 있으면 그 앱의 런처 인텐트로 실행.
 * - extraKey/extraValue 는 문자열 extra 하나 (예: sub_fragment_id).
 */
public class Shortcut {
    public String label = "";
    public String action = "";
    public String category = "";
    public String data = "";
    public String extraKey = "";
    public String extraValue = "";
    public String extraType = "s";   // "s"=문자열, "i"=정수(10진수 또는 0x..)
    public String pkg = "";
    public String iconPkg = "";      // OEM 아이콘을 꺼낼 패키지 (예: com.android.car.settings)
    public String iconRes = "";      // 드로어블 리소스 이름 (예: ic_fav_fold_mirror_48)

    public Shortcut() {
    }

    public Shortcut(String label, String action, String category,
                    String extraKey, String extraValue, String pkg) {
        this(label, action, category, extraKey, extraValue, "s", pkg);
    }

    public Shortcut(String label, String action, String category,
                    String extraKey, String extraValue, String extraType, String pkg) {
        this.label = label;
        this.action = action;
        this.category = category;
        this.extraKey = extraKey;
        this.extraValue = extraValue;
        this.extraType = extraType;
        this.pkg = pkg;
    }

    Shortcut icon(String iconPkg, String iconRes) {
        this.iconPkg = iconPkg;
        this.iconRes = iconRes;
        return this;
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("label", label);
        o.put("action", action);
        o.put("category", category);
        o.put("data", data);
        o.put("extraKey", extraKey);
        o.put("extraValue", extraValue);
        o.put("extraType", extraType);
        o.put("pkg", pkg);
        o.put("iconPkg", iconPkg);
        o.put("iconRes", iconRes);
        return o;
    }

    static Shortcut fromJson(JSONObject o) {
        Shortcut s = new Shortcut();
        s.label = o.optString("label");
        s.action = o.optString("action");
        s.category = o.optString("category");
        s.data = o.optString("data");
        s.extraKey = o.optString("extraKey");
        s.extraValue = o.optString("extraValue");
        s.extraType = o.optString("extraType", "s");
        s.pkg = o.optString("pkg");
        s.iconPkg = o.optString("iconPkg");
        s.iconRes = o.optString("iconRes");
        return s;
    }

    /** 실행할 인텐트 생성. 실패하면 null. */
    Intent toIntent(Context ctx) {
        Intent i;
        if (TextUtils.isEmpty(action) && !TextUtils.isEmpty(pkg)) {
            // 앱 런처 실행: 기존 인스턴스 재사용
            i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return null;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            i = new Intent(TextUtils.isEmpty(action) ? Intent.ACTION_MAIN : action);
            if (!TextUtils.isEmpty(category)) i.addCategory(category);
            if (!TextUtils.isEmpty(data)) i.setData(Uri.parse(data));
            if (!TextUtils.isEmpty(pkg)) i.setPackage(pkg);
            putExtra(i);
            // CLEAR_TASK: 대상 작업이 이미 떠 있어도 새 인텐트(+extra)로 다시 시작 →
            // "이전 설정 화면이 그냥 앞으로 나오는" 문제 방지
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        return i;
    }

    private void putExtra(Intent i) {
        if (TextUtils.isEmpty(extraKey)) return;
        if ("i".equals(extraType)) {
            try {
                String v = extraValue.trim();
                long n = (v.startsWith("0x") || v.startsWith("0X"))
                        ? Long.parseLong(v.substring(2), 16) : Long.parseLong(v);
                i.putExtra(extraKey, (int) n);
                return;
            } catch (Exception ignored) {
                // 숫자 파싱 실패 시 문자열로 폴백
            }
        }
        i.putExtra(extraKey, extraValue);
    }
}
