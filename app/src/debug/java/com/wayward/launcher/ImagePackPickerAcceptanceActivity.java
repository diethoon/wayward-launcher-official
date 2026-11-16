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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        verifyExisting = getIntent().getBooleanExtra("verify_existing", false);
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
                if (verifyExisting) {
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
                    throw new IllegalStateException("Persisted SAF access is unavailable.");
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
    }
}
