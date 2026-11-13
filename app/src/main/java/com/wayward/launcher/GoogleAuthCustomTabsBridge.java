package com.wayward.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;

import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsService;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Static GitHub Pages CloudSave bridge. Google access tokens never enter this class:
 * it only transports nonce-bound CloudSave commands and encrypted save envelopes.
 */
final class GoogleAuthCustomTabsBridge {
    interface Listener {
        void onPageMessage(String message);
        void onTabClosed();
        void onBridgeError(String message);
    }

    private static final Uri APP_ORIGIN = Uri.parse("https://jeiky224.github.io");
    private static final String EXPECTED_APP_PACKAGE = "com.wayward.launcher.official";
    private static final String EXPECTED_SIGNER_SHA256 = "F3:CA:95:AD:6C:6C:7D:19:23:27:C8:8F:82:B5:9E:53:B2:C9:89:7C:45:5A:0F:0B:EF:2E:AF:CF:29:A8:D7:54";
    private static final String AUTH_HOST = "jeiky224.github.io";
    private static final String AUTH_PATH = "/cloud/auth.html";
    private static final int MAX_MESSAGE_BYTES = 12 * 1024 * 1024;
    private static final int CHUNK_CHARS = 24 * 1024;
    private static final int MAX_CHUNKS = 768;

    private final Activity activity;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();

    private CustomTabsClient client;
    private CustomTabsSession session;
    private CustomTabsServiceConnection serviceConnection;
    private boolean bound;
    private boolean relationshipValidated;
    private boolean pageNavigationFinished;
    private boolean channelRequested;
    private boolean channelReady;
    private boolean tabLaunched;
    private boolean pauseObserved;
    private boolean destroyed;
    private Uri pendingUri;
    private String pendingNonce = "";
    private String customTabsProviderPackage = "";

    private final CustomTabsCallback callback = new CustomTabsCallback() {
        @Override
        public void onNavigationEvent(int navigationEvent, Bundle extras) {
            if (navigationEvent == NAVIGATION_FINISHED && tabLaunched) {
                pageNavigationFinished = true;
                requestChannelWhenReady();
            }
        }

        @Override
        public void onMessageChannelReady(Bundle extras) {
            channelReady = true;
            sendInitialMessage();
        }

        @Override
        public void onPostMessage(String message, Bundle extras) {
            if (message == null || message.length() > CHUNK_CHARS + 512) return;
            activity.runOnUiThread(() -> {
                if (!destroyed && tabLaunched) listener.onPageMessage(message);
            });
        }

        @Override
        public void onRelationshipValidationResult(int relation, Uri requestedOrigin,
                                                   boolean result, Bundle extras) {
            JSONObject details = new JSONObject();
            try {
                details.put("relation", relation);
                details.put("expectedRelation", CustomTabsService.RELATION_USE_AS_ORIGIN);
                details.put("requestedOrigin", requestedOrigin == null ? "" : requestedOrigin.toString());
                details.put("expectedOrigin", APP_ORIGIN.toString());
                details.put("result", result);
                details.put("providerPackage", customTabsProviderPackage);
                details.put("providerVersion", packageVersion(customTabsProviderPackage));
                details.put("appPackage", activity.getPackageName());
                details.put("appPackageMatches", EXPECTED_APP_PACKAGE.equals(activity.getPackageName()));
                details.put("appSignerSha256", currentSigningFingerprint());
                details.put("appSignerMatches", EXPECTED_SIGNER_SHA256.equals(currentSigningFingerprint()));
            } catch (Throwable ignored) {}
            boolean expectedCallback = relation == CustomTabsService.RELATION_USE_AS_ORIGIN &&
                    requestedOrigin != null && APP_ORIGIN.equals(requestedOrigin);
            DiagnosticsLogger.event(activity, expectedCallback && result ? "INFO" : "ERROR",
                    "oauth", "custom_tabs_dal_validation_result", details);
            if (!expectedCallback) {
                relationshipValidated = false;
                reportError("Custom Tabs 검증 콜백의 관계 또는 Origin이 요청값과 다릅니다.\n" + runtimeDiagnosticSummary());
                return;
            }
            relationshipValidated = result;
            if (!result) {
                reportError("Google은 현재 연결을 인정하지만 기기의 Custom Tabs 검증은 거부했습니다.\n" + runtimeDiagnosticSummary());
                return;
            }
            launchPendingTab();
        }
    };

    GoogleAuthCustomTabsBridge(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    boolean open(String rawUrl) {
        if (destroyed || rawUrl == null || rawUrl.length() > 2048) return false;
        final Uri uri;
        try {
            uri = Uri.parse(rawUrl);
            String nonce = uri.getQueryParameter("nonce");
            String gameOrigin = uri.getQueryParameter("origin");
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                    !AUTH_HOST.equalsIgnoreCase(uri.getHost()) ||
                    uri.getPort() != -1 ||
                    !AUTH_PATH.equals(uri.getPath()) ||
                    uri.getUserInfo() != null ||
                    uri.getFragment() != null ||
                    nonce == null || !nonce.matches("[a-f0-9]{48}") ||
                    !"custom-tabs".equals(uri.getQueryParameter("transport")) ||
                    gameOrigin == null || gameOrigin.isEmpty() || gameOrigin.length() > 256) return false;
            pendingNonce = nonce;
        } catch (Throwable error) {
            return false;
        }
        pendingUri = uri;
        pageNavigationFinished = false;
        channelRequested = false;
        channelReady = false;
        tabLaunched = false;
        pauseObserved = false;
        ensureBound();
        if (relationshipValidated) launchPendingTab();
        return true;
    }

    private void ensureBound() {
        if (bound || destroyed) return;
        String packageName = CustomTabsClient.getPackageName(
                activity, java.util.Collections.singletonList("com.android.chrome"), true);
        if (packageName == null || packageName.isEmpty()) {
            packageName = CustomTabsClient.getPackageName(activity, null);
        }
        if (packageName == null || packageName.isEmpty()) {
            reportError("Custom Tabs를 지원하는 브라우저를 찾지 못했습니다.");
            return;
        }
        customTabsProviderPackage = packageName;
        serviceConnection = new CustomTabsServiceConnection() {
            @Override
            public void onCustomTabsServiceConnected(ComponentName name, CustomTabsClient customTabsClient) {
                if (destroyed) return;
                client = customTabsClient;
                client.warmup(0L);
                session = client.newSession(callback);
                if (session == null) {
                    reportError("Custom Tabs 세션을 만들지 못했습니다.");
                    return;
                }
                boolean accepted = session.validateRelationship(
                        CustomTabsService.RELATION_USE_AS_ORIGIN, APP_ORIGIN, null);
                JSONObject details = new JSONObject();
                try {
                    details.put("providerPackage", customTabsProviderPackage);
                    details.put("providerVersion", packageVersion(customTabsProviderPackage));
                    details.put("appPackage", activity.getPackageName());
                    details.put("appPackageMatches", EXPECTED_APP_PACKAGE.equals(activity.getPackageName()));
                    details.put("appSignerSha256", currentSigningFingerprint());
                    details.put("appSignerMatches", EXPECTED_SIGNER_SHA256.equals(currentSigningFingerprint()));
                    details.put("origin", APP_ORIGIN.toString());
                    details.put("requestAccepted", accepted);
                } catch (Throwable ignored) {}
                DiagnosticsLogger.event(activity, accepted ? "INFO" : "ERROR", "oauth",
                        "custom_tabs_dal_validation_requested", details);
                if (!accepted) reportError("브라우저가 origin 검증 요청을 수락하지 않았습니다.\n" + runtimeDiagnosticSummary());
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                client = null;
                session = null;
                relationshipValidated = false;
                channelReady = false;
                if (!destroyed) reportError("Custom Tabs 서비스 연결이 끊어졌습니다.");
            }
        };
        bound = CustomTabsClient.bindCustomTabsService(activity, packageName, serviceConnection);
        if (!bound) {
            serviceConnection = null;
            reportError("Custom Tabs 서비스에 연결하지 못했습니다.");
        }
    }

    private void launchPendingTab() {
        if (!relationshipValidated || pendingUri == null || destroyed || tabLaunched || session == null) return;
        try {
            CustomTabsIntent intent = new CustomTabsIntent.Builder(session)
                    .setShowTitle(true)
                    .build();
            tabLaunched = true;
            intent.launchUrl(activity, pendingUri);
        } catch (Throwable error) {
            tabLaunched = false;
            reportError("Google 인증 Custom Tab을 열지 못했습니다: " + safeMessage(error));
        }
    }

    private void requestChannelWhenReady() {
        if (!relationshipValidated || !pageNavigationFinished || channelRequested ||
                session == null || !tabLaunched) return;
        channelRequested = true;
        boolean accepted = session.requestPostMessageChannel(APP_ORIGIN, APP_ORIGIN, new Bundle());
        if (!accepted) {
            channelRequested = false;
            reportError("Custom Tabs 양방향 메시지 채널 요청이 거부되었습니다.");
        }
    }

    private void sendInitialMessage() {
        if (!channelReady || session == null || pendingNonce.isEmpty()) return;
        try {
            JSONObject init = new JSONObject();
            init.put("type", "wayward-cloudsave-cct-init");
            init.put("version", 1);
            init.put("nonce", pendingNonce);
            int result = session.postMessage(init.toString(), null);
            if (result != CustomTabsService.RESULT_SUCCESS) {
                reportError("인증 페이지로 보안 메시지 채널 초기화 메시지를 보내지 못했습니다.");
            }
        } catch (Throwable error) {
            reportError("메시지 채널 초기화에 실패했습니다: " + safeMessage(error));
        }
    }

    boolean postGameMessage(String rawMessage) {
        if (destroyed || !tabLaunched || !channelReady || session == null ||
                rawMessage == null) return false;
        final byte[] bytes;
        try {
            if (rawMessage.length() > MAX_MESSAGE_BYTES * 2) return false;
            bytes = rawMessage.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_MESSAGE_BYTES) return false;
            JSONObject payload = new JSONObject(rawMessage);
            if (payload.optInt("version", -1) != 1 ||
                    !pendingNonce.equals(payload.optString("nonce", ""))) return false;
            String type = payload.optString("type", "");
            if (!("wayward-cloudsave-hello".equals(type) ||
                    "wayward-cloudsave-request".equals(type) ||
                    "wayward-cloudsave-native-close".equals(type))) return false;
        } catch (Throwable error) {
            return false;
        }

        if (bytes.length <= CHUNK_CHARS) return postRaw(rawMessage);

        String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
        int total = (encoded.length() + CHUNK_CHARS - 1) / CHUNK_CHARS;
        if (total < 2 || total > MAX_CHUNKS) return false;
        String transferId = randomHex48();
        for (int seq = 0; seq < total; seq++) {
            int start = seq * CHUNK_CHARS;
            int end = Math.min(encoded.length(), start + CHUNK_CHARS);
            JSONObject part = new JSONObject();
            try {
                part.put("__waywardCctChunk", 1);
                part.put("id", transferId);
                part.put("seq", seq);
                part.put("total", total);
                part.put("data", encoded.substring(start, end));
            } catch (Throwable error) {
                return false;
            }
            if (!postRaw(part.toString())) return false;
        }
        return true;
    }

    private boolean postRaw(String message) {
        try {
            return session.postMessage(message, null) == CustomTabsService.RESULT_SUCCESS;
        } catch (Throwable error) {
            reportError("인증 페이지로 게임 요청을 전달하지 못했습니다: " + safeMessage(error));
            return false;
        }
    }

    private String randomHex48() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder out = new StringBuilder(48);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    void onActivityPaused() {
        if (tabLaunched) pauseObserved = true;
    }

    void onActivityResumed() {
        if (!pauseObserved || !tabLaunched) return;
        pauseObserved = false;
        notifyPageClosed();
        resetActiveTab();
    }

    void closeFromGame() {
        if (tabLaunched && channelReady && session != null && !pendingNonce.isEmpty()) {
            try {
                JSONObject close = new JSONObject();
                close.put("type", "wayward-cloudsave-native-close");
                close.put("version", 1);
                close.put("nonce", pendingNonce);
                postRaw(close.toString());
            } catch (Throwable ignored) {}
        }
        tabLaunched = false;
        channelReady = false;
        listener.onTabClosed();
    }

    private void notifyPageClosed() {
        if (tabLaunched && channelReady && session != null && !pendingNonce.isEmpty()) {
            try {
                JSONObject close = new JSONObject();
                close.put("type", "wayward-cloudsave-native-close");
                close.put("version", 1);
                close.put("nonce", pendingNonce);
                postRaw(close.toString());
            } catch (Throwable ignored) {}
        }
    }

    private void resetActiveTab() {
        tabLaunched = false;
        channelReady = false;
        channelRequested = false;
        pageNavigationFinished = false;
        pendingNonce = "";
        pendingUri = null;
        listener.onTabClosed();
    }

    void destroy() {
        destroyed = true;
        notifyPageClosed();
        if (bound && serviceConnection != null) {
            try { activity.unbindService(serviceConnection); } catch (Throwable ignored) {}
        }
        bound = false;
        serviceConnection = null;
        session = null;
        client = null;
        pendingUri = null;
        pendingNonce = "";
    }

    private String currentSigningFingerprint() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return "unavailable:no_signing_info";
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) return "unavailable:no_signers";
            StringBuilder fingerprints = new StringBuilder();
            for (Signature signer : signers) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray());
                if (fingerprints.length() > 0) fingerprints.append(",");
                for (int i = 0; i < digest.length; i++) {
                    if (i > 0) fingerprints.append(":");
                    fingerprints.append(String.format(java.util.Locale.ROOT, "%02X", digest[i] & 0xff));
                }
            }
            return fingerprints.toString();
        } catch (Throwable error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    private String packageVersion(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "unknown";
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName == null ? String.valueOf(info.getLongVersionCode()) :
                    info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    private String runtimeDiagnosticSummary() {
        String appPackage = activity.getPackageName();
        String signer = currentSigningFingerprint();
        return "브라우저: " + customTabsProviderPackage + " / " + packageVersion(customTabsProviderPackage) +
                "\n앱 패키지: " + appPackage +
                "\n패키지 일치: " + EXPECTED_APP_PACKAGE.equals(appPackage) +
                "\n설치 서명 SHA-256: " + signer +
                "\n서명 일치: " + EXPECTED_SIGNER_SHA256.equals(signer);
    }

    private void reportError(String message) {
        activity.runOnUiThread(() -> {
            if (destroyed || activity.isFinishing()) return;
            DiagnosticDialog.show(activity, message);
        });
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null
                ? "알 수 없는 오류" : error.getMessage();
    }
}
