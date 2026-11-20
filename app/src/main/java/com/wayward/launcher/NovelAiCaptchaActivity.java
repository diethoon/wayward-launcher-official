package com.wayward.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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

/**
 * One-time, user-driven handoff to NovelAI's official image-generation website.
 * It deliberately does not read CAPTCHA tokens or pass results back to the launcher.
 */
public final class NovelAiCaptchaActivity extends Activity {
    private static final String OFFICIAL_IMAGE_URL = "https://novelai.net/image/gallery";
    private static final AtomicBoolean OPENING = new AtomicBoolean(false);
    private WebView web;
    private TextView pageStatus;
    private boolean pageLoadFailed;
    private String activePageUrl;

    static boolean openFrom(WebView source) {
        if (source == null) return false;
        android.content.Context app = source.getContext().getApplicationContext();
        if (!OPENING.compareAndSet(false, true)) {
            DiagnosticsLogger.event(app, "INFO", "novelai_captcha",
                    "activity_launch_suppressed_duplicate", null);
            return false;
        }
        AtomicBoolean launchStarted = new AtomicBoolean(false);
        boolean posted = source.post(() -> {
            if (!OPENING.get()) return;
            android.content.Context context = source.getContext();
            if (!(context instanceof Activity)) {
                OPENING.set(false);
                DiagnosticsLogger.event(app, "ERROR", "novelai_captcha",
                        "activity_launch_failed_invalid_context", null);
                showLaunchFailure(app);
                return;
            }
            Activity owner = (Activity) context;
            if (owner.isFinishing() || (Build.VERSION.SDK_INT >= 17 && owner.isDestroyed())) {
                OPENING.set(false);
                DiagnosticsLogger.event(app, "WARN", "novelai_captcha",
                        "activity_launch_failed_owner_unavailable", null);
                showLaunchFailure(app);
                return;
            }
            try {
                owner.startActivity(new Intent(owner, NovelAiCaptchaActivity.class));
                launchStarted.set(true);
                DiagnosticsLogger.event(app, "INFO", "novelai_captcha",
                        "activity_launch_started", null);
            } catch (RuntimeException error) {
                OPENING.set(false);
                org.json.JSONObject details = new org.json.JSONObject();
                try { details.put("exception", error.getClass().getSimpleName()); } catch (Exception ignored) {}
                DiagnosticsLogger.event(app, "ERROR", "novelai_captcha",
                        "activity_launch_failed", details);
                showLaunchFailure(app);
            }
        });
        if (!posted) {
            OPENING.set(false);
            DiagnosticsLogger.event(app, "ERROR", "novelai_captcha",
                    "activity_launch_failed_webview_post_rejected", null);
            showLaunchFailure(app);
            return false;
        }
        DiagnosticsLogger.event(app, "INFO", "novelai_captcha",
                "activity_launch_post_accepted", null);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!launchStarted.get() && OPENING.compareAndSet(true, false)) {
                DiagnosticsLogger.event(app, "ERROR", "novelai_captcha",
                        "activity_launch_post_timeout", null);
                showLaunchFailure(app);
            }
        }, 5000L);
        return true;
    }

    private static void showLaunchFailure(android.content.Context context) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "NovelAI 확인 화면을 열지 못했습니다. 런처를 다시 시도하세요.",
                        Toast.LENGTH_LONG).show());
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
                + "NovelAI 계정으로 로그인한 뒤 공식 페이지에서 직접 생성 버튼을 눌러 확인을 진행하세요.\n"
                + "확인을 마치면 이 화면을 닫고 런처의 이미지 생성을 다시 시도하세요.\n"
                + "런처는 CAPTCHA 토큰, 로그인 정보 또는 생성 결과를 읽거나 전달하지 않습니다.");
        notice.setTextColor(Color.rgb(65, 75, 85));
        notice.setTextSize(12);
        notice.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(notice, new LinearLayout.LayoutParams(-1, -2));

        pageStatus = new TextView(this);
        pageStatus.setText("NovelAI 공식 페이지 연결 중…");
        pageStatus.setTextColor(Color.rgb(65, 75, 85));
        pageStatus.setTextSize(12);
        pageStatus.setPadding(dp(14), dp(6), dp(14), dp(6));
        root.addView(pageStatus, new LinearLayout.LayoutParams(-1, -2));

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
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                activePageUrl = url;
                pageLoadFailed = false;
                if (pageStatus != null) pageStatus.setText("NovelAI 공식 페이지 연결 중…");
                DiagnosticsLogger.event(getApplicationContext(), "INFO", "novelai_captcha",
                        "webview_page_load_started", null);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!pageLoadFailed && url != null && url.equals(activePageUrl)
                        && pageStatus != null) pageStatus.setText(
                        "페이지 표시 완료. CAPTCHA는 NovelAI 측에서 확인이 필요하다고 판단할 때 표시됩니다.");
                DiagnosticsLogger.event(getApplicationContext(), "INFO", "novelai_captcha",
                        "webview_page_load_finished", null);
            }
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
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    Toast.makeText(NovelAiCaptchaActivity.this,
                            "외부 링크를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                   android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()
                        && request.getUrl() != null
                        && request.getUrl().toString().equals(activePageUrl)) {
                    pageLoadFailed = true;
                    if (pageStatus != null) pageStatus.setText(
                            "페이지 로딩 실패. 네트워크를 확인한 뒤 새로고침을 누르세요.");
                    org.json.JSONObject details = new org.json.JSONObject();
                    try {
                        if (error != null) details.put("webErrorCode", error.getErrorCode());
                    } catch (Exception ignored) {}
                    DiagnosticsLogger.event(getApplicationContext(), "ERROR", "novelai_captcha",
                            "webview_main_frame_load_failed", details);
                    Toast.makeText(NovelAiCaptchaActivity.this,
                            "NovelAI 공식 페이지를 불러오지 못했습니다. 연결 상태를 확인하고 새로고침하세요.",
                            Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                       android.webkit.WebResourceResponse response) {
                if (request != null && request.isForMainFrame()
                        && request.getUrl() != null
                        && request.getUrl().toString().equals(activePageUrl)) {
                    pageLoadFailed = true;
                    if (pageStatus != null) pageStatus.setText(
                            "NovelAI 페이지 응답 오류(HTTP " + response.getStatusCode() + "). 새로고침을 시도하세요.");
                    org.json.JSONObject details = new org.json.JSONObject();
                    try { details.put("httpStatus", response.getStatusCode()); } catch (Exception ignored) {}
                    DiagnosticsLogger.event(getApplicationContext(), "WARN", "novelai_captcha",
                            "webview_main_frame_http_error", details);
                }
            }
            @Override public void onReceivedSslError(WebView view,
                    android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
                pageLoadFailed = true;
                activePageUrl = view == null ? null : view.getUrl();
                if (pageStatus != null) pageStatus.setText("보안 연결 확인에 실패했습니다. 페이지를 열지 않았습니다.");
                DiagnosticsLogger.event(getApplicationContext(), "ERROR", "novelai_captcha",
                        "webview_ssl_error_cancelled", null);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        DiagnosticsLogger.event(getApplicationContext(), "INFO", "novelai_captcha",
                "webview_load_requested", null);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.removeAllViews();
            web.destroy();
            web = null;
        }
        DiagnosticsLogger.event(getApplicationContext(), "INFO", "novelai_captcha",
                "activity_closed_return_to_launcher", null);
        OPENING.set(false);
        super.onDestroy();
    }
}
