package com.wayward.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.util.Base64;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewCompat.WebMessageListener;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Locale;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/**
 * Displays the one static GitHub Pages CloudSave page inside the launcher.
 * Google authorization itself is delegated to Android AuthorizationClient;
 * Google OAuth web endpoints are never loaded in this embedded WebView.
 */
final class GoogleAuthInAppDialog {
    interface Listener {
        void onPageMessage(String rawMessage);
        void onAuthRequest(String nonce, boolean selectAccount);
        void onRevokeRequest(String nonce);
        void onClosed(String nonce);
        void onDiagnosticsRequested();
    }

    private static final String AUTH_ORIGIN = "https://jeiky224.github.io";
    private static final String AUTH_HOST = "jeiky224.github.io";
    private static final String AUTH_PATH = "/cloud/auth.html";
    private static final int MAX_MESSAGE_BYTES = 12 * 1024 * 1024;
    private static final int CHUNK_CHARS = 24 * 1024;
    private static final int MAX_CHUNKS = 768;
    private static final int COLOR_TEXT = Color.rgb(28, 32, 36);
    private static final int COLOR_MUTED = Color.rgb(106, 114, 122);

    private final Activity activity;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private Dialog dialog;
    private WebView webView;
    private JavaScriptReplyProxy replyProxy;
    private String activeNonce = "";
    private boolean closed;

    GoogleAuthInAppDialog(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    boolean open(String rawUrl) {
        if (activity.isFinishing() || rawUrl == null || rawUrl.length() > 2048) return false;
        final Uri uri;
        try {
            uri = Uri.parse(rawUrl);
            String nonce = uri.getQueryParameter("nonce");
            String gameOrigin = uri.getQueryParameter("origin");
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !AUTH_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1
                    || !AUTH_PATH.equals(uri.getPath())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || !"native-webview".equals(uri.getQueryParameter("transport"))
                    || nonce == null || !nonce.matches("[a-f0-9]{48}")
                    || !validGameOrigin(gameOrigin)) return false;
            if (dialog != null && dialog.isShowing()) closeFromGame();
            activeNonce = nonce;
        } catch (Throwable error) {
            return false;
        }

        closed = false;
        try {
            showDialog(uri);
            return true;
        } catch (Throwable error) {
            destroyCurrent(false);
            return false;
        }
    }

    private void showDialog(Uri uri) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw new IllegalStateException("이 Android System WebView는 보안 메시지 연결을 지원하지 않습니다.");
        }

        Dialog nextDialog = new Dialog(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(10), dp(10));
        header.setBackgroundColor(Color.rgb(248, 249, 250));

        TextView title = new TextView(activity);
        title.setText("Wayward CloudSave");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button close = new Button(activity);
        close.setText("닫기");
        close.setAllCaps(false);
        close.setTextColor(COLOR_TEXT);
        close.setMinHeight(dp(40));
        close.setBackground(roundRect(Color.WHITE, Color.rgb(220, 224, 228)));
        close.setOnClickListener(v -> closeFromGame());
        Button diagnostics = new Button(activity);
        diagnostics.setText("진단 내보내기");
        diagnostics.setAllCaps(false);
        diagnostics.setTextSize(12);
        diagnostics.setTextColor(COLOR_TEXT);
        diagnostics.setMinHeight(dp(40));
        diagnostics.setBackground(roundRect(Color.WHITE, Color.rgb(220, 224, 228)));
        diagnostics.setOnClickListener(v -> listener.onDiagnosticsRequested());
        LinearLayout.LayoutParams diagnosticsParams = new LinearLayout.LayoutParams(dp(104), dp(42));
        diagnosticsParams.rightMargin = dp(6);
        header.addView(diagnostics, diagnosticsParams);
        header.addView(close, new LinearLayout.LayoutParams(dp(68), dp(42)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        WebView page = new WebView(activity);
        webView = page;
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        page.setBackgroundColor(Color.WHITE);
        page.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                DiagnosticsLogger.event(activity, "INFO", "oauth_webview", "auth_page_started",
                        pageUrlDetails(url));
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                DiagnosticsLogger.event(activity, "INFO", "oauth_webview", "auth_page_finished",
                        pageUrlDetails(url));
                super.onPageFinished(view, url);
                injectPageDiagnostics(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    JSONObject details = new JSONObject();
                    try {
                        details.put("errorCode", error == null ? 0 : error.getErrorCode());
                        details.put("url", pageUrlDetails(request.getUrl().toString()));
                    } catch (Throwable ignored) {}
                    DiagnosticsLogger.event(activity, "ERROR", "oauth_webview",
                            "auth_page_main_frame_error", details);
                }
                super.onReceivedError(view, request, error);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request != null && response != null && request.isForMainFrame()) {
                    JSONObject details = new JSONObject();
                    try {
                        details.put("status", response.getStatusCode());
                        details.put("url", pageUrlDetails(request.getUrl().toString()));
                    } catch (Throwable ignored) {}
                    DiagnosticsLogger.event(activity, "ERROR", "oauth_webview",
                            "auth_page_main_frame_http_error", details);
                }
                super.onReceivedHttpError(view, request, response);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAllowedPageUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                try {
                    return !isAllowedPageUrl(Uri.parse(url));
                } catch (Throwable ignored) {
                    return true;
                }
            }
        });

        page.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    JSONObject details = new JSONObject();
                    try {
                        details.put("level", message.messageLevel().name());
                        details.put("line", message.lineNumber());
                        details.put("source", "static-auth-page");
                    } catch (Throwable ignored) {}
                    DiagnosticsLogger.event(activity, "ERROR", "oauth_webview",
                            "auth_page_console_error", details);
                }
                return true;
            }
        });

        WebViewCompat.addWebMessageListener(page, "WaywardAuthHost",
                Collections.singleton(AUTH_ORIGIN), new WebMessageListener() {
                    @Override
                    public void onPostMessage(WebView view, WebMessageCompat message,
                                              Uri sourceOrigin, boolean isMainFrame,
                                              JavaScriptReplyProxy reply) {
                        if (closed || view != webView || !isMainFrame
                                || sourceOrigin == null
                                || !AUTH_ORIGIN.equals(sourceOrigin.toString())
                                || message == null || message.getData() == null
                                || message.getData().length() > 24 * 1024 + 1024) {
                            DiagnosticsLogger.event(activity, "WARN", "oauth_webview",
                                    "auth_page_message_rejected",
                                    new JSONObject());
                            return;
                        }
                        replyProxy = reply;
                        handlePageCommand(message.getData());
                    }
                });

        panel.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        nextDialog.setContentView(panel);
        nextDialog.setCancelable(false);
        nextDialog.setOnDismissListener(ignored -> {
            if (!closed) {
                String nonce = activeNonce;
                closed = true;
                listener.onClosed(nonce);
                destroyCurrent(false);
            }
        });
        Window window = nextDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.white);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog = nextDialog;
        nextDialog.show();
        Window shownWindow = nextDialog.getWindow();
        if (shownWindow != null) {
            shownWindow.setBackgroundDrawableResource(android.R.color.white);
            shownWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        page.loadUrl(uri.toString());
    }

    private void handlePageCommand(String raw) {
        try {
            JSONObject command = new JSONObject(raw);
            String type = command.optString("type", "");
            if (!activeNonce.equals(command.optString("nonce", ""))) {
                DiagnosticsLogger.event(activity, "WARN", "oauth_webview",
                        "auth_page_nonce_mismatch", null);
                return;
            }
            if ("debug-state".equals(type)) {
                recordPageDiagnosticState(command);
                return;
            }
            switch (type) {
                case "ready": {
                    DiagnosticsLogger.event(activity, "INFO", "oauth_webview",
                            "auth_page_native_channel_ready_received", null);
                    boolean sent = sendToPage(new JSONObject()
                            .put("type", "native-ready")
                            .put("nonce", activeNonce));
                    JSONObject details = new JSONObject();
                    details.put("sent", sent);
                    DiagnosticsLogger.event(activity, sent ? "INFO" : "ERROR", "oauth_webview",
                            "native_ready_response", details);
                    break;
                }
                case "to-game": {
                    String message = command.optString("raw", "");
                    if (message.isEmpty() || message.length() > CHUNK_CHARS + 1024) return;
                    JSONObject payload = new JSONObject(message);
                    if (payload.optInt("__waywardCctChunk", -1) == 1) {
                        if (!isValidChunk(payload)) return;
                    } else if (!activeNonce.equals(payload.optString("nonce", ""))
                            || payload.optInt("version", -1) != 1
                            || !isAllowedPageToGameMessage(payload.optString("type", ""))) return;
                    JSONObject messageDetails = new JSONObject();
                    try {
                        messageDetails.put("type", payload.optString("type", "chunk"));
                        messageDetails.put("chars", message.length());
                    } catch (Throwable ignored) {}
                    DiagnosticsLogger.event(activity, "INFO", "oauth_webview",
                            "auth_page_message_forwarded_to_game", messageDetails);
                    listener.onPageMessage(message);
                    break;
                }
                case "auth-request":
                    DiagnosticsLogger.event(activity, "INFO", "oauth_webview",
                            "native_google_auth_request_received", null);
                    listener.onAuthRequest(activeNonce, command.optBoolean("selectAccount", false));
                    break;
                case "revoke-request":
                    listener.onRevokeRequest(activeNonce);
                    break;
                case "close":
                    closeFromGame();
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
            // Malformed or stale messages are intentionally ignored.
        }
    }

    boolean postGameMessage(String rawMessage) {
        if (closed || replyProxy == null || rawMessage == null || rawMessage.length() > MAX_MESSAGE_BYTES * 2)
            return false;
        try {
            byte[] bytes = rawMessage.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_MESSAGE_BYTES) return false;
            if (bytes.length <= CHUNK_CHARS) {
                JSONObject details = new JSONObject();
                try {
                    JSONObject payload = new JSONObject(rawMessage);
                    details.put("type", payload.optString("type", "unknown"));
                    details.put("chars", rawMessage.length());
                } catch (Throwable ignored) {}
                DiagnosticsLogger.event(activity, "INFO", "oauth_webview",
                        "game_message_received_by_auth_dialog", details);
                boolean sent = postGamePart(rawMessage);
                if (!sent) DiagnosticsLogger.event(activity, "ERROR", "oauth_webview",
                        "game_message_delivery_failed", null);
                return sent;
            }

            String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
            int total = (encoded.length() + CHUNK_CHARS - 1) / CHUNK_CHARS;
            if (total < 2 || total > MAX_CHUNKS) return false;
            String transferId = randomHex48();
            for (int seq = 0; seq < total; seq++) {
                int start = seq * CHUNK_CHARS;
                int end = Math.min(encoded.length(), start + CHUNK_CHARS);
                JSONObject part = new JSONObject();
                part.put("__waywardCctChunk", 1);
                part.put("id", transferId);
                part.put("seq", seq);
                part.put("total", total);
                part.put("data", encoded.substring(start, end));
                if (!postGamePart(part.toString())) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean postGamePart(String rawMessage) {
        try {
            JSONObject payload = new JSONObject(rawMessage);
            if (payload.optInt("__waywardCctChunk", -1) == 1) {
                if (!isValidChunk(payload)) return false;
            } else if (!activeNonce.equals(payload.optString("nonce", ""))
                    || payload.optInt("version", -1) != 1
                    || !isAllowedGameMessage(payload.optString("type", ""))) return false;
            JSONObject envelope = new JSONObject();
            envelope.put("type", "game-message");
            envelope.put("nonce", activeNonce);
            envelope.put("raw", rawMessage);
            return sendToPage(envelope);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private JSONObject pageUrlDetails(String rawUrl) {
        JSONObject details = new JSONObject();
        try {
            Uri uri = Uri.parse(rawUrl);
            details.put("scheme", String.valueOf(uri.getScheme()));
            details.put("host", String.valueOf(uri.getHost()));
            details.put("path", String.valueOf(uri.getPath()));
            details.put("transport", String.valueOf(uri.getQueryParameter("transport")));
            details.put("build", String.valueOf(uri.getQueryParameter("_wml_auth_build")));
        } catch (Throwable ignored) {}
        return details;
    }

    private void injectPageDiagnostics(WebView page) {
        String script =
                "(function(){'use strict';" +
                "if(window.__waywardNativeDiagInstalled)return;" +
                "window.__waywardNativeDiagInstalled=true;" +
                "var host=window.WaywardAuthHost;" +
                "if(!host||typeof host.postMessage!=='function')return;" +
                "var params=new URLSearchParams(location.search);" +
                "var last='';" +
                "function read(id){var e=document.getElementById(id);return e?String(e.textContent||'').slice(0,220):'';}" +
                "function emit(){try{" +
                "var s=Array.from(document.scripts).find(function(x){return x.src.indexOf('/cloud/auth.js')>=0;});" +
                "var origin='';try{origin=new URL(params.get('origin')||'').protocol;}catch(e){}" +
                "var state={type:'debug-state',nonce:params.get('nonce')||''," +
                "transport:params.get('transport')||'',build:params.get('_wml_auth_build')||''," +
                "authScriptLoaded:!!s,authScriptVersion:s?(new URL(s.src)).searchParams.get('v')||'':''," +
                "readyState:document.readyState,nativeHost:true," +
                "nativeHandler:typeof host.onmessage==='function',gameOriginScheme:origin," +
                "originTitle:read('origin-title'),originDetail:read('origin-detail')," +
                "status:read('status'),driveStatus:read('drive-status')," +
                "confirmVisible:!!(document.getElementById('confirm-wrap')&&!document.getElementById('confirm-wrap').hidden)};" +
                "var key=JSON.stringify(state);if(key!==last){last=key;host.postMessage(JSON.stringify(state));}" +
                "}catch(e){}}" +
                "emit();var root=document.documentElement;if(root&&window.MutationObserver){" +
                "new MutationObserver(emit).observe(root,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['hidden','disabled']});}" +
                "})();";
        try {
            page.evaluateJavascript(script, null);
        } catch (Throwable error) {
            DiagnosticsLogger.error(activity, "oauth_webview", "auth_page_diagnostics_probe_failed", error);
        }
    }

    private void recordPageDiagnosticState(JSONObject command) {
        JSONObject details = new JSONObject();
        try {
            String[] keys = {"transport", "build", "authScriptLoaded", "authScriptVersion",
                    "readyState", "nativeHost", "nativeHandler", "gameOriginScheme",
                    "originTitle", "originDetail", "status", "driveStatus", "confirmVisible"};
            for (String key : keys) {
                if (!command.has(key)) continue;
                Object value = command.get(key);
                if (value instanceof String) {
                    String text = ((String) value).replace('\n', ' ').replace('\r', ' ').trim();
                    details.put(key, text.substring(0, Math.min(220, text.length())));
                } else {
                    details.put(key, value);
                }
            }
        } catch (Throwable ignored) {}
        DiagnosticsLogger.event(activity, "INFO", "oauth_webview",
                "auth_page_runtime_state", details);
    }

    private boolean isValidChunk(JSONObject payload) {
        String id = payload.optString("id", "");
        int seq = payload.optInt("seq", -1);
        int total = payload.optInt("total", -1);
        String data = payload.optString("data", "");
        return id.matches("[a-f0-9]{48}") && total >= 2 && total <= MAX_CHUNKS
                && seq >= 0 && seq < total && !data.isEmpty() && data.length() <= CHUNK_CHARS;
    }

    private String randomHex48() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder value = new StringBuilder(48);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    boolean deliverAuthResult(String rawPayload) {
        return deliverPayload("auth-result", rawPayload);
    }

    boolean deliverRevokeResult(String nonce, boolean revoked, String error) {
        if (closed || replyProxy == null || !activeNonce.equals(nonce)) return false;
        try {
            JSONObject payload = new JSONObject();
            payload.put("nonce", nonce);
            payload.put("revoked", revoked);
            if (error != null && !error.isEmpty()) payload.put("error", error);
            JSONObject envelope = new JSONObject();
            envelope.put("type", "revoke-result");
            envelope.put("nonce", nonce);
            envelope.put("payload", payload);
            return sendToPage(envelope);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean deliverPayload(String type, String rawPayload) {
        if (closed || replyProxy == null || rawPayload == null || rawPayload.length() > 8192) return false;
        try {
            JSONObject payload = new JSONObject(rawPayload);
            if (!activeNonce.equals(payload.optString("nonce", ""))) return false;
            JSONObject envelope = new JSONObject();
            envelope.put("type", type);
            envelope.put("nonce", activeNonce);
            envelope.put("payload", payload);
            return sendToPage(envelope);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean sendToPage(JSONObject envelope) {
        if (closed || replyProxy == null || envelope == null) return false;
        try {
            replyProxy.postMessage(envelope.toString());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    void closeFromGame() {
        if (closed) return;
        String nonce = activeNonce;
        if (replyProxy != null) {
            try {
                JSONObject envelope = new JSONObject();
                envelope.put("type", "native-closed");
                envelope.put("nonce", nonce);
                sendToPage(envelope);
            } catch (Throwable ignored) {}
        }
        closed = true;
        listener.onClosed(nonce);
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        destroyCurrent(false);
    }

    boolean ownsNonce(String nonce) {
        return !closed && nonce != null && activeNonce.equals(nonce);
    }

    private void destroyCurrent(boolean notify) {
        if (notify && !closed) {
            closed = true;
            listener.onClosed(activeNonce);
        }
        WebView old = webView;
        webView = null;
        replyProxy = null;
        Dialog oldDialog = dialog;
        dialog = null;
        if (old != null) {
            try { old.stopLoading(); } catch (Throwable ignored) {}
            try { old.loadUrl("about:blank"); } catch (Throwable ignored) {}
            try { old.destroy(); } catch (Throwable ignored) {}
        }
        if (oldDialog != null && oldDialog.isShowing()) {
            try { oldDialog.dismiss(); } catch (Throwable ignored) {}
        }
        activeNonce = "";
    }

    private boolean isAllowedPageUrl(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && AUTH_HOST.equalsIgnoreCase(uri.getHost()) && uri.getPort() == -1
                && uri.getUserInfo() == null && uri.getPath() != null
                && uri.getPath().startsWith("/cloud/");
    }

    private static boolean validGameOrigin(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) return false;
        if ("null".equals(value)) return true;
        try {
            Uri uri = Uri.parse(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() >= -1
                    && uri.getUserInfo() == null && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null && uri.getFragment() == null
                    && uri.toString().equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAllowedGameMessage(String type) {
        return "wayward-cloudsave-hello".equals(type)
                || "wayward-cloudsave-request".equals(type)
                || "wayward-cloudsave-native-close".equals(type);
    }

    private static boolean isAllowedPageToGameMessage(String type) {
        return "wayward-cloudsave-page-ready".equals(type)
                || "wayward-cloudsave-ready".equals(type)
                || "wayward-cloudsave-status".equals(type)
                || "wayward-cloudsave-response".equals(type);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundRect(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(10));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }
}
