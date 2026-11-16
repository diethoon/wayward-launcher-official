package com.wayward.launcher;

import android.content.Context;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Installs the image-pack File System Access adapter for the active game origin. */
final class ImagePackFolderPickerScript {
    private static final int MAX_SCRIPT_BYTES = 64 * 1024;
    private static final String INSTALLED_FLAG = "window.__waywardNativeImagePackPickerInstalled===true";

    private ImagePackFolderPickerScript() {}

    static String read(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("imagepack-folder-picker.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) output.write(buffer, 0, count);
                if (output.size() > MAX_SCRIPT_BYTES) {
                    throw new IOException("imagepack-folder-picker.js exceeds the 64 KiB limit.");
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static boolean installDocumentStart(WebView webView, String source, String origin) {
        if (webView == null || source == null || source.isEmpty()
                || origin == null || origin.isEmpty()
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return false;
        }
        WebViewCompat.addDocumentStartJavaScript(webView, source, Collections.singleton(origin));
        return true;
    }

    static boolean isSameOrigin(String pageUrl, String expectedOrigin) {
        if (pageUrl == null || expectedOrigin == null) return false;
        try {
            Uri page = Uri.parse(pageUrl);
            Uri expected = Uri.parse(expectedOrigin);
            String pageScheme = page.getScheme();
            String expectedScheme = expected.getScheme();
            String pageHost = page.getHost();
            String expectedHost = expected.getHost();
            return pageScheme != null && expectedScheme != null
                    && pageScheme.equalsIgnoreCase(expectedScheme)
                    && pageHost != null && expectedHost != null
                    && pageHost.equalsIgnoreCase(expectedHost)
                    && page.getPort() == expected.getPort()
                    && page.getUserInfo() == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Runtime fallback used when document-start injection is unsupported or did not run. */
    static void installAfterPageFinished(
            WebView webView,
            String pageUrl,
            String expectedOrigin,
            String source,
            ValueCallback<Boolean> callback) {
        if (webView == null || source == null || source.isEmpty()
                || !isSameOrigin(pageUrl, expectedOrigin)) {
            if (callback != null) callback.onReceiveValue(false);
            return;
        }

        webView.evaluateJavascript(INSTALLED_FLAG, alreadyInstalled -> {
            if ("true".equals(alreadyInstalled)) {
                if (callback != null) callback.onReceiveValue(true);
                return;
            }
            String installAndVerify = source + "\n;(" + INSTALLED_FLAG + ");";
            webView.evaluateJavascript(installAndVerify,
                    result -> { if (callback != null) callback.onReceiveValue("true".equals(result)); });
        });
    }
}
