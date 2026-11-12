#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(
  path.join(__dirname, '../app/src/main/java/com/wayward/launcher/MainActivity.java'),
  'utf8'
);

assert.match(source, /startGoogleDriveAuthWithPolicy\(String nonce,boolean selectAccount\)/);
assert.match(source, /beginGoogleDriveAuth\(nonce,selectAccount\)/);
assert.match(source, /requestBuilder\.setPrompt\(AuthorizationRequest\.Prompt\.SELECT_ACCOUNT\)/);
assert.match(source, /private void beginGoogleDriveAuth\(String nonce,boolean selectAccount\)/);
assert.match(source, /revokeGoogleDriveAccess\(String nonce\)/);
assert.match(source, /Identity\.getAuthorizationClient\(MainActivity\.this\)\.revokeAccess\(request\)/);
assert.match(source, /storeGoogleRevokeResult\(nonce,true,null\)/);
assert.match(source, /storeGoogleRevokeResult\(nonce,false,msg\(error\)\)/);
assert.match(source, /clearGoogleAccessToken\(String token\)/);

// A one-argument compatibility entry point remains for older WML builds.
assert.match(source, /startGoogleDriveAuth\(String nonce\)\s*\{\s*startGoogleDriveAuthWithPolicy\(nonce,false\);/);

console.log('Android CloudSave session-policy bridge contract passed');
