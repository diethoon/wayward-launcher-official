'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const activity = read('app/src/main/java/com/wayward/launcher/MainActivity.java');
const dialog = read('app/src/main/java/com/wayward/launcher/GoogleAuthInAppDialog.java');
const injected = read('app/src/main/assets/wayward-google-auth-bridge.js');
const gradle = read('app/build.gradle');

assert.match(gradle, /androidx\.webkit:webkit:1\.12\.1/);
assert.match(gradle, /play-services-auth:21\.6\.0/);
assert.match(dialog, /WebViewCompat\.addWebMessageListener/);
assert.match(dialog, /Collections\.singleton\(AUTH_ORIGIN\)/);
assert.match(dialog, /sourceOrigin == null/);
assert.match(dialog, /!isMainFrame/);
assert.match(dialog, /native-webview/);
assert.match(dialog, /activeNonce\.equals\(command\.optString\("nonce", ""\)\)/);
assert.match(dialog, /MAX_MESSAGE_BYTES = 12 \* 1024 \* 1024/);
assert.match(dialog, /CHUNK_CHARS = 24 \* 1024/);
assert.match(dialog, /Base64\.encodeToString/);
assert.match(dialog, /replyProxy\.postMessage/);
assert.doesNotMatch(dialog, /Intent\.ACTION_VIEW|CustomTabsIntent|client_secret|refresh_token|workers\.dev|vercel\.app|render\.com/i);

const authStart = activity.indexOf('@JavascriptInterface public boolean openGoogleAuthPage');
const authEnd = activity.indexOf('@JavascriptInterface public boolean sendGoogleAuthMessage', authStart);
assert.ok(authStart >= 0 && authEnd > authStart, 'in-app auth URL opener must be present');
const authOpen = activity.slice(authStart, authEnd);
assert.match(authOpen, /native-webview/);
assert.match(authOpen, /googleAuthInAppDialog\.open/);
assert.doesNotMatch(authOpen, /Intent\.ACTION_VIEW|googleAuthCustomTabsBridge/);
assert.match(activity, /beginGoogleDriveAuth\(nonce, selectAccount\)/);
assert.match(activity, /deliverPendingGoogleAuthResult\(nonce\)/);
assert.match(activity, /googleAuthInAppDialog\.deliverAuthResult\(payload\)/);
assert.match(activity, /beginGoogleDriveRevoke\(nonce\)/);
assert.doesNotMatch(activity, /googleAuthCustomTabsBridge/);
assert.match(activity, /takePendingStateForNonce\\(String nonce\\)\\s*\\{return \"\";\\}/);
assert.match(activity, /WebViewFeature\.WEB_MESSAGE_LISTENER/);
assert.match(activity, /window\.__waywardCctReceive/);
assert.match(activity, /window\.__waywardCctMarkClosed/);

assert.match(injected, /transport', 'native-webview'/);
assert.match(injected, /function makePopupProxy/);
assert.match(injected, /postMessage\(message, targetOrigin\)/);
assert.match(injected, /host\.sendGoogleAuthMessage\(raw\)/);
assert.match(injected, /Object\.defineProperty\(event, 'source'/);
assert.match(injected, /origin: AUTH_ORIGIN/);
assert.doesNotMatch(injected, /accessToken|refreshToken|client_secret|workers\.dev|vercel\.app|render\.com/i);
assert.doesNotMatch(injected, /return \{ closed: false, close\(\)/);

console.log('Static CloudSave in-app auth bridge contract checks passed');
