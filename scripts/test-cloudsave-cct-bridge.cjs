'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const activity = read('app/src/main/java/com/wayward/launcher/MainActivity.java');
const bridge = read('app/src/main/java/com/wayward/launcher/GoogleAuthCustomTabsBridge.java');
const injected = read('app/src/main/assets/wayward-google-auth-bridge.js');
const manifest = read('app/src/main/AndroidManifest.xml');
const gradle = read('app/build.gradle');

assert.match(gradle, /androidx\.browser:browser:1\.8\.0/);
assert.match(manifest, /androidx\.browser\.customtabs\.PostMessageService/);
assert.match(manifest, /android:exported="true"/);
assert.match(bridge, /CustomTabsClient\.getPackageName/);
assert.match(bridge, /client\.warmup\(0L\)/);
assert.match(bridge, /validateRelationship\(/);
assert.match(bridge, /RELATION_USE_AS_ORIGIN/);
assert.match(bridge, /requestPostMessageChannel\(/);
assert.match(bridge, /onMessageChannelReady\(/);
assert.match(bridge, /onPostMessage\(/);
assert.match(bridge, /wayward-cloudsave-cct-init/);
assert.match(bridge, /MAX_MESSAGE_BYTES = 12 \* 1024 \* 1024/);
assert.match(bridge, /CHUNK_CHARS = 24 \* 1024/);
assert.match(bridge, /payload\.optInt\("version", -1\)/);
assert.match(bridge, /pendingNonce\.equals\(payload\.optString\("nonce", ""\)\)/);
assert.doesNotMatch(bridge, /accessToken|refreshToken|client_secret|workers\.dev|vercel\.app|render\.com/i);

const authOpenStart = activity.indexOf('@JavascriptInterface public boolean openGoogleAuthPage');
const authOpenEnd = activity.indexOf('@JavascriptInterface public boolean sendGoogleAuthMessage', authOpenStart);
assert.ok(authOpenStart >= 0 && authOpenEnd > authOpenStart, 'native auth URL opener must be present');
const authOpen = activity.slice(authOpenStart, authOpenEnd);
assert.match(authOpen, /googleAuthCustomTabsBridge\.open/);
assert.doesNotMatch(authOpen, /Intent\.ACTION_VIEW/);
assert.match(activity, /@JavascriptInterface public boolean sendGoogleAuthMessage/);
assert.match(activity, /@JavascriptInterface public void closeGoogleAuthPage/);
assert.match(activity, /window\.__waywardCctReceive/);
assert.match(activity, /window\.__waywardCctMarkClosed/);

assert.match(injected, /transport', 'custom-tabs'/);
assert.match(injected, /function makePopupProxy/);
assert.match(injected, /postMessage\(message, targetOrigin\)/);
assert.match(injected, /host\.sendGoogleAuthMessage\(raw\)/);
assert.match(injected, /Object\.defineProperty\(event, 'source'/);
assert.match(injected, /origin: AUTH_ORIGIN/);
assert.doesNotMatch(injected, /accessToken|refreshToken|client_secret|workers\.dev|vercel\.app|render\.com/i);
assert.doesNotMatch(injected, /return \{ closed: false, close\(\)/);

console.log('Custom Tabs static CloudSave bridge contract checks passed');
