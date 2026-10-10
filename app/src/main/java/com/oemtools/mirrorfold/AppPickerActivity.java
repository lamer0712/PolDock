package com.oemtools.mirrorfold;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 설치된 런처 앱을 아이콘과 함께 보여주고, 고른 앱을 도크 바로가기로 추가. */
public class AppPickerActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 28));
        sc.addView(root);

        root.addView(Ui.title(this, "앱 선택"));
        TextView sub = new TextView(this);
        sub.setText("탭하면 도크에 추가됩니다");
        sub.setTextColor(Ui.DIM);
        sub.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), 0, Ui.dp(this, 14));
        root.addView(sub);

        LinearLayout box = Ui.card(this);
        root.addView(box);

        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        Collections.sort(apps, (x, y) -> x.loadLabel(pm).toString()
                .compareToIgnoreCase(y.loadLabel(pm).toString()));

        List<String> seen = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            final String pkg = ri.activityInfo.packageName;
            if (seen.contains(pkg)) continue;
            seen.add(pkg);
            final String name = ri.loadLabel(pm).toString();
            Drawable icon = ri.loadIcon(pm);
            box.addView(appRow(name, pkg, icon));
        }
        setContentView(sc);
    }

    private View appRow(String name, String pkg, Drawable icon) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.pressable(Ui.FIELD, 0xFF30343E, Ui.dp(this, 14)));
        int p = Ui.dp(this, 10);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(this);
        if (icon != null) iv.setImageDrawable(icon);
        int sz = Ui.dp(this, 40);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = Ui.dp(this, 14);
        row.addView(iv, ilp);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> addApp(name, pkg));
        return row;
    }

    private void addApp(String name, String pkg) {
        Shortcut s = new Shortcut(name, "", "", "", "", "s", pkg);
        List<Shortcut> list = ShortcutStore.load(this);
        list.add(s);
        ShortcutStore.save(this, list);
        Toast.makeText(this, name + " 추가됨", Toast.LENGTH_SHORT).show();
        finish();
    }
}
