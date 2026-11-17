package com.wayward.launcher;

import android.content.Context;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;

/** Installs the image-pack File System Access adapter for the active game origin. */
final class ImagePackFolderPickerScript {
    private static final int MAX_SCRIPT_BYTES = 64 * 1024;
    private static final String INSTALLED_FLAG =
            "window.__waywardNativeImagePackPickerInstalled===true&&typeof window.showDirectoryPicker==='function'";

    private ImagePackFolderPickerScript() {}

    static String createCapability() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return android.util.Base64.encodeToString(secret,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    static boolean matchesCapability(String expected, String supplied) {
        if (expected == null || expected.isEmpty() || supplied == null || supplied.isEmpty()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Context context, String capability) throws IOException {
        if (capability == null || capability.isEmpty()) {
            throw new IOException("A native folder-picker capability is required.");
        }
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
            String source = new String(output.toByteArray(), StandardCharsets.UTF_8);
            String placeholder = "__WAYWARD_FOLDER_PICKER_CAPABILITY__";
            if (!source.contains(placeholder)) {
                throw new IOException("imagepack-folder-picker.js is missing its native capability placeholder.");
            }
            return source.replace(placeholder, JSONObject.quote(capability));
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

        String installScript = "(function(source,expectedOrigin){"
                    + "var visited=0,installed=0,failed=0;"
                    + "function visit(frame){"
                    + "var frameOrigin;"
                    + "try{frameOrigin=frame.location.origin;}catch(e){return;}"
                    + "if(frameOrigin!==expectedOrigin)return;"
                    + "visited++;"
                    + "try{if(frame.__waywardNativeImagePackPickerInstalled!==true||typeof frame.showDirectoryPicker!=='function')frame.eval(source);"
                    + "if(typeof frame.showDirectoryPicker==='function')installed++;else failed++;}"
                    + "catch(e){failed++;}"
                    + "try{for(var i=0;i<frame.frames.length;i++)visit(frame.frames[i]);}catch(e){}"
                    + "}"
                    + "visit(window);"
                    + "return visited>0&&installed===visited&&failed===0;"
                    + "})(" + JSONObject.quote(source) + "," + JSONObject.quote(expectedOrigin) + ");";
        webView.evaluateJavascript(installScript,
                result -> { if (callback != null) callback.onReceiveValue("true".equals(result)); });
    }
}
