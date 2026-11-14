#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');

const main = fs.readFileSync('app/src/main/java/com/wayward/launcher/MainActivity.java', 'utf8');
const application = fs.readFileSync('app/src/main/java/com/wayward/launcher/LauncherApplication.java', 'utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');
const gradle = fs.readFileSync('app/build.gradle', 'utf8');
const tactileStyle = fs.readFileSync('app/src/main/java/com/wayward/launcher/NeumorphicButtonStyle.java', 'utf8');
const naiSettings = fs.readFileSync('app/src/main/java/com/wayward/launcher/NovelAiSettingsActivity.java', 'utf8');
const webFileChooser = fs.readFileSync('app/src/main/java/com/wayward/launcher/WebFileChooser.java', 'utf8');

const homeStart = main.indexOf('private void showHome(){');
const homeEnd = main.indexOf('private void showLauncherInfoDialog()', homeStart);
assert(homeStart >= 0 && homeEnd > homeStart, 'home screen method must exist');
const home = main.slice(homeStart, homeEnd);

assert.match(home, /＋ 폴더/, 'home must expose folder registration');
assert.match(home, /내 게임/, 'home must expose the registered game list');
assert.match(home, /여러 게임을 독립 세션으로 실행/, 'home must describe independent sessions');
assert.match(home, /이미지 생성 서버 실행/, 'home must expose the explicit local image-generation server switch');
assert.match(main, /glassSettingsButton\(this::openNovelAiSettings\)/, 'the image server row must expose a compact NovelAI settings gear');
assert.match(main, /setContentDescription\("NovelAI 토큰 설정"\)/, 'the settings gear must be accessible');
assert.match(main, /⚙/, 'the settings affordance must render as a gear icon');
assert.match(main, /NeumorphicButtonStyle\.bind\(this,tile,selected,17f\)/,
  'image mode choices must share the common raised/recessed button style');
assert.match(main, /NeumorphicButtonStyle\.bind\(this,gear,false,16f\)/,
  'the settings gear must use the same button style');
assert.match(main, /NeumorphicButtonStyle\.bind\(this,b,false,12f\)/,
  'standard launcher buttons must use the common button style');
assert.match(main, /NeumorphicButtonStyle\.bind\(this,b,false,10f\)/,
  'compact launcher buttons must use the common button style');
assert.match(naiSettings, /NeumorphicButtonStyle\.bind\(this, button, false, 12f\)/,
  'NovelAI settings actions must use the common button style');
assert.match(tactileStyle, /setOutlineSpotShadowColor/,
  'neumorphic controls must tune native outline shadows');
assert.match(tactileStyle, /setElevation/,
  'neumorphic controls must distinguish raised and recessed elevation');
assert.match(tactileStyle, /ACTION_DOWN/,
  'neumorphic controls must depress on touch down');
assert.match(tactileStyle, /recessed/,
  'neumorphic style must represent persistent selected/recessed states');
assert.doesNotMatch(main, /tile\.setClipToOutline\(true\)/,
  'mode button borders must not be clipped by outline clipping');
assert.match(main, /packsParams\.bottomMargin=dp\(8\)/,
  'the image-pack button must have extra room beneath its lower border and shadow');
assert.match(main, /imageModePanel\.setClipChildren\(false\)/,
  'image mode panel must not clip button shadows');
const imageModeUiStart = main.indexOf('private void renderImageModePanel()');
const imageModeUiEnd = main.indexOf('private void openNovelAiSettings()', imageModeUiStart);
assert(imageModeUiStart >= 0 && imageModeUiEnd > imageModeUiStart, 'image mode panel method must exist');
assert.doesNotMatch(main.slice(imageModeUiStart, imageModeUiEnd), /glassModeTile\("NAI 설정"/,
  'the separate NAI settings tile must be removed');
assert.match(home, /이미지팩만 사용/, 'home must expose the all-servers-off image-pack switch');
assert.match(home, /applySystemBarInsets\(root\)/, 'home and game root must apply safe system-bar insets');
assert.match(main, /private void applySystemBarInsets\(View target\)/, 'system-bar inset handler must exist');
assert.match(main, /Build\.VERSION\.SDK_INT < 35/, 'inset padding must be limited to Android 15+ edge-to-edge devices');
assert.match(main, /WindowInsets\.Type\.systemBars\(\)\s*\|\s*WindowInsets\.Type\.displayCutout\(\)/, 'status/navigation bars and display cutout must be protected');

assert.match(main, /installImageGenDocumentStartScript\(origin\);/, 'the managed ImageGen bridge must be installed before the game scripts');
assert.match(main, /@JavascriptInterface public void imageGenProxyFetch/, 'ImageGen requests must pass through the native loopback proxy');
assert.doesNotMatch(main, /@JavascriptInterface public String listImageProviders/, 'image provider bridge must not be exposed');
assert.doesNotMatch(application, /DiagnosticsUploader\.uploadPending/, 'automatic remote diagnostics upload must be disabled');

assert.match(manifest, /ImageGenService/, 'the user-started image-generation service must be registered');
assert.match(manifest, /NovelAiSettingsActivity/, 'the NAI credential screen must be registered');
assert.doesNotMatch(manifest, /ImageProviderActivity/, 'the legacy generic Cloudflare provider editor must stay out of the official UI');
assert.match(main, /startGoogleDriveAuth\(String nonce\)/, 'native Drive authorization bridge must exist');
assert.match(main, /takePendingStateForNonce\(String nonce\)/, 'nonce-scoped one-time result handoff must exist');
assert.match(main, /clearGoogleAccessToken\(String token\)/, 'invalid native Drive tokens must be clearable');
assert.match(main, /startImageServerAfterNaiSettings=true;\s*openNovelAiSettings\(\);\s*return;/,
  'starting generation without a token must open NAI settings and remember the requested start');
const naiResultStart = main.indexOf('if(req==REQUEST_NAI_SETTINGS)');
const naiResultEnd = main.indexOf('if(req==REQUEST_GOOGLE_AUTH)', naiResultStart);
assert(naiResultStart >= 0 && naiResultEnd > naiResultStart, 'NAI settings result handler must exist');
assert.match(main.slice(naiResultStart, naiResultEnd), /requestedStart\|\|IMAGE_MODE_GENERATION\.equals\(currentImageMode\(\)\)/,
  'saving a token from a pending server-start flow must resume server startup');
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
assert.match(main.slice(webStart, webEnd), /installImageGenDocumentStartScript\(origin\)/,
  'the Image Studio adapter must be installed on the game origin');

console.log('Official v1 scope checks passed');


// Activity result request codes must be globally unique. A collision caused the
// WebView ZIP/file picker result (4103) to be consumed by Google auth first.
const mainRequestCodeNames = ['PICK_TREE', 'PICK_MOD_ZIP', 'REQUEST_GOOGLE_AUTH', 'REQUEST_NAI_SETTINGS'];
const mainRequestCodes = mainRequestCodeNames.map((name) => {
  const match = main.match(new RegExp(`\\b${name}\\s*=\\s*(\\d+)`));
  assert(match, `MainActivity request code ${name} must exist`);
  return Number(match[1]);
});
const chooserRequestMatch = webFileChooser.match(/static final int REQUEST_CODE\\s*=\\s*(\\d+)/);
assert(chooserRequestMatch, 'WebView file chooser request code must exist');
const allRequestCodes = [...mainRequestCodes, Number(chooserRequestMatch[1])];
assert.equal(new Set(allRequestCodes).size, allRequestCodes.length,
  'MainActivity and WebView file chooser ActivityResult request codes must be unique');
