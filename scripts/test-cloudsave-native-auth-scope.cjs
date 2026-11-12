'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(
  path.join(__dirname, '../app/src/main/java/com/wayward/launcher/MainActivity.java'),
  'utf8'
);

const requiredScope = 'https://www.googleapis.com/auth/drive.appdata';
assert.match(source, /AuthorizationResult\s+authorizationResult\s*=\s*Identity\.getAuthorizationClient\(this\)\.getAuthorizationResultFromIntent\(data\)/);
assert.match(source, /storeGoogleAuthResult\(nonce,\s*authorizationResult\)/);
assert.match(source, /private static void storeGoogleAuthResult\(String nonce,AuthorizationResult result\)/);
assert.match(source, /result\.getGrantedScopes\(\)/);
assert.match(source, /grantedScopes\.contains\(WAYWARD_CLOUDSAVE_DRIVE_SCOPE\)/);
assert.match(source, /WAYWARD_CLOUDSAVE_DRIVE_SCOPE\s*=\s*"https:\/\/www\.googleapis\.com\/auth\/drive\.appdata"/);
assert.match(source, /payload\.put\("scope",grantedScopeText\)/);
assert.match(source, /grantedScopeText\.split\("\\\\s\+"\)/);
assert.match(source, /accessToken\.length\(\)\s*<=\s*4096/);
assert.doesNotMatch(source, /client_secret|refresh_token|workers\.dev|vercel\.app|render\.com/i);
assert.ok(source.includes(requiredScope), 'the same drive.appdata scope must be requested and verified');
console.log('Android native CloudSave granted-scope contract tests passed');
