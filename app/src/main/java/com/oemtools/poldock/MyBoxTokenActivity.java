package com.oemtools.poldock;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 안내를 보면서 MYBOX에 로그인해 개인 액세스 토큰을 발급받는 내부 브라우저. */
public class MyBoxTokenActivity extends Activity {
    private static final String MYBOX_URL = "https://mybox.naver.com/main/web";
    private static final String DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
    private WebView webView;
    private boolean returningToEditor;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int side = Ui.dp(this, 16);
        header.setPadding(side, Ui.dp(this, 10), side, Ui.dp(this, 6));

        TextView title = Ui.title(this, "MYBOX 토큰 발급받기");
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.chip(this, "X", Ui.GREY, v -> finish());
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(0, 0, 0, 0);
        close.setContentDescription("닫기");
        header.addView(close, new LinearLayout.LayoutParams(
                Ui.dp(this, 46), Ui.dp(this, 46)));
        root.addView(header);

        ScrollView guideScroll = new ScrollView(this);
        guideScroll.setVerticalScrollBarEnabled(false);
        TextView guide = new TextView(this);
        guide.setText("1. MYBOX 페이지에서 우상단 로그인 버튼으로 네이버 로그인\n"
                + "2. 좌하단 설정 → 계정 및 개인 액세스 토큰 관리 클릭\n"
                + "3. 화면 중간의 토큰 생성 클릭\n"
                + "4. 이름은 아무거나 입력하고 유효기간은 최대로 선택\n"
                + "5. 생성된 토큰 옆 복사 버튼 클릭\n"
                + "6. 편집창의 MYBOX 토큰 입력 버튼을 누르고 붙여넣은 뒤 저장");
        guide.setTextColor(Ui.TEXT);
        guide.setTextSize(15);
        guide.setLineSpacing(Ui.dp(this, 3), 1f);
        guide.setPadding(side, Ui.dp(this, 4), side, Ui.dp(this, 12));
        guideScroll.addView(guide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(guideScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 180)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalUrl(Uri.parse(url));
            }

        });
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (savedInstanceState == null) webView.loadUrl(MYBOX_URL);
        else webView.restoreState(savedInstanceState);
    }

    /** HTTPS 웹 이동은 WebView 안에서, 앱 전용 스킴은 해당 앱에서 연다. */
    private boolean handleExternalUrl(Uri uri) {
        String scheme = uri == null ? null : uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    public void finish() {
        if (returningToEditor) return;
        returningToEditor = true;
        super.finish();
        OverlayService.openEditor(this);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
