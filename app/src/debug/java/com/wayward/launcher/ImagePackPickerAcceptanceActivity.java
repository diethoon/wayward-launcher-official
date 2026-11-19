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


public final class ImagePackPickerAcceptanceActivity extends Activity {
    private static final String TAG = "WaywardImagePackTest";
    private static final String PROFILE = "acceptance_imagepack";
    private static final String TEST_ORIGIN = "https://wayward-imagepack-test.local";
    private ImagePackDirectoryAccess access;
    private WebView webView;
    private boolean verifyExisting;
    private boolean verifyRevoked;
    private String initialTreeUri = "";
    private String revokedTestRootDocumentUri = "";
    private String revokedTestFileUri = "";
    private String pickerScript;
    private String folderPickerCapability = "";
    // loadDataWithBaseURL can report a different WebView.getUrl() value than its trusted page callback.
    private String verifiedTestPageUrl = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        verifyExisting = getIntent().getBooleanExtra("verify_existing", false);
        verifyRevoked = getIntent().getBooleanExtra("verify_revoked", false);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        String initialUri = getIntent().getStringExtra("initial_uri");
        initialTreeUri = initialUri == null ? "" : initialUri;
        if (initialUri != null && !initialUri.isEmpty()) {
            // Seed the same persisted-URI preference consumed by the production constructor.
            getSharedPreferences("wayward_launcher", MODE_PRIVATE).edit()
                    .putString("image_pack_tree_uri_" + PROFILE, Uri.parse(initialUri).toString())
                    .commit();
        }
        access = new ImagePackDirectoryAccess(this, webView, PROFILE);
        if (verifyRevoked) revokePersistedTreeGrantForTesting();
        webView.addJavascriptInterface(new TestWmlHost(), "WMLHost");
        webView.addJavascriptInterface(new AcceptanceBridge(), "AcceptanceBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(
                    WebView view, String url, android.graphics.Bitmap favicon) {
                if (view == webView) verifiedTestPageUrl = "";
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (view == webView && ImagePackFolderPickerScript.isSameOrigin(url, TEST_ORIGIN)) {
                    verifiedTestPageUrl = url;
                } else if (view == webView) {
                    verifiedTestPageUrl = "";
                }
                Log.i(TAG, "PAGE_FINISHED verifiedOrigin="
                        + ImagePackFolderPickerScript.isSameOrigin(verifiedTestPageUrl, TEST_ORIGIN)
                        + " callbackOrigin=" + safeOrigin(url)
                        + " webViewOrigin=" + safeOrigin(view.getUrl()));
                ImagePackFolderPickerScript.installAfterPageFinished(
                        view, url, TEST_ORIGIN, pickerScript, installed -> {
                            if (!installed) {
                                Log.e(TAG, "FAIL image-pack picker script runtime installation");
                            } else if (verifyRevoked) {
                                view.evaluateJavascript("window.AcceptanceBridge.verifyPermissionRevoked()", null);
                            } else if (verifyExisting) {
                                view.evaluateJavascript("window.AcceptanceBridge.verifyPersistedAccess()", null);
                            } else {
                                view.evaluateJavascript("document.getElementById('pick').click()", null);
                            }
                        });
            }
        });
        setContentView(webView);

        try {
            folderPickerCapability = ImagePackFolderPickerScript.createCapability();
            pickerScript = ImagePackFolderPickerScript.read(this, folderPickerCapability);
            String page = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                    + "<body><button id='pick'>Choose image-pack folder</button><div id='result'>READY</div>"
                    + "<script>document.getElementById('pick').addEventListener('click',async()=>{"
                    + "try{const directory=await window.showDirectoryPicker({mode:'readwrite'});"
                    + "if(!directory||directory.kind!=='directory'||typeof directory.getFileHandle!=='function'||typeof directory.entries!=='function')"
                    + "throw new Error('showDirectoryPicker did not return a FileSystemDirectoryHandle-compatible object');"
                    + "const names=[];for await(const [name,handle] of directory.entries())names.push(name);"
                    + "if(!names.includes('pixel.png'))throw new Error('Directory handle listing did not include pixel.png');"
                    + "const fileHandle=await directory.getFileHandle('pixel.png');"
                    + "const file=await fileHandle.getFile();"
                    + "const bytes=new Uint8Array(await file.arrayBuffer());"
                    + "const signature=Array.from(bytes.slice(0,8)).join(',');"
                    + "if(signature!=='137,80,78,71,13,10,26,10')throw new Error('Selected file did not contain a PNG signature');"
                    + "document.getElementById('result').textContent='PASS_FS_DIRECTORY_HANDLE name='+directory.name+' listed='+names.length+' bytes='+bytes.length;"
                    + "window.AcceptanceBridge.verifyFilesystemOperations();"
                    + "window.AcceptanceBridge.report('PASS fs-directory-handle name='+directory.name+' listed='+names.length+' bytes='+bytes.length);"
                    + "}catch(error){document.getElementById('result').textContent='FAIL '+String(error&&error.name)+':'+String(error&&error.message);"
                    + "window.AcceptanceBridge.report('FAIL picked '+String(error&&error.name)+':'+String(error&&error.message));}"
                    + "});</script></body></html>";
            webView.loadDataWithBaseURL(TEST_ORIGIN + "/", page, "text/html", "UTF-8", null);
        } catch (Exception error) {
            Log.e(TAG, "FAIL test page setup", error);
        }
    }


    private void revokePersistedTreeGrantForTesting() {
        String key = "image_pack_tree_uri_" + PROFILE;
        String saved = getSharedPreferences("wayward_launcher", MODE_PRIVATE).getString(key, "");
        if (saved == null || saved.isEmpty()) {
            Log.e(TAG, "FAIL revoke persisted SAF grant: no saved tree URI");
            return;
        }
        Uri selected = Uri.parse(saved);
        try {
            JSONObject info = new JSONObject(access.getFolderInfoJson());
            revokedTestRootDocumentUri = info.optString("documentUri", "");
            JSONObject listing = new JSONObject(access.listDirectoryJson(
                    revokedTestRootDocumentUri, "acceptance-revoke-list"));
            org.json.JSONArray entries = listing.optJSONArray("entries");
            if (entries != null) for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && "pixel.png".equals(entry.optString("name"))) {
                    revokedTestFileUri = entry.optString("uri", "");
                    break;
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "FAIL capture document URI before grant revocation", error);
        }
        for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (!selected.equals(permission.getUri())) continue;
            int flags = 0;
            if (permission.isReadPermission()) flags |= android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (flags != 0) getContentResolver().releasePersistableUriPermission(selected, flags);
            getSharedPreferences("wayward_launcher", MODE_PRIVATE).edit().remove(key).commit();
            Log.i(TAG, "PASS test fixture released the persisted SAF tree grant");
            return;
        }
        getSharedPreferences("wayward_launcher", MODE_PRIVATE).edit().remove(key).commit();
        Log.i(TAG, "PASS test fixture confirmed no persisted SAF grant remained");
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
        folderPickerCapability = "";
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

    private static String safeOrigin(String value) {
        if (value == null || value.isEmpty()) return "empty";
        Uri parsed = Uri.parse(value);
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || host == null) return "opaque";
        return scheme + "://" + host + ":" + parsed.getPort();
    }

    private final class TestWmlHost {
        private boolean hasCapability(String capability) {
            return ImagePackFolderPickerScript.matchesCapability(folderPickerCapability, capability);
        }

        private String denied() {
            return "{\\"ok\\":false,\\"name\\":\\"SecurityError\\",\\"message\\":\\"The native folder bridge capability is invalid.\\"}";
        }

        @JavascriptInterface public void chooseImagePackFolder(String requestId, String capability, String mode, String initialUri) {
            runOnUiThread(() -> chooseImagePackFolderOnUiThread(requestId, capability, mode, initialUri));
        }

        private void chooseImagePackFolderOnUiThread(String requestId, String capability, String mode, String initialUri) {
            try {
                boolean trustedOrigin = webView != null
                        && ImagePackFolderPickerScript.isSameOrigin(verifiedTestPageUrl, TEST_ORIGIN);
                boolean trustedCapability = hasCapability(capability);
                Log.i(TAG, "BRIDGE_ORIGIN verified=" + safeOrigin(verifiedTestPageUrl)
                        + " webView=" + safeOrigin(webView == null ? null : webView.getUrl())
                        + " accepted=" + (trustedOrigin && trustedCapability));
                if (!trustedOrigin || !trustedCapability) {
                    access.rejectRequest(requestId,
                            trustedOrigin ? "NotAllowedError" : "SecurityError",
                            "The folder request was rejected by the native test bridge.");
                    return;
                }
                Log.i(TAG, "PASS folder bridge request accepted on UI thread");
                access.openFolderPicker(requestId, mode, initialUri);
            } catch (Exception error) {
                Log.e(TAG, "FAIL folder bridge UI dispatch", error);
                access.rejectRequest(requestId, "NotAllowedError",
                        "Android could not start the system image-pack folder picker.");
            }
        }

        @JavascriptInterface public String listImagePackDirectoryForRequest(
                String capability, String documentUri, String requestId) {
            if (!hasCapability(capability)) return denied();
            if (access == null) return "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}";
            if (documentUri != null && documentUri.startsWith("__WAYWARD_DIR_START__:")) {
                return access.startListDirectory(
                        documentUri.substring("__WAYWARD_DIR_START__:".length()), requestId);
            }
            if (documentUri != null && documentUri.startsWith("__WAYWARD_DIR_ACK__:")) {
                try {
                    int batchIndex = Integer.parseInt(
                            documentUri.substring("__WAYWARD_DIR_ACK__:".length()));
                    if (batchIndex < 0) throw new NumberFormatException("negative batch index");
                    return access.acknowledgeDirectoryBatch(requestId, batchIndex)
                            ? "{\\"ok\\":true}"
                            : "{\\"ok\\":false,\\"name\\":\\"AbortError\\",\\"message\\":\\"The SAF batch is no longer active.\\"}";
                } catch (NumberFormatException invalidBatch) {
                    return "{\\"ok\\":false,\\"name\\":\\"TypeError\\",\\"message\\":\\"The SAF batch index is invalid.\\"}";
                }
            }
            if (documentUri != null && documentUri.startsWith("__WAYWARD_DIR_CANCEL__:")) {
                boolean cancelled = access.cancelDirectoryList(requestId);
                return "{\\"ok\\":true,\\"cancelled\\":" + cancelled + "}";
            }
            if (documentUri != null && documentUri.startsWith("__WAYWARD_DIR_RELEASE__:")) {
                access.releaseDirectoryTreeForRequest(requestId);
                return "{\\"ok\\":true}";
            }
            return access.listDirectoryJson(documentUri, requestId);
        }

        @JavascriptInterface public String readImagePackFileForRequest(
                String capability, String documentUri, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.readFileJson(documentUri, requestId);
        }

        @JavascriptInterface public String openImagePackFileStreamForRequest(
                String capability, String documentUri, String start, String length, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.openFileStreamJson(documentUri, start, length, requestId);
        }

        @JavascriptInterface public String readImagePackFileStreamChunkForRequest(
                String capability, String streamId, String maxBytes, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.readFileStreamChunkJson(streamId, maxBytes, requestId);
        }

        @JavascriptInterface public String closeImagePackFileStreamForRequest(
                String capability, String streamId, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.closeFileStreamJson(streamId, requestId);
        }

        @JavascriptInterface public String createImagePackFile(
                String capability, String parentUri, String name, String mime, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.createFileJson(parentUri, name, mime, requestId);
        }

        @JavascriptInterface public String writeImagePackFile(
                String capability, String documentUri, String mime, String data, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.writeFileJson(documentUri, mime, data, requestId);
        }

        @JavascriptInterface public String createImagePackDirectory(
                String capability, String parentUri, String name, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.createDirectoryJson(parentUri, name, requestId);
        }

        @JavascriptInterface public String deleteImagePackEntry(
                String capability, String documentUri, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.deleteEntryJson(documentUri, requestId);
        }

        @JavascriptInterface public String resolveImagePackEntry(
                String capability, String parentUri, String targetUri, String requestId) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.resolveEntryJson(parentUri, targetUri, requestId);
        }

        @JavascriptInterface public String getImagePackFolderInfo(String capability) {
            if (!hasCapability(capability)) return denied();
            return access == null ? "{\\"ok\\":false,\\"name\\":\\"NotFoundError\\"}"
                    : access.getFolderInfoJson();
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
                String pixelUri = findChildUri(info.getString("documentUri"), "pixel.png");
                JSONObject file = new JSONObject(access.readFileJson(pixelUri, "acceptance-persisted-read"));
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
                JSONObject info = new JSONObject(access.getFolderInfoJson());
                String rootUri = info.getString("documentUri");
                JSONObject createdDirectory = new JSONObject(access.createDirectoryJson(
                        rootUri, "acceptance-crud", "acceptance-create-directory"));
                requireOk(createdDirectory, "create folder");
                String directoryUri = createdDirectory.getString("uri");

                JSONObject createdFile = new JSONObject(access.createFileJson(
                        directoryUri, "replace.png", "image/png", "acceptance-create-file"));
                requireOk(createdFile, "create file");
                String fileUri = createdFile.getString("uri");
                byte[] original = new byte[] {1, 2, 3, 4};
                byte[] replacement = new byte[] {9, 8, 7, 6, 5, 4};
                requireOk(new JSONObject(access.writeFileJson(fileUri, "image/png",
                        Base64.encodeToString(original, Base64.NO_WRAP), "acceptance-write-original")), "write file");
                requireBytes(original, new JSONObject(access.readFileJson(fileUri, "acceptance-read-original")), "read file");
                requireOk(new JSONObject(access.writeFileJson(fileUri, "image/png",
                        Base64.encodeToString(replacement, Base64.NO_WRAP), "acceptance-write-replacement")), "replace existing file");
                requireBytes(replacement, new JSONObject(access.readFileJson(fileUri, "acceptance-read-replacement")), "read replaced file");

                JSONObject children = new JSONObject(access.listDirectoryJson(directoryUri, "acceptance-list-created"));
                if (!children.optBoolean("ok") || !children.optJSONArray("entries").toString().contains("replace.png")) {
                    throw new IllegalStateException("New file is missing from the selected directory URI listing.");
                }
                JSONObject resolved = new JSONObject(access.resolveEntryJson(rootUri, fileUri, "acceptance-resolve"));
                if (!resolved.optBoolean("ok") || resolved.optJSONArray("path") == null
                        || resolved.optJSONArray("path").length() != 2) {
                    throw new IllegalStateException("SAF URI ancestry did not resolve the nested document.");
                }
                requireOk(new JSONObject(access.deleteEntryJson(fileUri, "acceptance-delete-file")), "delete file");
                requireOk(new JSONObject(access.deleteEntryJson(directoryUri, "acceptance-delete-folder")), "delete folder");

                Uri root = Uri.parse(rootUri);
                Uri foreignTree = android.provider.DocumentsContract.buildTreeDocumentUri(
                        root.getAuthority(), "wayward-unrelated-tree");
                Uri foreignDocument = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        foreignTree, "unrelated-document");
                String outsideUri = foreignDocument.toString();
                assertRejected(access.listDirectoryJson(outsideUri, "acceptance-list-outside"), "list outside tree");
                assertRejected(access.readFileJson(outsideUri, "acceptance-read-outside"), "read outside tree");
                assertRejected(access.writeFileJson(outsideUri, "text/plain",
                        Base64.encodeToString(new byte[] {0}, Base64.NO_WRAP), "acceptance-write-outside"), "write outside tree");
                assertRejected(access.createDirectoryJson(outsideUri, "forbidden", "acceptance-create-outside"),
                        "create outside tree");
                assertRejected(access.deleteEntryJson(outsideUri, "acceptance-delete-outside"), "delete outside tree");
                Log.i(TAG, "PASS SAF document-uri CRUD, ancestry, and cross-tree access denied");
            } catch (Exception error) {
                Log.e(TAG, "FAIL SAF document-uri CRUD and boundary checks", error);
            }
        }

        @JavascriptInterface public void verifyPermissionRevoked() {
            try {
                Uri tree = Uri.parse(initialTreeUri);
                String rootUri = revokedTestRootDocumentUri.isEmpty()
                        ? android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                tree, android.provider.DocumentsContract.getTreeDocumentId(tree)).toString()
                        : revokedTestRootDocumentUri;
                JSONObject info = new JSONObject(access.getFolderInfoJson());
                JSONObject listing = new JSONObject(access.listDirectoryJson(rootUri, "acceptance-revoked-list"));
                JSONObject read = new JSONObject(access.readFileJson(revokedTestFileUri, "acceptance-revoked-read"));
                if (info.optBoolean("ok") || listing.optBoolean("ok") || read.optBoolean("ok")) {
                    throw new IllegalStateException("Filesystem operations remained available after permission revocation.");
                }
                Log.i(TAG, "PASS permission-revoked filesystem operations denied");
            } catch (Exception error) {
                Log.e(TAG, "FAIL permission-revoked check", error);
            }
        }

        private String findChildUri(String parentUri, String name) throws Exception {
            JSONObject listing = new JSONObject(access.listDirectoryJson(parentUri, "acceptance-find-" + name));
            requireOk(listing, "list selected directory");
            org.json.JSONArray entries = listing.getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (name.equals(entry.optString("name"))) return entry.getString("uri");
            }
            throw new IllegalStateException("SAF child document was not found: " + name);
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
