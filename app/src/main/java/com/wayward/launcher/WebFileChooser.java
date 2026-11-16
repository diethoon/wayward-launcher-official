package com.wayward.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Connects HTML <input type="file"> requests in the game WebView to Android's
 * Storage Access Framework. The page receives the selected content:// URIs
 * through the standard WebView callback; no game HTML changes are required.
 */
final class WebFileChooser extends WebChromeClient {
    // Keep this distinct from MainActivity's 4101-4104 ActivityResult request codes.
    static final int REQUEST_CODE = 4105;

    private final Activity activity;
    private final String diagnosticSessionId;
    private final java.util.concurrent.atomic.AtomicInteger consoleErrorSamples =
            new java.util.concurrent.atomic.AtomicInteger();
    private ValueCallback<Uri[]> pendingCallback;
    private String pendingRequestId = "";
    private boolean allowMultiple;

    WebFileChooser(Activity activity, String sessionId) {
        this.activity = activity;
        diagnosticSessionId = sessionId == null ? "" : sessionId;
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage message) {
        if (message != null && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                && consoleErrorSamples.getAndIncrement() < 16) {
            org.json.JSONObject details = new org.json.JSONObject();
            try {
                String source = message.sourceId();
                Uri sourceUri = source == null ? null : Uri.parse(source);
                String sourcePath = sourceUri == null ? "" : sourceUri.getPath();
                if (sourcePath == null || sourcePath.isEmpty()) sourcePath = source == null ? "" : source;
                if (sourcePath.length() > 240) sourcePath = sourcePath.substring(0, 240);
                details.put("sourcePath", sourcePath);
                details.put("line", message.lineNumber());
                details.put("level", message.messageLevel().name());
            } catch (Exception ignored) {}
            // Deliberately omit message text: it can contain game content or user data.
            DiagnosticsLogger.event(activity, "ERROR", "webview", "javascript_console_error", details);
        }
        return super.onConsoleMessage(message);
    }

    @Override
    public boolean onShowFileChooser(
            WebView webView,
            ValueCallback<Uri[]> filePathCallback,
            FileChooserParams fileChooserParams) {
        cancelPending("replaced_by_new_request");
        pendingCallback = filePathCallback;
        pendingRequestId = "filechooser-" + java.util.UUID.randomUUID();
        // Folder selection is invoked directly by the native image-pack bridge.
        // Keep this callback dedicated to ordinary and multiple file requests.
        allowMultiple = fileChooserParams != null
                && fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
        Intent picker = buildPickerIntent(fileChooserParams);
        org.json.JSONObject details = ImagePackDiagnostics.pageDetails(
                webView == null ? null : webView.getUrl(), true);
        try {
            details.put("mode", allowMultiple ? "multiple" : "single");
            String[] accepted = fileChooserParams == null ? null : fileChooserParams.getAcceptTypes();
            if (accepted != null) details.put("acceptTypes", String.join(",", accepted));
        } catch (Exception ignored) {}
        ImagePackDiagnostics.event(activity, diagnosticSessionId, pendingRequestId,
                "web_file_chooser_called", "info", details);
        try {
            activity.startActivityForResult(picker, REQUEST_CODE);
            ImagePackDiagnostics.event(activity, diagnosticSessionId, pendingRequestId,
                    "web_file_chooser_activity_started", "info",
                    ImagePackDiagnostics.details("mode", allowMultiple ? "multiple" : "single"));
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, pendingRequestId,
                    "web_file_chooser_activity_launch_failed", error);
            deliver(null);
        }
        return true;
    }

    private Intent buildPickerIntent(FileChooserParams params) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);

        List<String> accepted = acceptedMimeTypes(
                params == null ? null : params.getAcceptTypes());
        if (accepted.isEmpty()) {
            picker.setType("*/*");
        } else if (accepted.size() == 1) {
            picker.setType(accepted.get(0));
        } else {
            picker.setType("*/*");
            picker.putExtra(Intent.EXTRA_MIME_TYPES, accepted.toArray(new String[0]));
        }

        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        picker.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return picker;
    }

    private static List<String> acceptedMimeTypes(String[] acceptTypes) {
        List<String> result = new ArrayList<>();
        if (acceptTypes == null) return result;

        for (String accepted : acceptTypes) {
            if (accepted == null) continue;
            for (String raw : accepted.split("[,;\\s]+")) {
                String value = raw.trim().toLowerCase(Locale.ROOT);
                if (value.isEmpty()) continue;

                if (value.startsWith(".")) {
                    String extension = value.substring(1);
                    String mime = "zip".equals(extension)
                            ? "application/zip"
                            : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (mime != null && !result.contains(mime)) result.add(mime);
                } else if (value.contains("/") && !value.contains(" ")
                        && !result.contains(value)) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    void handleActivityResult(int resultCode, Intent data) {
        if (pendingCallback == null) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, "",
                    "web_file_chooser_result_without_pending_request", "warn",
                    ImagePackDiagnostics.details("resultCode", resultCode));
            return;
        }
        ClipData clip = data == null ? null : data.getClipData();
        Uri single = data == null ? null : data.getData();
        org.json.JSONObject details = ImagePackDiagnostics.details(
                "resultCode", resultCode,
                "result", resultCode == Activity.RESULT_OK && data != null ? "selected" : "cancelled",
                "dataPresent", data != null,
                "clipItemCount", clip == null ? 0 : clip.getItemCount(),
                "resultFlags", data == null ? 0 : data.getFlags());
        if (single != null) {
            try {
                details.put("uriScheme", single.getScheme() == null ? "none" : single.getScheme());
                details.put("uriAuthority", single.getAuthority() == null ? "" : single.getAuthority());
            } catch (Exception ignored) {}
        }
        ImagePackDiagnostics.event(activity, diagnosticSessionId, pendingRequestId,
                "web_file_chooser_activity_result", 
                resultCode == Activity.RESULT_OK && data != null ? "info" : "warn", details);
        if (resultCode != Activity.RESULT_OK || data == null) {
            deliver(null);
            return;
        }

        List<Uri> selected = new ArrayList<>();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !selected.contains(uri)) selected.add(uri);
            }
        }

        if (single != null && !selected.contains(single)) selected.add(single);

        if (!allowMultiple && selected.size() > 1) {
            selected = new ArrayList<>(selected.subList(0, 1));
        }
        ImagePackDiagnostics.event(activity, diagnosticSessionId, pendingRequestId,
                "web_file_chooser_uris_collected", "info",
                ImagePackDiagnostics.details("selectedCount", selected.size(),
                        "mode", allowMultiple ? "multiple" : "single"));
        deliver(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
    }

    void cancelPending() {
        cancelPending("activity_destroyed");
    }

    private void cancelPending(String reason) {
        if (pendingCallback != null) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, pendingRequestId,
                    "web_file_chooser_cancelled", "warn",
                    ImagePackDiagnostics.details("result", reason, "cancelled", true));
        }
        deliver(null);
    }

    private void deliver(Uri[] result) {
        ValueCallback<Uri[]> callback = pendingCallback;
        String requestId = pendingRequestId;
        pendingCallback = null;
        pendingRequestId = "";
        allowMultiple = false;
        if (callback != null) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_callback_delivered", result == null ? "warn" : "info",
                    ImagePackDiagnostics.details("selectedCount", result == null ? 0 : result.length,
                            "cancelled", result == null));
            callback.onReceiveValue(result);
        }
    }
}
