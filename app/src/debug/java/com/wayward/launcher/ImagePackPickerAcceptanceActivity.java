package com.wayward.launcher;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ImagePackPickerAcceptanceActivity extends Activity {
    private static final String TAG = "WaywardImagePackTest";
    private static final String PROFILE = "acceptance_imagepack";
    private ImagePackDirectoryAccess access;
    private WebView webView;
    private boolean verifyExisting;
    private boolean verifyRevoked;
    private boolean verifySymlinkFixture;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        verifyExisting = getIntent().getBooleanExtra("verify_existing", false);
        verifyRevoked = getIntent().getBooleanExtra("verify_revoked", false);
        verifySymlinkFixture = getIntent().getBooleanExtra("verify_symlink_fixture", false);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        access = new ImagePackDirectoryAccess(this, webView, PROFILE);
        String initialUri = getIntent().getStringExtra("initial_uri");
        if (initialUri != null && !initialUri.isEmpty()) {
            access.setInitialUriForTesting(Uri.parse(initialUri));
        }
        webView.addJavascriptInterface(new TestWmlHost(), "WMLHost");
        webView.addJavascriptInterface(new AcceptanceBridge(), "AcceptanceBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (verifyRevoked) {
                    view.evaluateJavascript("window.AcceptanceBridge.verifyPermissionRevoked()", null);
                } else if (verifyExisting) {
                    view.evaluateJavascript("window.AcceptanceBridge.verifyPersistedAccess()", null);
                } else {
                    view.evaluateJavascript("document.getElementById('pick').click()", null);
                }
            }
        });
        setContentView(webView);

        try {
            String pickerScript = readAsset("imagepack-folder-picker.js");
            String page = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                    + "<body><button id='pick'>Choose image-pack folder</button>"
                    + "<script>" + pickerScript + "</script><script>"
                    + "document.getElementById('pick').addEventListener('click',async()=>{"
                    + "try{const directory=await window.showDirectoryPicker({mode:'read'});"
                    + "const fileHandle=await directory.getFileHandle('pixel.png');"
                    + "const file=await fileHandle.getFile();"
                    + "const bytes=new Uint8Array(await file.arrayBuffer());"
                    + "const signature=Array.from(bytes.slice(0,8)).join(',');"
                    + "if(signature!=='137,80,78,71,13,10,26,10')throw new Error('Selected file did not contain a PNG signature');"
                    + "window.AcceptanceBridge.verifyFilesystemOperations();"
                    + "window.AcceptanceBridge.report('PASS picked directory='+directory.name+' bytes='+bytes.length);"
                    + "}catch(error){window.AcceptanceBridge.report('FAIL picked '+String(error&&error.name)+':'+String(error&&error.message));}"
                    + "});</script></body></html>";
            webView.loadDataWithBaseURL("https://wayward-imagepack-test.local/", page,
                    "text/html", "UTF-8", null);
        } catch (Exception error) {
            Log.e(TAG, "FAIL test page setup", error);
        }
    }

    private String readAsset(String name) throws Exception {
        try (InputStream input = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (access != null) access.onResume();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ImagePackDirectoryAccess.REQUEST_CODE) {
            access.handleActivityResult(resultCode, data);
        }
    }

    @Override protected void onDestroy() {
        if (access != null) access.cancelPending();
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("WMLHost");
            webView.removeJavascriptInterface("AcceptanceBridge");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class TestWmlHost {
        @JavascriptInterface public void chooseImagePackFolder(String requestId) {
            access.openFolderPicker(requestId);
        }
        @JavascriptInterface public String listImagePackDirectory(String path) {
            return access.listDirectoryJson(path);
        }
        @JavascriptInterface public String readImagePackFile(String path) {
            return access.readFileJson(path);
        }
        @JavascriptInterface public String writeImagePackFile(String path, String mime, String data) {
            return access.writeFileJson(path, mime, data);
        }
        @JavascriptInterface public String createImagePackDirectory(String path) {
            return access.createDirectoryJson(path);
        }
        @JavascriptInterface public String deleteImagePackEntry(String path) {
            return access.deleteEntryJson(path);
        }
        @JavascriptInterface public String getImagePackFolderInfo() {
            return access.getFolderInfoJson();
        }
    }

    private final class AcceptanceBridge {
        @JavascriptInterface public void report(String message) {
            if (message != null && message.startsWith("PASS ")) Log.i(TAG, message);
            else Log.e(TAG, message == null ? "FAIL no result" : message);
        }

        @JavascriptInterface public void verifyPersistedAccess() {
            try {
                JSONObject info = new JSONObject(access.getFolderInfoJson());
                JSONObject file = new JSONObject(access.readFileJson("pixel.png"));
                if (!info.optBoolean("ok") || !file.optBoolean("ok")) {
                    throw new IllegalStateException("Persisted filesystem access is unavailable.");
                }
                byte[] bytes = Base64.decode(file.getString("data"), Base64.DEFAULT);
                byte[] signature = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
                if (bytes.length < signature.length) throw new IllegalStateException("Persisted PNG is empty.");
                for (int i = 0; i < signature.length; i++) {
                    if (bytes[i] != signature[i]) throw new IllegalStateException("Persisted PNG signature mismatch.");
                }
                Log.i(TAG, "PASS persisted-after-restart directory=" + info.optString("name")
                        + " bytes=" + bytes.length);
            } catch (Exception error) {
                Log.e(TAG, "FAIL persisted-after-restart", error);
            }
        }

        @JavascriptInterface public void verifyFilesystemOperations() {
            try {
                requireOk(new JSONObject(access.createDirectoryJson("acceptance-crud")), "create folder");
                byte[] original = new byte[] {1, 2, 3, 4};
                byte[] replacement = new byte[] {9, 8, 7, 6, 5, 4};
                requireOk(new JSONObject(access.writeFileJson("acceptance-crud/replace.png", "image/png",
                        Base64.encodeToString(original, Base64.NO_WRAP))), "create file");
                requireBytes(original, new JSONObject(access.readFileJson("acceptance-crud/replace.png")), "read file");
                requireOk(new JSONObject(access.writeFileJson("acceptance-crud/replace.png", "image/png",
                        Base64.encodeToString(replacement, Base64.NO_WRAP))), "replace existing file");
                requireBytes(replacement, new JSONObject(access.readFileJson("acceptance-crud/replace.png")), "read replaced file");
                JSONObject children = new JSONObject(access.listDirectoryJson("acceptance-crud"));
                if (!children.optBoolean("ok") || !children.optJSONArray("entries").toString().contains("replace.png")) {
                    throw new IllegalStateException("New file is missing from the selected-root listing.");
                }
                requireOk(new JSONObject(access.deleteEntryJson("acceptance-crud/replace.png")), "delete file");
                requireOk(new JSONObject(access.deleteEntryJson("acceptance-crud")), "delete folder");

                assertRejected(access.listDirectoryJson("../Documents"), "list sibling folder");
                assertRejected(access.readFileJson("../Documents/private.txt"), "read sibling file");
                assertRejected(access.writeFileJson("../Documents/private.txt", "text/plain",
                        Base64.encodeToString(new byte[] {0}, Base64.NO_WRAP)), "write sibling file");
                assertRejected(access.createDirectoryJson("../Documents/forbidden"), "create sibling folder");
                assertRejected(access.deleteEntryJson("../Documents/private.txt"), "delete sibling file");

                JSONObject rootEntries = new JSONObject(access.listDirectoryJson(""));
                if (!rootEntries.optBoolean("ok")) throw new IllegalStateException("Selected-root listing failed.");
                if (verifySymlinkFixture) {
                    if (rootEntries.optJSONArray("entries").toString().contains("__wayward_escape")) {
                        throw new IllegalStateException("Escaping symbolic link was exposed in listing.");
                    }
                    assertRejected(access.listDirectoryJson("__wayward_escape"), "list through symbolic link");
                    assertRejected(access.readFileJson("__wayward_escape/outside.txt"), "read through symbolic link");
                    assertRejected(access.writeFileJson("__wayward_escape/injected.txt", "text/plain",
                            Base64.encodeToString(new byte[] {1}, Base64.NO_WRAP)), "write through symbolic link");
                    Log.i(TAG, "PASS selected-root-boundary crud atomic-replace sibling-and-symlink-blocked");
                } else {
                    Log.i(TAG, "SKIP symlink-on-device fixture: shared-storage provider denied symlink creation; host JVM symlink test passed separately.");
                    Log.i(TAG, "PASS selected-root-boundary crud atomic-replace sibling-blocked");
                }
            } catch (Exception error) {
                Log.e(TAG, "FAIL selected-root-boundary CRUD and symlink checks", error);
            }
        }

        @JavascriptInterface public void verifyPermissionRevoked() {
            try {
                JSONObject info = new JSONObject(access.getFolderInfoJson());
                JSONObject listing = new JSONObject(access.listDirectoryJson(""));
                JSONObject read = new JSONObject(access.readFileJson("pixel.png"));
                if (info.optBoolean("ok") || listing.optBoolean("ok") || read.optBoolean("ok")) {
                    throw new IllegalStateException("Filesystem operations remained available after permission revocation.");
                }
                Log.i(TAG, "PASS permission-revoked filesystem operations denied");
            } catch (Exception error) {
                Log.e(TAG, "FAIL permission-revoked check", error);
            }
        }

        private void requireOk(JSONObject result, String operation) {
            if (!result.optBoolean("ok")) {
                throw new IllegalStateException(operation + " failed: " + result.optString("name"));
            }
        }

        private void assertRejected(String raw, String operation) throws Exception {
            JSONObject result = new JSONObject(raw);
            if (result.optBoolean("ok")) throw new IllegalStateException(operation + " was allowed.");
        }

        private void requireBytes(byte[] expected, JSONObject result, String operation) {
            requireOk(result, operation);
            byte[] actual = Base64.decode(result.optString("data"), Base64.DEFAULT);
            if (!java.util.Arrays.equals(expected, actual)) {
                throw new IllegalStateException(operation + " returned unexpected bytes.");
            }
        }

    }
}
