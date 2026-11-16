#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const mainActivity = fs.readFileSync(
  path.join(root, 'app/src/main/java/com/wayward/launcher/MainActivity.java'),
  'utf8'
);
const bridge = fs.readFileSync(
  path.join(root, 'app/src/main/assets/wayward-google-auth-bridge.js'),
  'utf8'
);

const count = (source, expression) => (source.match(expression) || []).length;

// Preserve the legacy native entry point while exposing the explicit policy API.
assert.match(
  mainActivity,
  /@JavascriptInterface\s+public void startGoogleDriveAuth\(String nonce\)\s*\{\s*startGoogleDriveAuthWithPolicy\(nonce,false\);\s*\}/
);
assert.match(
  mainActivity,
  /@JavascriptInterface\s+public void startGoogleDriveAuthWithPolicy\(String nonce,boolean forceSelectAccount\)\s*\{\s*if\(nonce==null\|\|!nonce\.matches\("\[a-f0-9\]\{48\}"\)\)return;\s*runOnUiThread\(\(\)->beginGoogleDriveAuth\(nonce,forceSelectAccount\)\);\s*\}/
);
assert.equal(
  count(mainActivity, /@JavascriptInterface\s+public void startGoogleDriveAuth\(/g),
  1,
  'legacy native entry point must be declared once'
);
assert.equal(
  count(mainActivity, /@JavascriptInterface\s+public void startGoogleDriveAuthWithPolicy\(/g),
  1,
  'policy native entry point must be declared once'
);

// The old private entry point remains compatible and defaults to normal account selection.
assert.match(
  mainActivity,
  /private void beginGoogleDriveAuth\(String nonce\)\s*\{\s*beginGoogleDriveAuth\(nonce,false\);\s*\}/
);
assert.match(
  mainActivity,
  /private void beginGoogleDriveAuth\(String nonce,boolean forceSelectAccount\)/
);
assert.match(
  mainActivity,
  /if\(forceSelectAccount\)\s*\{\s*requestBuilder\.setPrompt\(AuthorizationRequest\.Prompt\.SELECT_ACCOUNT\);\s*\}/
);
assert.equal(
  count(mainActivity, /AuthorizationRequest\.builder\(\)/g),
  1,
  'CloudSave authorization must use one native authorization request builder'
);

// Logout must clear the cached native token using the existing Google Identity API.
assert.match(
  mainActivity,
  /@JavascriptInterface\s+public void clearGoogleAccessToken\(String token\)/
);
assert.match(
  mainActivity,
  /clearToken\(ClearTokenRequest\.builder\(\)\.setToken\(token\)\.build\(\)\)/
);
assert.ok(mainActivity.includes('@JavascriptInterface public void cancelGoogleDriveAuth(String nonce)'));
assert.ok(mainActivity.includes('pendingGoogleAuthRequestNonces.remove(req)'));
assert.ok(mainActivity.includes('reserveGoogleAuthRequestCode(nonce)'));
assert.ok(mainActivity.includes('startIntentSenderForResult(pending.getIntentSender(),requestCode,'));
assert.ok(mainActivity.includes('clearGoogleDriveAuthRequest(nonce)'));
assert.ok(mainActivity.includes('PENDING_GOOGLE_AUTH_RESULTS.remove(nonce)'));
assert.ok(mainActivity.includes('@JavascriptInterface public void revokeGoogleDriveAccess(String nonce)'));
assert.ok(mainActivity.includes('storeGoogleRevokeResult(nonce,true,null)'));
assert.ok(mainActivity.includes('storeGoogleRevokeResult(nonce,false,msg(error))'));

// The WebView bridge forwards the account policy into the native adapter.
assert.match(bridge, /const accountPolicy = parsed\.searchParams\.get\('login'\) \|\| 'remember'/);
assert.match(bridge, /const forceSelectAccount = accountPolicy === 'select_account'/);
assert.match(bridge, /'https:\/\/jeiky224\.github\.io'/,
  'current CloudSave Pages auth origin must be intercepted');
assert.doesNotMatch(bridge, /vercel\.app/i,
  'legacy Vercel authentication origin must not be allowed');
assert.match(bridge, /host\.startGoogleDriveAuthWithPolicy\(nonce, forceSelectAccount\)/);
assert.match(bridge, /else if \(!forceSelectAccount && hasLegacyStart\)/);
assert.match(bridge, /host\.startGoogleDriveAuth\(nonce\);/);
assert.match(bridge, /deliverStartError\('현재 Android 런처는 Google 계정 선택 API를 제공하지 않습니다\./);

// The WebView bridge only forwards the native result. It must not create a second GIS OAuth flow.
assert.match(
  bridge,
  /document\.addEventListener\('click',\s*clearGoogleTokenOnExplicitLogout,\s*true\)/
);
assert.match(bridge, /DRIVE_SESSION_TOKEN_KEY = 'wml-google-drive-session-token-v1'/);
assert.match(bridge, /lastGoogleDriveAccessToken = data\.accessToken/);
assert.doesNotMatch(bridge, /google\.accounts\.(?:oauth2|id)\s*\./i);
assert.doesNotMatch(bridge, /gapi\.auth2/i);

console.log('CloudSave native account-selection and logout policy contract passed');
