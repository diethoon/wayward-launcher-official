package com.wayward.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles only WebView FileChooserParams requests. showDirectoryPicker() stays
 * on the independent WMLHost/File System Access bridge.
 */
final class WebFileChooser extends WebChromeClient {
    // MainActivity owns 4101-4106. Unique request codes isolate late picker results.
    private static final int REQUEST_CODE_MIN = 12000;
    private static final int REQUEST_CODE_MAX = 32767;
    private static final AtomicInteger NEXT_REQUEST_CODE = new AtomicInteger(REQUEST_CODE_MIN);

    private final Activity activity;
    private final String diagnosticSessionId;
    private final AtomicInteger consoleErrorSamples = new AtomicInteger();
    private PendingRequest pending;

    private static final class PendingRequest {
        final ValueCallback<Uri[]> callback;
        final String requestId;
        final int requestCode;
        final int mode;
        final WebFileChooserRequestPolicy.Kind kind;
        final List<String> acceptedMimeTypes;
        final String pickerAction;

        PendingRequest(ValueCallback<Uri[]> callback, String requestId, int requestCode,
                int mode, WebFileChooserRequestPolicy.Kind kind,
                List<String> acceptedMimeTypes, String pickerAction) {
            this.callback = callback;
            this.requestId = requestId;
            this.requestCode = requestCode;
            this.mode = mode;
            this.kind = kind;
            this.acceptedMimeTypes = acceptedMimeTypes;
            this.pickerAction = pickerAction;
        }
    }

    WebFileChooser(Activity activity, String sessionId) {
        this.activity = activity;
        diagnosticSessionId = sessionId == null ? "" : sessionId;
    }

    static boolean isRequestCode(int requestCode) {
        return requestCode >= REQUEST_CODE_MIN && requestCode <= REQUEST_CODE_MAX;
    }

    private static int nextRequestCode() {
        return NEXT_REQUEST_CODE.getAndUpdate(current ->
                current >= REQUEST_CODE_MAX ? REQUEST_CODE_MIN : current + 1);
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage message) {
        if (message != null && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                && consoleErrorSamples.getAndIncrement() < 16) {
            JSONObject details = new JSONObject();
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
    public boolean onShowFileChooser(WebView webView,
            ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
        if (filePathCallback == null) return false;
        final String requestId = "filechooser-" + java.util.UUID.randomUUID();

        if (pending != null) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_request_rejected_busy", "warn",
                    ImagePackDiagnostics.details("requestSource", "webview_file_chooser",
                            "pendingRequest", true, "result", "invalid_state"));
            // Do not replace an active callback: its picker result may still arrive.
            deliverRejectedCallback(filePathCallback, requestId, "busy");
            return true;
        }
        if (params == null) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_request_rejected", "error",
                    ImagePackDiagnostics.details("requestSource", "webview_file_chooser",
                            "result", "missing_file_chooser_params"));
            deliverRejectedCallback(filePathCallback, requestId, "unsupported");
            return true;
        }

        final int mode;
        final int permissionMode;
        final WebFileChooserRequestPolicy.Kind kind;
        final List<String> accepted;
        final boolean captureEnabled;
        try {
            mode = params.getMode();
            kind = WebFileChooserRequestPolicy.classify(mode);
            accepted = acceptedMimeTypes(params.getAcceptTypes());
            captureEnabled = params.isCaptureEnabled();
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_request_inspection_failed", error);
            deliverRejectedCallback(filePathCallback, requestId, "inspection_failed");
            return true;
        }

        if (kind == WebFileChooserRequestPolicy.Kind.UNSUPPORTED) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_request_unsupported", "warn",
                    ImagePackDiagnostics.details("requestSource", "webview_file_chooser",
                            "mode", mode, "requestType", "unsupported",
                            "captureEnabled", captureEnabled, "mimeTypeCount", accepted.size()));
            deliverRejectedCallback(filePathCallback, requestId, "unsupported");
            return true;
        }

        final Intent picker;
        try {
            picker = buildPickerIntent(params, kind, accepted, captureEnabled);
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_intent_build_failed", error);
            deliverRejectedCallback(filePathCallback, requestId, "picker_build_failed");
            return true;
        }

        int requestCode = nextRequestCode();
        PendingRequest request = new PendingRequest(filePathCallback, requestId, requestCode,
                mode, kind, accepted, picker.getAction());
        JSONObject details = ImagePackDiagnostics.pageDetails(
                webView == null ? null : webView.getUrl(), true);
        try {
            details.put("requestSource", "webview_file_chooser");
            details.put("requestType", WebFileChooserRequestPolicy.label(kind));
            details.put("mode", mode);
            details.put("captureEnabled", captureEnabled);
            details.put("mimeTypeCount", accepted.size());
            details.put("acceptTypes", joinMimeTypes(accepted));
            details.put("pickerAction", picker.getAction());
            details.put("requestCode", requestCode);
            details.put("titlePresent", params.getTitle() != null);
            details.put("filenameHintPresent", params.getFilenameHint() != null);
        } catch (Exception ignored) {}
        ImagePackDiagnostics.event(activity, diagnosticSessionId, requestId,
                "web_file_chooser_called", "info", details);

        // Store immutable ownership before launch. Only this requestCode may complete it.
        pending = request;
        try {
            activity.startActivityForResult(picker, requestCode);
            ImagePackDiagnostics.event(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_activity_started", "info",
                    ImagePackDiagnostics.details("requestType", WebFileChooserRequestPolicy.label(kind),
                            "pickerAction", picker.getAction(), "requestCode", requestCode));
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_activity_launch_failed", error);
            complete(request, null, "picker_launch_failed", "picker_launch_failed");
        }
        return true;
    }

    private Intent buildPickerIntent(FileChooserParams params,
            WebFileChooserRequestPolicy.Kind kind, List<String> accepted,
            boolean captureEnabled) {
            picker.addFlags(flags);
            addTitle(picker, params.getTitle());
            return picker;
        }

        if (WebFileChooserRequestPolicy.isSave(kind)) {
            Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            applyMimeTypes(picker, accepted);
            String filenameHint = params.getFilenameHint();
            String title = filenameHint == null || filenameHint.trim().isEmpty()
                    ? (params.getTitle() == null ? null : params.getTitle().toString())
                    : filenameHint;
            addTitle(picker, title);
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            return picker;
        }

        if (captureEnabled) {
            // Preserve WebView's platform chooser Intent for capture-capable requests.
            Intent picker = params.createIntent();
            if (picker == null || picker.getAction() == null) {
                throw new IllegalArgumentException("WebView did not provide a capture-capable chooser Intent.");
            }
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return picker;
        }

        // Ordinary files keep ACTION_OPEN_DOCUMENT and its persistable grant behavior.
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        applyMimeTypes(picker, accepted);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                WebFileChooserRequestPolicy.allowsMultipleFiles(kind));
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        addTitle(picker, params.getTitle());
        return picker;
    }

    private static void addTitle(Intent intent, CharSequence title) {
        if (title != null && title.length() > 0) intent.putExtra(Intent.EXTRA_TITLE, title.toString());
    }

    private static void addTitle(Intent intent, String title) {
        if (title != null && !title.trim().isEmpty()) intent.putExtra(Intent.EXTRA_TITLE, title);
    }

    private static void applyMimeTypes(Intent picker, List<String> accepted) {
        if (accepted == null || accepted.isEmpty()) {
            picker.setType("*/*");
        } else if (accepted.size() == 1) {
            picker.setType(accepted.get(0));
        } else {
            picker.setType("*/*");
            picker.putExtra(Intent.EXTRA_MIME_TYPES, accepted.toArray(new String[0]));
        }
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
                    String mime = "zip".equals(extension) ? "application/zip"
                            : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (mime != null && !result.contains(mime)) result.add(mime);
                } else if (value.matches("[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)")
                        && !result.contains(value)) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static String joinMimeTypes(List<String> types) {
        StringBuilder result = new StringBuilder();
        for (String type : types) {
            if (result.length() > 0) result.append(',');
            result.append(type);
        }
        return result.toString();
    }

    void handleActivityResult(int requestCode, int resultCode, Intent data) {
        PendingRequest request = pending;
        if (request == null || request.requestCode != requestCode) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, "",
                    "web_file_chooser_stale_or_duplicate_result", "warn",
                    ImagePackDiagnostics.details("requestCode", requestCode,
                            "pendingRequest", request != null, "resultCode", resultCode));
            return;
        }

        ClipData resultClip = data == null ? null : data.getClipData();
        Uri resultUri = data == null ? null : data.getData();
        JSONObject activityResultDetails = ImagePackDiagnostics.details(
                "requestType", WebFileChooserRequestPolicy.label(request.kind),
                "mode", request.mode,
                "pickerAction", request.pickerAction,
                "requestCode", request.requestCode,
                "resultCode", resultCode,
                "result", resultCode == Activity.RESULT_OK && data != null ? "selected" : "cancelled",
                "dataPresent", data != null,
                "clipItemCount", resultClip == null ? 0 : resultClip.getItemCount(),
                "resultFlags", data == null ? 0 : data.getFlags());
        if (resultUri != null) {
            try {
                activityResultDetails.put("uriScheme",
                        resultUri.getScheme() == null ? "none" : resultUri.getScheme());
                activityResultDetails.put("uriAuthority",
                        resultUri.getAuthority() == null ? "" : resultUri.getAuthority());
            } catch (Exception ignored) {}
        }
        ImagePackDiagnostics.event(activity, diagnosticSessionId, request.requestId,
                "web_file_chooser_activity_result",
                resultCode == Activity.RESULT_OK && data != null ? "info" : "warn",
                activityResultDetails);

        if (resultCode != Activity.RESULT_OK) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, request.requestId,
                    "web_file_chooser_result_cancelled", "warn",
                    ImagePackDiagnostics.details("requestType", WebFileChooserRequestPolicy.label(request.kind),
                            "resultCode", resultCode, "dataPresent", data != null));
            complete(request, null, "cancelled", "user_cancelled");
            return;
        }
        if (data == null) {
            complete(request, null, "result_validation_failed", "missing_result_intent");
            return;
        }

        try {
            Uri[] selected = collectFileResult(request, data);
            if (selected == null || selected.length == 0) {
                complete(request, null, "result_validation_failed", "empty_selection");
                return;
            }
            complete(request, selected, "selected", "");
        } catch (PermissionDeniedException error) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, request.requestId,
                    "web_file_chooser_permission_denied", "warn",
                    ImagePackDiagnostics.details("requestType", WebFileChooserRequestPolicy.label(request.kind),
                            "reason", error.safeReason));
            complete(request, null, "permission_denied", error.safeReason);
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, request.requestId,
                    "web_file_chooser_result_validation_failed", error);
            complete(request, null, "result_validation_failed", error.getClass().getSimpleName());
        }
    }

    private Uri[] collectFileResult(PendingRequest request, Intent data) throws Exception {
        List<Uri> selected = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !selected.contains(uri)) selected.add(uri);
            }
        }
        Uri single = data.getData();
        if (single != null && !selected.contains(single)) selected.add(single);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("The successful picker result contained no file URI.");
        }
        if (!WebFileChooserRequestPolicy.allowsMultipleFiles(request.kind) && selected.size() != 1) {
            throw new IllegalArgumentException("A single-file request returned more than one URI.");
        }

        for (Uri uri : selected) {
            if (!isContentUri(uri)) {
                throw new IllegalArgumentException("The picker returned a non-content URI.");
            }
            if (WebFileChooserRequestPolicy.isSave(request.kind)) {
                if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                    throw new PermissionDeniedException("save_uri_write_grant_missing");
                }
            } else {
                validateReadableUri(uri);
                validateAcceptedMimeType(uri, request.acceptedMimeTypes);
            }
        }
        return selected.toArray(new Uri[0]);
    }

    private boolean isContentUri(Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())
                || uri.getAuthority() == null || uri.getAuthority().trim().isEmpty()
                || uri.getUserInfo() != null) return false;
        String app = activity.getPackageName();
        String authority = uri.getAuthority();
        return !authority.equals(app) && !authority.startsWith(app + ".");
    }

    private void validateReadableUri(Uri uri) throws Exception {
        ContentResolver resolver = activity.getContentResolver();
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new PermissionDeniedException("uri_not_readable");
        } catch (SecurityException error) {
            throw new PermissionDeniedException("uri_read_permission_denied");
        }
    }

    private void validateAcceptedMimeType(Uri uri, List<String> accepted) throws Exception {
        if (accepted == null || accepted.isEmpty() || accepted.contains("*/*")) return;
        String actual = activity.getContentResolver().getType(uri);
        if (actual == null || actual.trim().isEmpty()) return;
        for (String expected : accepted) {
            if (expected.equalsIgnoreCase(actual)) return;
            int slash = expected.indexOf('/');
            if (slash > 0 && expected.endsWith("/*")
                    && actual.regionMatches(true, 0, expected.substring(0, slash + 1), 0,
                            slash + 1)) return;
        }
        throw new IllegalArgumentException("Selected file MIME type does not match the request.");
    }

    private static final class PermissionDeniedException extends Exception {
        final String safeReason;
        PermissionDeniedException(String safeReason) {
            super(safeReason);
            this.safeReason = safeReason;
        }
    }

    void cancelPending() {
        PendingRequest request = pending;
        if (request != null) complete(request, null, "cancelled", "activity_destroyed");
    }

    private void deliverRejectedCallback(ValueCallback<Uri[]> callback, String requestId, String reason) {
        ImagePackDiagnostics.event(activity, diagnosticSessionId, requestId,
                "web_file_chooser_callback_delivered", "warn",
                ImagePackDiagnostics.details("requestType", "rejected", "result", reason,
                        "selectedCount", 0, "cancelled", true));
        try {
            callback.onReceiveValue(null);
        } catch (RuntimeException error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_rejected_callback_failed", error);
        }
    }

    private void complete(PendingRequest request, Uri[] result, String status, String reason) {
        if (request == null || pending != request) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId,
                    request == null ? "" : request.requestId,
                    "web_file_chooser_duplicate_callback_suppressed", "warn",
                    ImagePackDiagnostics.details("result", "already_completed"));
            return;
        }
        // Clear ownership before callback invocation; reentrant calls cannot steal the result.
        pending = null;
        ImagePackDiagnostics.event(activity, diagnosticSessionId, request.requestId,
                "web_file_chooser_result_status", result == null ? "warn" : "info",
                ImagePackDiagnostics.details("requestType", WebFileChooserRequestPolicy.label(request.kind),
                        "status", status, "reason", reason == null ? "" : reason,
                        "selectedCount", result == null ? 0 : result.length,
                        "pickerAction", request.pickerAction));
        ImagePackDiagnostics.event(activity, diagnosticSessionId, request.requestId,
                "web_file_chooser_callback_delivered", result == null ? "warn" : "info",
                ImagePackDiagnostics.details("requestType", WebFileChooserRequestPolicy.label(request.kind),
                        "selectedCount", result == null ? 0 : result.length,
                        "cancelled", result == null));
        try {
            request.callback.onReceiveValue(result);
        } catch (RuntimeException error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, request.requestId,
                    "web_file_chooser_callback_failed", error);
        }
    }
}
