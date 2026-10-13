package com.oemtools.poldock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Polestar OEM 런처의 Favorite apps 후보를 보여주고 도크 바로가기로 추가. */
public class AppPickerActivity extends Activity {

    static final String EXTRA_RETURN_TO_DOCK_EDITOR = "return_to_dock_editor";
    private static final String MEDIA_BROWSER_SERVICE =
            "android.media.browse.MediaBrowserService";
    private static final String MEDIA_TEMPLATE = "android.car.intent.action.MEDIA_TEMPLATE";
    private static final String MEDIA_COMPONENT = "android.car.intent.extra.MEDIA_COMPONENT";
    private static final String SETTINGS_PACKAGE = "com.android.car.settings";
    private static final String SETTINGS_ACTIVITY =
            "ecarx.settings.ui.activity.CarSettingsTabActivity";
    private static final String SECONDARY_SETTINGS_ACTIVITY =
            "ecarx.settings.ui.activity.CarSettingSecondaryModuleActivity";

    // com.ecarx.car.launcher 2.21.2의 app-grid 제외 목록.
    private static final Set<String> OEM_IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.car.messenger", "com.ecarx.car.ucappclient",
            "org.chromium.webview_shell", "com.android.gallery3d",
            "com.android.calendar", "com.android.deskclock", "com.android.car.dialer",
            "com.android.contacts", "com.android.car.themeplayground",
            "com.google.android.car.adaslocation", "com.android.music",
            "com.google.android.car.evs", "com.ecarx.fakemapsapp",
            "com.google.android.car.garagemode.testapp", "com.haleytek.audiodemo",
            "com.google.android.car.kitchensink",
            "com.google.android.car.networking.preferenceupdater",
            "com.android.car.ui.paintbooth", "com.google.android.apps.gmm.tools.tracks.sensortool",
            "com.haleytek.devicelistlite", "com.example.GnssTestApp",
            "com.android.car.rotaryplayground", "com.ecarx.car.whiteboard",
            "com.android.documentsui", "com.android.camera2", "com.android.quicksearchbox",
            "com.android.car.bugreport", "com.google.android.carassistant",
            "com.android.vending", "com.ecarx.usermanual.presentation",
            "com.siriusxm.aaos.uiapp", "com.siriusxm.polestar.uiapp"
    ));

    private static final Set<String> OEM_IGNORED_COMPONENTS = new HashSet<>(Arrays.asList(
            "com.android.bluetooth/com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService",
            "com.android.car.media.localmediaplayer/com.android.car.media.localmediaplayer.LocalMediaBrowserService",
            "com.siriusxm.aaos.uiapp/com.siriusxm.aaos.coreapp.SXMMediaService",
            "com.siriusxm.polestar.uiapp/com.siriusxm.aaos.coreapp.SXMMediaService",
            "com.android.music/com.android.music.MediaPlaybackService",
            "com.ecarx.media.multimedia/com.ecarx.usb.UsbMediaSessionService",
            "com.haleytek.radio.service/com.haleytek.radio.service.RadioService",
            "com.autonavi.amapauto/com.autonavi.auto.remote.fill.UsbFillActivity",
            "com.tmap.auto.ecarx/com.skt.tmap.auto.activity.TmapAutoNaviActivity",
            "com.haleytek.carplay/com.haleytek.carplay.android.CarPlayMediaBrowserService"
    ));

    private static final class AppCandidate {
        final String name;
        final String pkg;
        final String component;
        final Drawable icon;
        final boolean mediaService;
        final String action;
        final String shortcutIconRes;

        AppCandidate(String name, String pkg, String component, Drawable icon,
                     boolean mediaService) {
            this(name, pkg, component, icon, mediaService, "", "");
        }

        AppCandidate(String name, String pkg, String component, Drawable icon,
                     boolean mediaService, String action, String shortcutIconRes) {
            this.name = name;
            this.pkg = pkg;
            this.component = component;
            this.icon = icon;
            this.mediaService = mediaService;
            this.action = action;
            this.shortcutIconRes = shortcutIconRes;
        }
    }

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

        TextView title = Ui.title(this, "앱 선택");
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = Ui.dp(this, 14);
        root.addView(title, titleLp);

        LinearLayout box = Ui.card(this);
        root.addView(box);

        PackageManager pm = getPackageManager();
        List<AppCandidate> apps = loadOemFavoriteCandidates(pm);
        for (AppCandidate app : apps) {
            box.addView(appRow(app));
        }
        setContentView(sc);
    }

    private List<AppCandidate> loadOemFavoriteCandidates(PackageManager pm) {
        List<AppCandidate> result = new java.util.ArrayList<>();
        Set<String> seenComponents = new HashSet<>();
        Set<String> packagesWithActivity = new HashSet<>();

        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, PackageManager.GET_META_DATA)) {
            if (ri.activityInfo == null) continue;
            ComponentName component = new ComponentName(
                    ri.activityInfo.packageName, ri.activityInfo.name);
            String flat = component.flattenToString();
            if (shouldIgnore(component) || !seenComponents.add(flat)) continue;
            packagesWithActivity.add(component.getPackageName());
            result.add(new AppCandidate(labelOf(ri, pm), component.getPackageName(), flat,
                    ri.loadIcon(pm), false));
        }

        Intent media = new Intent(MEDIA_BROWSER_SERVICE);
        for (ResolveInfo ri : pm.queryIntentServices(media, PackageManager.GET_META_DATA)) {
            ServiceInfo info = ri.serviceInfo;
            if (info == null) continue;
            ComponentName component = new ComponentName(info.packageName, info.name);
            String flat = component.flattenToString();
            if (packagesWithActivity.contains(info.packageName) || shouldIgnore(component)
                    || isVideoApp(pm, info.packageName) || !seenComponents.add(flat)) continue;
            result.add(new AppCandidate(labelOf(ri, pm), info.packageName, flat,
                    ri.loadIcon(pm), true));
        }

        addSettingsCandidate(result, pm, "조정", SETTINGS_ACTIVITY,
                "android.settings.INTERIOR_ADJUSTMENT", "ic_quick_adjustments_48");
        addSettingsCandidate(result, pm, "모드", SECONDARY_SETTINGS_ACTIVITY,
                "ecarx.intent.action.SECONDARY_SETTINGS", "ic_quick_car_back_48");
        addSettingsCandidate(result, pm, "블루투스", SETTINGS_ACTIVITY,
                "android.settings.BLUETOOTH_SETTINGS", "ic_fav_bluetooth_48");
        addSettingsCandidate(result, pm, "Wi-Fi", SETTINGS_ACTIVITY,
                "android.settings.WIFI_SETTINGS", "ic_fav_wifi_general_48");
        addSettingsCandidate(result, pm, "실내등", SETTINGS_ACTIVITY,
                "android.settings.INTERIOR_LIGHT", "ic_fav_interior_lights_48");
        addSettingsCandidate(result, pm, "외부 조명", SETTINGS_ACTIVITY,
                "android.settings.EXTERIOR_LIGHT", "ic_fav_exterior_lights_blades_48");

        Collections.sort(result, (x, y) -> x.name.compareToIgnoreCase(y.name));
        return result;
    }

    private boolean shouldIgnore(ComponentName component) {
        return OEM_IGNORED_PACKAGES.contains(component.getPackageName())
                || OEM_IGNORED_COMPONENTS.contains(component.flattenToString());
    }

    private String labelOf(ResolveInfo info, PackageManager pm) {
        CharSequence label = info.loadLabel(pm);
        if (!TextUtils.isEmpty(label)) return label.toString();
        if (info.activityInfo != null) return info.activityInfo.packageName;
        if (info.serviceInfo != null) return info.serviceInfo.packageName;
        return "앱";
    }

    private boolean isVideoApp(PackageManager pm, String pkg) {
        try {
            ApplicationInfo app = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            int xmlId = app.metaData == null ? 0 : app.metaData.getInt("com.android.automotive", 0);
            if (xmlId == 0) return false;
            Resources resources = pm.getResourcesForApplication(app);
            try (XmlResourceParser parser = resources.getXml(xmlId)) {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.getEventType() == XmlPullParser.START_TAG
                            && "uses".equals(parser.getName())
                            && "video".equals(parser.getAttributeValue(null, "name"))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void addSettingsCandidate(List<AppCandidate> result, PackageManager pm,
                                      String name, String activity, String action,
                                      String iconRes) {
        ComponentName component = new ComponentName(SETTINGS_PACKAGE, activity);
        try {
            pm.getActivityInfo(component, 0);
            result.add(new AppCandidate(name, SETTINGS_PACKAGE,
                    component.flattenToString(), packageDrawable(SETTINGS_PACKAGE, iconRes),
                    false, action, iconRes));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    private Drawable packageDrawable(String pkg, String name) {
        try {
            android.content.Context packageContext = createPackageContext(pkg, 0);
            int id = packageContext.getResources().getIdentifier(name, "drawable", pkg);
            if (id != 0) return packageContext.getResources().getDrawable(id, null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private View appRow(AppCandidate app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.pressable(Ui.FIELD, 0xFF30343E, Ui.dp(this, 8)));
        int p = Ui.dp(this, 10);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(this);
        if (app.icon != null) iv.setImageDrawable(app.icon);
        int sz = Ui.dp(this, 40);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = Ui.dp(this, 14);
        row.addView(iv, ilp);

        TextView t = new TextView(this);
        t.setText(app.name);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> addApp(app));
        return row;
    }

    private void addApp(AppCandidate app) {
        Shortcut s;
        if (app.mediaService) {
            s = new Shortcut(app.name, MEDIA_TEMPLATE, "", MEDIA_COMPONENT,
                    app.component, "s", "");
            s.iconPkg = app.pkg;
        } else if (!TextUtils.isEmpty(app.action)) {
            s = new Shortcut(app.name, app.action, "", "PARAM",
                    "INTENT_SETTINGS", "s", app.pkg);
            s.component = app.component;
            s.iconPkg = SETTINGS_PACKAGE;
            s.iconRes = app.shortcutIconRes;
        } else {
            s = new Shortcut(app.name, "", "", "", "", "s", app.pkg);
            s.component = app.component;
        }
        List<Shortcut> list = ShortcutStore.load(this);
        list.add(s);
        ShortcutStore.save(this, list);
        Toast.makeText(this, app.name + " 추가됨", Toast.LENGTH_SHORT).show();
        if (getIntent().getBooleanExtra(EXTRA_RETURN_TO_DOCK_EDITOR, false)) {
            Intent done = new Intent(OverlayService.ACTION_SHORTCUT_ADDED)
                    .setPackage(getPackageName());
            sendBroadcast(done);
        }
        finish();
    }
}
