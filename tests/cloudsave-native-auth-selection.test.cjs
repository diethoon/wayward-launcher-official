#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const mainActivity = fs.readFileSync(
  path.join(root, 'app/src/main/java/com/wayward/launcher/MainActivity.java'),
  'utf8'
);
const gradle = fs.readFileSync(path.join(root, 'app/build.gradle'), 'utf8');

assert.match(
  mainActivity,
  /@JavascriptInterface\s+public void startGoogleDriveAuthWithPolicy\(String nonce,boolean forceSelectAccount\)/
);
assert.match(
  mainActivity,
  /if\(nonce==null\|\|!nonce\.matches\("\[a-f0-9\]\{48\}"\)\)return;/
);
assert.match(
  mainActivity,
  /runOnUiThread\(\(\)->beginGoogleDriveAuth\(nonce,forceSelectAccount\)\)/
);
assert.match(
  mainActivity,
  /private void beginGoogleDriveAuth\(String nonce,boolean forceSelectAccount\)/
);
assert.match(
  mainActivity,
  /if\(forceSelectAccount\)\{\s*requestBuilder\.setPrompt\(AuthorizationRequest\.Prompt\.SELECT_ACCOUNT\);\s*\}/
);
assert.match(
  mainActivity,
  /private void beginGoogleDriveAuth\(String nonce\)\{\s*beginGoogleDriveAuth\(nonce,false\);\s*\}/
);
assert.match(gradle, /versionCode\s+107/);
assert.match(gradle, /versionName\s+'1\.0\.7'/);

console.log('CloudSave native account-selection bridge contract passed');
