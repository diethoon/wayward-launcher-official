package com.wayward.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
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
        final List<String> acceptedTypes;
        final boolean captureEnabled;
        final Uri captureOutputUri;
        final String pickerAction;

        PendingRequest(ValueCallback<Uri[]> callback, String requestId, int requestCode,
                int mode, WebFileChooserRequestPolicy.Kind kind,
                List<String> acceptedTypes, boolean captureEnabled, Uri captureOutputUri, String pickerAction) {
            this.callback = callback;
            this.requestId = requestId;
            this.requestCode = requestCode;
            this.mode = mode;
            this.kind = kind;
            this.acceptedTypes = new ArrayList<>(acceptedTypes);
            this.captureEnabled = captureEnabled;
            this.captureOutputUri = captureOutputUri;
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
        final WebFileChooserRequestPolicy.Kind kind;
        final List<String> acceptedTypes;
        final List<String> accepted;
        final boolean captureEnabled;
        try {
            mode = params.getMode();
            kind = WebFileChooserRequestPolicy.classify(mode);
            acceptedTypes = WebFileChooserRequestPolicy.parseAcceptTypes(params.getAcceptTypes());
            accepted = acceptedMimeTypes(acceptedTypes);
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
            picker = buildPickerIntent(params, kind, accepted, acceptedTypes, captureEnabled);
        } catch (Exception error) {
            ImagePackDiagnostics.error(activity, diagnosticSessionId, requestId,
                    "web_file_chooser_intent_build_failed", error);
            deliverRejectedCallback(filePathCallback, requestId, "picker_build_failed");
            return true;
        }

        int requestCode = nextRequestCode();
        Uri captureOutputUri = captureEnabled ? findCaptureOutputUri(picker) : null;
        PendingRequest request = new PendingRequest(filePathCallback, requestId, requestCode,
                mode, kind, acceptedTypes, captureEnabled, captureOutputUri, picker.getAction());
        JSONObject details = ImagePackDiagnostics.pageDetails(
                webView == null ? null : webView.getUrl(), true);
        try {
            details.put("requestSource", "webview_file_chooser");
            details.put("requestType", WebFileChooserRequestPolicy.label(kind));
            details.put("mode", mode);
            details.put("captureEnabled", captureEnabled);
            details.put("mimeTypeCount", accepted.size());
            details.put("acceptTypes", joinMimeTypes(acceptedTypes));
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
            List<String> acceptedTypes, boolean captureEnabled) {
        if (WebFileChooserRequestPolicy.isSave(kind)) {
            Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            applyMimeTypes(picker, accepted);
            String filenameHint = params.getFilenameHint();
            // FileChooserParams.getTitle() is chooser UI text, not a filename.
            // ACTION_CREATE_DOCUMENT.EXTRA_TITLE is exclusively the suggested filename.
            addTitle(picker, filenameHint);
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            return picker;
        }

        if (captureEnabled) {
            // Preserve WebView's platform chooser Intent for capture-capable requests.
            Intent picker = params.createIntent();
            if (picker == null || picker.getAction() == null || !containsLiveCaptureIntent(picker)) {
                throw new UnsupportedOperationException("WebView did not provide a live media capture Intent for this capture request.");
            }
            picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    WebFileChooserRequestPolicy.allowsMultipleFiles(kind));
            if (acceptedTypes != null && !acceptedTypes.isEmpty()) applyMimeTypes(picker, accepted);
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            return picker;
        }

        // Start from WebView's original request Intent so unmodeled options survive.
        // ACTION_OPEN_DOCUMENT is required here for a persistable SAF URI grant.
        Intent source = params.createIntent();
        if (source == null || source.getAction() == null) {
            throw new IllegalArgumentException("WebView did not provide a file chooser Intent.");
        }
        String sourceAction = source.getAction();
        if (!Intent.ACTION_GET_CONTENT.equals(sourceAction)
                && !Intent.ACTION_OPEN_DOCUMENT.equals(sourceAction)) {
            throw new UnsupportedOperationException("Unsupported WebView file chooser action.");
        }
        Intent picker = new Intent(source);
        picker.setAction(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        applyMimeTypes(picker, accepted);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                WebFileChooserRequestPolicy.allowsMultipleFiles(kind));
        if (params.getTitle() != null && !picker.hasExtra(Intent.EXTRA_TITLE)) {
            addTitle(picker, params.getTitle());
        }
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
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

    private static List<String> acceptedMimeTypes(List<String> acceptedTypes) {
        List<String> result = new ArrayList<>();
        for (String value : acceptedTypes) {
            if (value.startsWith(".")) {
                String extension = value.substring(1);
                String mime = "zip".equals(extension) ? "application/zip"
                        : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mime != null && !result.contains(mime)) result.add(mime);
            } else if (!result.contains(value)) {
                result.add(value);
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
                "result", resultCode == Activity.RESULT_OK && (data != null || request.captureOutputUri != null) ? "selected" : "cancelled",
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
        if (data == null && !(request.captureEnabled && request.captureOutputUri != null)) {
            complete(request, null, "result_validation_failed", "missing_result_intent");
            return;
        }

        try {
            Uri[] selected = collectFileResult(request, resultCode, data);
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

    private Uri[] collectFileResult(PendingRequest request, int resultCode, Intent data) throws Exception {
        List<Uri> selected = new ArrayList<>();
        ClipData clip = data == null ? null : data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !selected.contains(uri)) selected.add(uri);
            }
        }
        Uri single = data == null ? null : data.getData();
        if (single != null && !selected.contains(single)) selected.add(single);
        if (!request.captureEnabled && !WebFileChooserRequestPolicy.isSave(request.kind)) {
            Uri[] parsed = FileChooserParams.parseResult(resultCode, data);
            if (parsed != null) for (Uri uri : parsed) if (uri != null && !selected.contains(uri)) selected.add(uri);
        }
        if (request.captureEnabled && selected.isEmpty() && request.captureOutputUri != null) selected.add(request.captureOutputUri);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("The successful picker result contained no file URI.");
        }
        if (!WebFileChooserRequestPolicy.allowsMultipleFiles(request.kind) && selected.size() != 1) {
            throw new IllegalArgumentException("A single-file request returned more than one URI.");
        }

        for (Uri uri : selected) {
            if (!isContentUri(uri) && !uri.equals(request.captureOutputUri)) {
                throw new IllegalArgumentException("The picker returned a non-content URI.");
            }
            if (WebFileChooserRequestPolicy.isSave(request.kind)) {
                validateWritableUri(uri);
            } else {
                validateReadableUri(uri);
            }
            validateAcceptedFile(uri, request.acceptedTypes);
            if (data != null) persistReturnedPermission(data, uri);
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

    private void validateWritableUri(Uri uri) throws Exception {
        int permission = activity.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            throw new PermissionDeniedException("save_uri_write_grant_missing");
        }
        try (ParcelFileDescriptor descriptor = activity.getContentResolver().openFileDescriptor(uri, "rw")) {
            if (descriptor == null) throw new PermissionDeniedException("save_uri_not_writable");
        } catch (SecurityException error) {
            throw new PermissionDeniedException("save_uri_write_permission_denied");
        }
    }

    private void validateAcceptedFile(Uri uri, List<String> acceptedTypes) throws Exception {
        if (acceptedTypes == null || acceptedTypes.isEmpty()) return;
        String actualMime = activity.getContentResolver().getType(uri);
        String displayName = null;
        try (Cursor cursor = activity.getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) displayName = cursor.getString(index);
            }
        }
        if (!WebFileChooserRequestPolicy.acceptsFile(acceptedTypes, actualMime, displayName)) {
            throw new IllegalArgumentException("Selected file does not match the original accept filter.");
        }
    }

    private void persistReturnedPermission(Intent data, Uri uri) {
        int flags = data.getFlags();
        if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) return;
        int grants = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (grants == 0) return;
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, grants);
        } catch (SecurityException error) {
            ImagePackDiagnostics.event(activity, diagnosticSessionId, "",
                    "web_file_chooser_persistable_grant_unavailable", "warn",
                    ImagePackDiagnostics.details("uriAuthority", uri.getAuthority(), "grantFlags", grants));
        }
    }

    private static boolean isLiveCaptureAction(String action) {
        return MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                || MediaStore.ACTION_VIDEO_CAPTURE.equals(action)
                || "android.provider.MediaStore.RECORD_SOUND".equals(action);
    }

    private static boolean containsLiveCaptureIntent(Intent intent) {
        if (intent == null) return false;
        if (isLiveCaptureAction(intent.getAction())) return true;
        Intent nested = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (nested != null && containsLiveCaptureIntent(nested)) return true;
        Parcelable[] initial = intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        if (initial != null) {
            for (Parcelable item : initial) {
                if (item instanceof Intent && containsLiveCaptureIntent((Intent) item)) return true;
            }
        }
        return false;
    }

    private static Uri findCaptureOutputUri(Intent intent) {
        if (intent == null) return null;
        Uri output = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        if (output != null) return output;
        Intent nested = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
        output = findCaptureOutputUri(nested);
        if (output != null) return output;
        Parcelable[] initial = intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        if (initial != null) {
            for (Parcelable item : initial) {
                if (item instanceof Intent) {
                    output = findCaptureOutputUri((Intent) item);
                    if (output != null) return output;
                }
            }
        }
        return null;
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
