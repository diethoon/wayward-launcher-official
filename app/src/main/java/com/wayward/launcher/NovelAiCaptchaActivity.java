package com.wayward.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-time user-driven handoff to NovelAI's official image-generation website. */
public final class NovelAiCaptchaActivity extends Activity {
    private static final String OFFICIAL_IMAGE_URL = "https://novelai.net/image/gallery";
    private static final AtomicBoolean OPENING = new AtomicBoolean(false);
    private WebView web;

    static void openFrom(WebView source) {
        if (source == null || !OPENING.compareAndSet(false, true)) return;
        source.post(() -> {
            android.content.Context context = source.getContext();
            if (!(context instanceof Activity)) { OPENING.set(false); return; }
            Activity owner = (Activity) context;
            if (owner.isFinishing() || (Build.VERSION.SDK_INT >= 17 && owner.isDestroyed())) {
                OPENING.set(false);
                return;
            }
            try { owner.startActivity(new Intent(owner, NovelAiCaptchaActivity.class)); }
            catch (RuntimeException error) {
                OPENING.set(false);
                Toast.makeText(owner, "NovelAI 무료 체험 확인 화면을 열지 못했습니다.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("NovelAI 무료 체험 확인");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        Button back = new Button(this);
        back.setText("닫기");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = new TextView(this);
        title.setText("NovelAI 공식 무료 체험 페이지");
        title.setTextColor(Color.rgb(35, 42, 50));
        title.setTextSize(16);
        title.setPadding(dp(10), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button reload = new Button(this);
        reload.setText("새로고침");
        reload.setAllCaps(false);
        reload.setOnClickListener(v -> { if (web != null) web.reload(); });
        toolbar.addView(reload, new LinearLayout.LayoutParams(-2, -2));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        TextView notice = new TextView(this);
        notice.setText("NovelAI 무료 체험 이미지 생성에서 reCAPTCHA 확인이 필요하다고 응답했습니다.\n"
                + "아래는 NovelAI 공식 페이지입니다. 로그인 후 직접 이미지 생성을 눌러 CAPTCHA 화면이 표시되는지 확인하세요.\n"
                + "런처는 CAPTCHA 토큰, 로그인 정보 또는 생성 결과를 읽거나 전달하지 않습니다.");
        notice.setTextColor(Color.rgb(65, 75, 85));
        notice.setTextSize(12);
        notice.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(notice, new LinearLayout.LayoutParams(-1, -2));

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.WHITE);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return route(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return route(Uri.parse(url));
            }
            private boolean route(Uri uri) {
                if (uri == null) return true;
                String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
                String host = String.valueOf(uri.getHost()).toLowerCase(Locale.ROOT);
                if ("https".equals(scheme) && isAllowedHost(host)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception ignored) {
                    Toast.makeText(NovelAiCaptchaActivity.this, "외부 링크를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                   android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Toast.makeText(NovelAiCaptchaActivity.this,
                            "NovelAI 공식 페이지를 불러오지 못했습니다. 연결 상태를 확인하고 새로고침하세요.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        web.loadUrl(OFFICIAL_IMAGE_URL);
    }

    private static boolean isAllowedHost(String host) {
        return host.equals("novelai.net") || host.endsWith(".novelai.net")
                || host.equals("google.com") || host.endsWith(".google.com")
                || host.equals("gstatic.com") || host.endsWith(".gstatic.com")
                || host.equals("recaptcha.net") || host.endsWith(".recaptcha.net")
                || host.equals("googleapis.com") || host.endsWith(".googleapis.com")
                || host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com");
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading(); web.loadUrl("about:blank"); web.removeAllViews(); web.destroy(); web = null;
        }
        OPENING.set(false);
        super.onDestroy();
    }
}
