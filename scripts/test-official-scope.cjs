#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');

const main = fs.readFileSync('app/src/main/java/com/wayward/launcher/MainActivity.java', 'utf8');
const application = fs.readFileSync('app/src/main/java/com/wayward/launcher/LauncherApplication.java', 'utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');
const gradle = fs.readFileSync('app/build.gradle', 'utf8');
const naiSettings = fs.readFileSync('app/src/main/java/com/wayward/launcher/NovelAiSettingsActivity.java', 'utf8');
const naiTokenStore = fs.readFileSync('app/src/main/java/com/wayward/launcher/NovelAiTokenStore.java', 'utf8');
const glassStyle = fs.readFileSync('app/src/main/java/com/wayward/launcher/GlassButtonStyle.java', 'utf8');
const webFileChooser = fs.readFileSync('app/src/main/java/com/wayward/launcher/WebFileChooser.java', 'utf8');

const homeStart = main.indexOf('private void showHome(){');
const homeEnd = main.indexOf('private void showLauncherInfoDialog()', homeStart);
assert(homeStart >= 0 && homeEnd > homeStart, 'home screen method must exist');
const home = main.slice(homeStart, homeEnd);

assert.match(home, /\+ PC 폴더/, 'home must expose an approved PC workspace picker');
assert.match(home, /\+ 현재 기기 폴더/, 'home must retain current-device folder registration');
assert.match(home, /페어링/, 'home must expose internal QR pairing instead of the old info action');
assert.match(home, /원격 PC 파일매니저/, 'home must keep remote file management below image routing');
assert.match(home, /내 게임/, 'home must expose the registered game list');
assert.match(home, /여러 게임을 독립 세션으로 실행/, 'home must describe independent sessions');
assert.match(home, /이미지 생성 서버 실행/, 'home must expose the explicit local image-generation server switch');
assert.match(main, /setContentDescription\("NovelAI 토큰 설정"\)/, 'the settings gear must be accessible');
assert.match(main, /⚙/, 'the settings affordance must render as a gear icon');
assert.match(main, /GlassButtonStyle\.bind\(this,b,false,12f\)/,
  'standard launcher buttons must use the common button style');
assert.match(main, /GlassButtonStyle\.bind\(this,b,false,10f\)/,
  'compact launcher buttons must use the common button style');
assert.match(naiSettings, /GlassButtonStyle\.bind\(this, button, false, 12f\)/,
  'NovelAI settings actions must use the common button style');
assert.match(naiTokenStore, /AndroidKeyStore/,
  'NovelAI credentials must remain in the Android Keystore');
assert.doesNotMatch(naiTokenStore, /cloudflare-worker|workers\.dev|openai-compatible/,
  'the credential store must not double as an external provider registry');

const glassTileStart = main.indexOf('private View glassModeTile(');
const glassTileEnd = main.indexOf('private void openNovelAiSettings()', glassTileStart);
assert(glassTileStart >= 0 && glassTileEnd > glassTileStart, 'glass mode button builder must exist');
const glassTileUi = main.slice(glassTileStart, glassTileEnd);
assert.match(glassTileUi, /GlassButtonStyle\.bind\(this,tile,selected,17f\)/,
  'image mode buttons must use the neutral glass material');
assert.match(glassStyle, /Color\.argb\(/,
  'glass surfaces must use translucent alpha layers');
assert.match(glassStyle, /GradientDrawable\.Orientation\.TOP_BOTTOM/,
  'glass surfaces must include a soft specular highlight');
assert.doesNotMatch(glassStyle, /Color\.(?:BLUE|CYAN)|#(?:00|33|66)A(?:FF|E6|CC)/i,
  'glass button material must not use a sky-blue gradient');
assert.match(glassTileUi, /settingsAction!=null/,
  'the settings icon must be embedded inside the server glass button');
assert.match(glassTileUi, /gear\.setContentDescription\("NovelAI 토큰 설정"\)/,
  'the embedded gear must remain accessible');
assert.match(glassTileUi, /gear\.setOnClickListener\(view->settingsAction\.run\(\)\)/,
  'the embedded gear must open token settings independently');
assert.equal((glassTileUi.match(/new LinearLayout\.LayoutParams\(0,dp\(82\),1f\)/g) || []).length, 2,
  'local image generation and ComfyUI controls must remain on one equal-width row');
assert.match(glassTileUi, /new LinearLayout\.LayoutParams\(-1,dp\(72\)\)/,
  'the locally installed image-pack switch must remain a full-width separate row');
assert.match(glassTileUi, /generationSelected,this::openNovelAiSettings/,
  'only the server glass button must contain the token-settings gear');
assert.match(glassTileUi, /packsSelected,null,this::switchToImagePacksOnly/,
  'the image-pack button must not create a second gear or nested control');
assert.match(glassTileUi, /ComfyUI 서버 실행/,
  'the second image routing action must launch/use the paired PC ComfyUI service');
assert.match(glassTileUi, /이 기기에 설치된 이미지팩만 탐색/,
  'image-pack routing must explicitly describe local-only image packs');
assert.doesNotMatch(glassTileUi, /glassSettingsButton|dp\(112\)/,
  'the separate gear grid and oversized server button must be removed');
assert.match(main, /imageCard\.setBackground\(uiShape\(Color\.rgb\(239,241,243\)/,
  'glass controls must sit over a neutral matte surface for visible transparency');
assert.doesNotMatch(main, /tile\.setClipToOutline\(true\)/,
  'mode button borders must not be clipped by outline clipping');
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
const naiResultEnd = main.indexOf('String authNonce=pendingGoogleAuthRequestNonces.remove(req);', naiResultStart);
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

// MainActivity owns its fixed request codes. WebView file chooser requests use
// an isolated per-request range so a late result cannot satisfy a later callback.
const mainRequestCodeNames = ['PICK_TREE', 'PICK_MOD_ZIP', 'REQUEST_NAI_SETTINGS'];
const mainRequestCodes = mainRequestCodeNames.map((name) => {
  const match = main.match(new RegExp("\\b" + name + "\\s*=\\s*(\\d+)"));
  assert(match, 'MainActivity request code ' + name + ' must exist');
  return Number(match[1]);
});
const chooserMinMatch = webFileChooser.match(/REQUEST_CODE_MIN\s*=\s*(\d+)/);
const chooserMaxMatch = webFileChooser.match(/REQUEST_CODE_MAX\s*=\s*(\d+)/);
assert(chooserMinMatch && chooserMaxMatch, 'WebView per-request code range must exist');
const chooserMin = Number(chooserMinMatch[1]);
const chooserMax = Number(chooserMaxMatch[1]);
assert.ok(chooserMin >= 1 && chooserMax <= 65534 && chooserMin < chooserMax,
  'WebView chooser request-code range must be valid and bounded');
assert.match(webFileChooser, /NEXT_REQUEST_CODE\.getAndUpdate\(/,
  'each file chooser request must reserve a distinct ActivityResult code');
assert.match(webFileChooser, /static boolean isRequestCode\(int requestCode\)/,
  'MainActivity must route the reserved WebView chooser range');
assert.match(main, /REQUEST_GOOGLE_AUTH_MIN=60000/);
assert.match(main, /REQUEST_GOOGLE_AUTH_MAX=65534/);
assert.ok(main.includes('reserveGoogleAuthRequestCode(nonce)'));
assert.ok(chooserMax < 60000 || chooserMin > 65534,
  'WebView chooser codes must not overlap the dynamic Google auth range');
assert.ok(mainRequestCodes.every(code => code < chooserMin || code > chooserMax),
  'MainActivity fixed picker codes must not overlap the WebView chooser range');
assert.equal(new Set(mainRequestCodes).size, mainRequestCodes.length,
  'MainActivity fixed ActivityResult request codes must remain unique');
console.log('Official v1 scope checks passed');
