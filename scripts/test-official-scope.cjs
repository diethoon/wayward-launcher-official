#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');

const main = fs.readFileSync('app/src/main/java/com/wayward/launcher/MainActivity.java', 'utf8');
const application = fs.readFileSync('app/src/main/java/com/wayward/launcher/LauncherApplication.java', 'utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');
const gradle = fs.readFileSync('app/build.gradle', 'utf8');
const cache = fs.readFileSync('app/src/main/java/com/wayward/launcher/SafImageCache.java', 'utf8');
const diagnostics = fs.readFileSync('app/src/main/java/com/wayward/launcher/DiagnosticsLogger.java', 'utf8');

const homeStart = main.indexOf('private void showHome(){');
const homeEnd = main.indexOf('private void showLauncherInfoDialog()', homeStart);
assert(homeStart >= 0 && homeEnd > homeStart, 'home screen method must exist');
const home = main.slice(homeStart, homeEnd);

assert.match(home, /＋ 폴더/, 'home must expose folder registration');
assert.match(home, /내 게임/, 'home must expose the registered game list');
assert.match(home, /여러 게임을 독립 세션으로 실행/, 'home must describe independent sessions');
assert.doesNotMatch(home, /ZIP|ImageGen|이미지 생성|이미지 서비스/, 'home must not expose unfinished features');
assert.match(home, /applySystemBarInsets\(root\)/, 'home and game root must apply safe system-bar insets');
assert.match(main, /private void applySystemBarInsets\(View target\)/, 'system-bar inset handler must exist');
assert.match(main, /Build\.VERSION\.SDK_INT < 35/, 'inset padding must be limited to Android 15+ edge-to-edge devices');
assert.match(main, /WindowInsets\.Type\.systemBars\(\)\s*\|\s*WindowInsets\.Type\.displayCutout\(\)/, 'status/navigation bars and display cutout must be protected');

assert.doesNotMatch(main, /installImageGenDocumentStartScript\(origin\);/, 'ImageGen document-start injection must be disabled');
assert.doesNotMatch(main, /@JavascriptInterface public void imageGenProxyFetch/, 'ImageGen proxy bridge must not be exposed');
assert.doesNotMatch(main, /@JavascriptInterface public String listImageProviders/, 'image provider bridge must not be exposed');
assert.doesNotMatch(application, /DiagnosticsUploader|imagegen_service/, 'application must not retain image-server process hooks');
assert.doesNotMatch(main, /ImageGen|imageGen|ImageProvider|ComfyEndpoint|comfyUrl|imageStudioModId|OPEN_IMAGE_STUDIO_SETTINGS/,
  'official launcher must not retain dormant image-generation server code');
assert.match(main, /tree\.prewarmImageDirectories\(\)/,
  'game launch must start image-directory metadata prefetch before WebView loading');
assert.match(main, /MAX_IMAGE_PREFETCH_DIRECTORIES = 256/,
  'image metadata prefetch must have a bounded directory budget');
assert.match(main, /directoryCount < MAX_IMAGE_PREFETCH_DIRECTORIES/,
  'image metadata prefetch must enforce its directory bound');
assert.match(main, /MAX_IMAGE_PREFETCH_DEPTH = 6/,
  'image metadata prefetch must cover deeply nested portrait and thumbnail paths');
assert.match(main, /childCount < MAX_IMAGE_PREFETCH_CHILDREN/,
  'image metadata prefetch must enforce its child-entry bound');
assert.match(main, /imageDirectoryPriority\(left\.name\)/,
  'thumbnail and portrait directories must receive traversal priority');
assert.doesNotMatch(diagnostics, /DiagnosticsUploader|uploadPending|readPending|acknowledge|https?:\/\//,
  'launcher diagnostics must remain local-only without a remote uploader');

assert.doesNotMatch(manifest, /ImageGenService|ImageGenPanelActivity|ImageProviderActivity|ImageDiagnosticsActivity|network_security_config/,
  'unused server and provider components must not be registered');
assert.match(main, /startGoogleDriveAuth\(String nonce\)/, 'native Drive authorization bridge must exist');
assert.match(main, /takePendingStateForNonce\(String nonce\)/, 'nonce-scoped one-time result handoff must exist');
assert.match(main, /clearGoogleAccessToken\(String token\)/, 'invalid native Drive tokens must be clearable');
assert.match(gradle, /play-services-auth:21\.6\.0/, 'Google Identity Services dependency must be pinned');
assert.match(gradle, /storeType\s+['"]JKS['"]/, 'CI must normalize the PKCS#12 upload key to a Java keystore before Gradle signing');
assert.match(gradle, /keyPassword\s+System\.getenv\('ANDROID_KEYSTORE_PASSWORD'\)/, 'converted JKS key and store must use the P12 import password');
assert.doesNotMatch(gradle, /ANDROID_KEY_PASSWORD/, 'release signing must not depend on a separately mis-entered key password');

const sessionStart = main.indexOf('private void openProfileSession(String id)');
const sessionEnd = main.indexOf('private void removeProfile(String id)', sessionStart);
assert(sessionStart >= 0 && sessionEnd > sessionStart, 'profile launch method must exist');
assert.doesNotMatch(main.slice(sessionStart, sessionEnd), /ensureImageGenServer\(/,
  'opening a game session must not automatically start the disabled ImageGen service');

const webStart = main.indexOf('private void createWeb(){');
const webEnd = main.indexOf('private void beginGoogleDriveAuth(', webStart);
assert(webStart >= 0 && webEnd > webStart, 'WebView construction method must exist');
assert.doesNotMatch(main.slice(webStart, webEnd), /installImageGenDocumentStartScript\(/,
  'official game sessions must not install ImageGen document-start scripts');

assert.doesNotMatch(cache, /completed\.get|SHARED_LOAD_TIMEOUT_SECONDS/,
  'image delivery must not wait for an in-flight cache writer');
assert.match(cache, /inflight-bypass/,
  'concurrent image requests must bypass an in-flight cache fill immediately');
assert.match(cache, /CACHE_EXECUTOR\.execute/,
  'image cache population must run on the bounded background executor');
assert.match(cache, /chunks\.offer\(copy\)/,
  'image bytes must enter a bounded non-blocking cache queue');
assert.match(cache, /new ArrayBlockingQueue<>\(16\)/,
  'the image cache queue must have a fixed memory bound');
const teeStart = cache.indexOf('private static final class TeeInputStream');
const teeEnd = cache.indexOf('static final class OpenResult', teeStart);
assert(teeStart >= 0 && teeEnd > teeStart, 'lightweight stream tee must exist');
const tee = cache.slice(teeStart, teeEnd);
assert.match(tee, /load\.enqueue\(buffer, offset, count\)/,
  'the WebView stream must offer byte copies to the background cache queue');
assert.doesNotMatch(tee, /append\(|output\.write/,
  'the WebView stream must never perform cache disk writes');
for (const source of [
  'ComfyEndpoint.java', 'DiagnosticsUploader.java', 'ImageDiagnosticsActivity.java',
  'ImageGenBackendInstaller.java', 'ImageGenLocalProviderServer.java',
  'ImageGenPanelActivity.java', 'ImageGenProxy.java', 'ImageGenService.java',
  'ImageGenStatusStore.java', 'ImageProviderActivity.java', 'ImageProviderBridge.java',
  'ImageProviderClient.java', 'ImageProviderStore.java'
]) {
  assert.equal(fs.existsSync(`app/src/main/java/com/wayward/launcher/${source}`), false,
    `${source} must be removed from the official 1.0.0 build.`);
}
assert.equal(fs.existsSync('app/src/main/res/xml/network_security_config.xml'), false,
  'obsolete loopback cleartext server policy must be removed');

assert.match(manifest, /android:icon="@mipmap\\/ic_launcher"/,
  'launcher must use the generated custom app icon');
assert.match(manifest, /android:roundIcon="@mipmap\\/ic_launcher_round"/,
  'round launcher icon must be registered');
const iconGenerator = fs.readFileSync('scripts/generate_launcher_icons.py', 'utf8');
assert.match(iconGenerator, /2f23c94d7aea3091bb3bf394131f550422fd61d1/,
  'launcher icon generation must be pinned to the uploaded source image');
assert.match(iconGenerator, /CROP = \\(0\\.12, 0\\.065, 0\\.88, 0\\.83\\)/,
  'launcher icon crop must frame the central character from just above the head to the knees');
