package com.oemtools.poldock;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.List;

/** 바로가기 하나 추가/편집. intent extra "index" (-1=새로 추가). */
public class EditActivity extends Activity {

    static final String EXTRA_INDEX = "index";

    private EditText label, action, category, data, extraKey, extraValue, extraType, pkg,
            iconPkg, iconRes;
    private int index;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        index = getIntent().getIntExtra(EXTRA_INDEX, -1);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 28));
        sc.addView(root);

        root.addView(Ui.title(this, index < 0 ? "바로가기 추가" : "바로가기 편집"));

        LinearLayout basic = Ui.card(this);
        basic.addView(Ui.sectionLabel(this, "기본"));
        label = add(basic, "이름 (버튼 표시)");
        action = add(basic, "action (예: ecarx.intent.action.SUB_SETTINGS)");
        pkg = add(basic, "패키지 (앱 실행/지정, 없으면 비움)");
        root.addView(basic);

        LinearLayout extra = Ui.card(this);
        extra.addView(Ui.sectionLabel(this, "고급 (인텐트)"));
        category = add(extra, "category (예: android.intent.category.HOME)");
        data = add(extra, "data URI");
        extraKey = add(extra, "extra 키 (예: sub_fragment_id)");
        extraValue = add(extra, "extra 값 (정수면 10진수 또는 0x..)");
        extraType = add(extra, "extra 타입 (s=문자열, i=정수)");
        root.addView(extra);

        LinearLayout icon = Ui.card(this);
        icon.addView(Ui.sectionLabel(this, "아이콘"));
        iconPkg = add(icon, "아이콘 패키지 (예: com.android.car.settings)");
        iconRes = add(icon, "아이콘 리소스 (예: ic_fav_fold_mirror_48)");
        root.addView(icon);

        Shortcut s = current();
        if (s != null) {
            label.setText(s.label);
            action.setText(s.action);
            category.setText(s.category);
            data.setText(s.data);
            extraKey.setText(s.extraKey);
            extraValue.setText(s.extraValue);
            extraType.setText(s.extraType);
            pkg.setText(s.pkg);
            iconPkg.setText(s.iconPkg);
            iconRes.setText(s.iconRes);
        }

        root.addView(Ui.rowGap(this,
                Ui.button(this, "저장", Ui.ACCENT, v -> save()),
                Ui.button(this, "취소", Ui.GREY, v -> finish())));
        setContentView(sc);
    }

    private EditText add(LinearLayout card, String hint) {
        card.addView(Ui.fieldLabel(this, hint));
        EditText e = Ui.field(this, hint);
        card.addView(e);
        return e;
    }

    private Shortcut current() {
        List<Shortcut> list = ShortcutStore.load(this);
        if (index >= 0 && index < list.size()) return list.get(index);
        return null;
    }

    private void save() {
        Shortcut previous = current();
        Shortcut s = new Shortcut();
        s.label = t(label);
        s.action = t(action);
        s.category = t(category);
        s.data = t(data);
        s.extraKey = t(extraKey);
        s.extraValue = t(extraValue);
        s.extraType = "i".equalsIgnoreCase(t(extraType)) ? "i" : "s";
        s.pkg = t(pkg);
        if (previous != null && previous.pkg.equals(s.pkg)
                && previous.action.equals(s.action)) {
            s.component = previous.component;
        }
        s.iconPkg = t(iconPkg);
        s.iconRes = t(iconRes);
        if (s.label.isEmpty()) s.label = s.action.isEmpty() ? "(이름없음)" : s.action;

        List<Shortcut> list = ShortcutStore.load(this);
        if (index >= 0 && index < list.size()) list.set(index, s);
        else list.add(s);
        ShortcutStore.save(this, list);
        finish();
    }

    private String t(EditText e) {
        return e.getText().toString().trim();
    }
}
