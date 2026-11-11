#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');

const main = fs.readFileSync('app/src/main/java/com/wayward/launcher/MainActivity.java', 'utf8');
const application = fs.readFileSync('app/src/main/java/com/wayward/launcher/LauncherApplication.java', 'utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');
const gradle = fs.readFileSync('app/build.gradle', 'utf8');

const homeStart = main.indexOf('private void showHome(){');
const homeEnd = main.indexOf('private void showLauncherInfoDialog()', homeStart);
assert(homeStart >= 0 && homeEnd > homeStart, 'home screen method must exist');
const home = main.slice(homeStart, homeEnd);

assert.match(home, /＋ 폴더/, 'home must expose folder registration');
assert.match(home, /내 게임/, 'home must expose the registered game list');
assert.match(home, /여러 게임을 독립 세션으로 실행/, 'home must describe independent sessions');
assert.doesNotMatch(home, /ZIP|ImageGen|이미지 생성|이미지 서비스/, 'home must not expose unfinished features');

assert.doesNotMatch(main, /installImageGenDocumentStartScript\(origin\);/, 'ImageGen document-start injection must be disabled');
assert.doesNotMatch(main, /@JavascriptInterface public void imageGenProxyFetch/, 'ImageGen proxy bridge must not be exposed');
assert.doesNotMatch(main, /@JavascriptInterface public String listImageProviders/, 'image provider bridge must not be exposed');
assert.doesNotMatch(application, /DiagnosticsUploader\.uploadPending/, 'automatic remote diagnostics upload must be disabled');

assert.doesNotMatch(manifest, /ImageGenService|ImageGenPanelActivity|ImageProviderActivity|ImageDiagnosticsActivity/, 'unfinished ImageGen components must not be registered');
assert.match(main, /startGoogleDriveAuth\(String nonce\)/, 'native Drive authorization bridge must exist');
assert.match(main, /takePendingStateForNonce\(String nonce\)/, 'nonce-scoped one-time result handoff must exist');
assert.match(main, /clearGoogleAccessToken\(String token\)/, 'invalid native Drive tokens must be clearable');
assert.match(gradle, /play-services-auth:21\.6\.0/, 'Google Identity Services dependency must be pinned');

console.log('Official v1 scope checks passed');
