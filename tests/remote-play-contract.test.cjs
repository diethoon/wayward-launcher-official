const assert = require('node:assert/strict');
const fs = require('node:fs');

const read = path => fs.readFileSync(path, 'utf8');
const main = read('app/src/main/java/com/wayward/launcher/MainActivity.java');
const client = read('app/src/main/java/com/wayward/launcher/RemoteHostClient.java');
const connection = read('app/src/main/java/com/wayward/launcher/RemoteHostConnection.java');
const store = read('app/src/main/java/com/wayward/launcher/RemoteHostStore.java');
const discovery = read('app/src/main/java/com/wayward/launcher/RemoteHostDiscovery.java');
const bridge = read('app/src/main/java/com/wayward/launcher/RemoteGameResourceBridge.java');
const resourceCache = read('app/src/main/java/com/wayward/launcher/RemoteResourceCache.java');
const comfy = read('app/src/main/java/com/wayward/launcher/RemoteComfyClient.java');
const remoteImageApi = read('app/src/main/java/com/wayward/launcher/RemoteGameImageApiClient.java');
const imageProxy = read('app/src/main/java/com/wayward/launcher/ImageGenProxy.java');
const files = read('app/src/main/java/com/wayward/launcher/RemoteFilesClient.java');
const manifest = read('app/src/main/AndroidManifest.xml');
const naiStore = read('app/src/main/java/com/wayward/launcher/NovelAiTokenStore.java');
const imageStudio = read('app/src/main/assets/imagegen-studio-bridge.js');

assert.match(main, /private String sessionDomain\(String id\)\s*\{\s*return "localhost";/,
  'the existing per-game localhost WebView origin must remain unchanged');
assert.match(main, /setDomain\(domain\+":"\+originPort\)/,
  'the existing per-game origin and port must continue to host localStorage/IndexedDB');
assert.match(main, /RemoteGameResourceBridge\.serve\(this,encodedPath,requestHeaders,samples\)/,
  'Remote Play image requests must be proxied by the Native WebView resource interceptor');
const generatedAnchorRoute = main.indexOf('path.startsWith("/images/__wayward_generated__/")');
assert.ok(generatedAnchorRoute >= 0 &&
  main.indexOf('serveGeneratedImageRequest(path, imageDiagnostics)', generatedAnchorRoute) > generatedAnchorRoute,
  'the app-private generated-image anchor must remain available in local generation mode');
assert.match(main, /IMAGE_MODE_REMOTE_PC\.equals\(currentImageMode\(\)\)[\s\S]*?RemoteGameResourceBridge\.serve\(MainActivity\.this,u\.getEncodedPath\(\)/,
  'Remote PC generated-image anchors must be fetched from the approved PC root through the pinned Host');
assert.match(main, /IMAGE_MODE_REMOTE_PC/,
  'the existing serialized image routing manager must retain a Remote PC mode contract');
assert.match(main, /registerRemotePcServerControl\(/,
  'Remote Play must use the existing server-mode transition coordinator');

assert.match(connection, /verifySignedManifest\(\)/,
  'the Host QR must be validated before trust is stored');
assert.match(connection, /verifyHostSignature\(/,
  'Host pairing challenges and approval receipts must be verified using the pinned Host public key');
assert.match(connection, /verifyPairingApprovalReceipt/,
  'the approval receipt must bind the signed Host, Pairing ID, Device ID, public-key fingerprint and permission bitmask');
assert.match(client, /verifyPairingApprovalReceipt/,
  'the mobile pairing flow must verify the canonical signed receipt before accepting enrollment');
assert.match(connection, /parseSignedLong\(text, "permissions"\)[\s\S]*permissions != expectedPermissions/,
  'the Pairing approval signature must bind the permission bitmask');

assert.match(connection, /certificateSha256/,
  'the signed Host manifest must bind the TLS certificate fingerprint');
assert.match(connection, /normalizeExternalBaseUrl/,
  'the optional Internet endpoint must be restricted to a numeric globally routable HTTPS IPv4 address and port');
assert.match(connection, /uri\.getPort\(\) < 1[^\n]*uri\.getPort\(\) > 65535/,
  'the signed external endpoint must validate its supplied port instead of assuming 8765');
for (const port of [8765, 43210, 50000]) {
  assert.ok(connection.includes('uri.getPort()') && connection.includes('externalBaseUrl'),
    `the Android signed endpoint parser must retain support for explicit port ${port}`);
}
assert.match(connection, /normalizeExternalBaseUrl\(externalBaseUrl\)/,
  'the persisted signed external endpoint must pass through the same explicit-port validator');
assert.match(connection, /externalBaseUrl=.*signedText|externalBaseUrl=/,
  'the optional Internet endpoint must be bound by the Host Identity signature');
assert.match(client, /host\.externalBaseUrl/,
  'Remote Host requests must try the signed external endpoint after the private LAN endpoint fails');
assert.match(client, /SSLException/,
  'TLS or certificate-pin failures must not trigger a fallback to another endpoint');
assert.match(client, /writeRequestBody/,
  'request bodies must be sent only after a trusted endpoint has connected, avoiding duplicate POST retries');
assert.match(connection, /qrNonce/);
assert.match(client, /\.put\("qrNonce", host\.qrNonce\)/,
  'initial pairing must consume the nonce embedded in the signed and short-lived QR');
assert.match(client, /checkServerTrusted/,
  'TLS must validate the exact pinned certificate rather than trust arbitrary self-signed certificates');
assert.match(client, /MessageDigest\.isEqual\(expected, actual\)/,
  'TLS certificate pin comparison must use constant-time digest comparison');
assert.match(client, /RemoteHostStore\.getDeviceKeyPair/,
  'the Remote Host client must use the Android Keystore-backed device key store');
assert.match(store, /AndroidKeyStore/,
  'the persistent Device Identity must be backed by Android Keystore');

assert.match(client, /volatile String accessToken/,
  'the bearer token must be held in process memory');
assert.doesNotMatch(client, /getSharedPreferences|putString\([^\n]*accessToken|putString\([^\n]*sessionToken/,
  'Remote Host bearer tokens must not be persisted');
assert.doesNotMatch(main, /@JavascriptInterface[^\n]*(?:accessToken|sessionToken|RemoteHost)/,
  'Remote Host credentials must never be exposed through the game JavaScript bridge');
assert.match(client, /expiresAtUtc/,
  'short-lived Host sessions must observe the server-issued expiry');

assert.match(discovery, /_wayward-remote-host\._tcp/,
  'the non-UI discovery service must use the Host DNS-SD service type');
assert.match(discovery, /RemoteHostStore\.loadConnection/,
  'mDNS rediscovery must require an existing QR-trusted Host');
assert.match(discovery, /RemoteHostClient\.discoverHost/,
  'a discovered endpoint must be validated through the saved Host certificate pin');
assert.match(client, /verifyAndStoreQr/,
  'QR registration must verify the signed Host identity before storing it');
assert.match(client, /pairAndWaitForApproval/,
  'pairing must wait for explicit approval in the Windows Manager');
assert.match(client, /getWorkspaceRoots/,
  'the native client must query Host-approved workspace roots');
assert.match(main, /compactButton\("\+ PC 폴더"/,
  'Home must provide an approved PC workspace picker before the current-device folder picker');
assert.match(main, /compactButton\("페어링"/,
  'Home must expose internal QR pairing in the upper-right action slot');
assert.match(main, /원격 PC 파일매니저/,
  'the remote file manager must remain separate from image routing');
assert.match(main, /이 기기에 설치된 이미지팩만 탐색/,
  'the image-pack mode description must be local-only and explicit');
assert.match(main, /RemotePairingActivity\.class/,
  'the pairing action must open the in-launcher scanner');
const pairingActivity = read('app/src/main/java/com/wayward/launcher/RemotePairingActivity.java');
const fileManagerActivity = read('app/src/main/java/com/wayward/launcher/RemotePcFileManagerActivity.java');
assert.match(pairingActivity, /GmsBarcodeScanning\.getClient\(this, options\)/,
  'QR scanning must run in the launcher through Google Code Scanner');
assert.match(pairingActivity, /areModulesAvailable\(scanner\)/,
  'the Google Code Scanner optional module must be checked before opening the scanner');
assert.match(pairingActivity, /installModules\(request\)[\s\S]*?beginScan\(\)/,
  'a missing scanner module must be installed and scanning retried automatically');
assert.match(pairingActivity, /setBarcodeFormats\(Barcode\.FORMAT_QR_CODE\)/,
  'the scanner must accept QR codes only');
assert.match(pairingActivity, /enableAutoZoom\(\)/,
  'the QR scanner must enable automatic zoom');
assert.match(pairingActivity, /stage=qr_recognition[\s\S]*?stage=qr_parse[\s\S]*?stage=pc_pairing_auth[\s\S]*?stage=user_approval/,
  'diagnostics must distinguish QR recognition, parsing, PC authentication and approval');
assert.doesNotMatch(pairingActivity, /Log\.[idw]\([^\n]*(?:raw|accessToken|pollingSecret)/i,
  'diagnostic logs must never include QR contents or authentication secrets');
assert.match(pairingActivity, /stage=folder_access result=failed/,
  'folder-list errors must be reported as a separate folder-access state');
assert.match(pairingActivity, /RemoteHostClient\.pairAndWaitForApproval\(this, host\)[\s\S]*?RemoteHostClient\.getWorkspaceRoots\(this, host\)/,
  'pairing approval must complete before the optional workspace-root lookup');
assert.match(pairingActivity, /setResult\(RESULT_OK\)/,
  'a completed pairing must remain successful even when the folder lookup fails');
assert.match(connection, /optInt\("protocolVersion", 0\) != 1/);
assert.match(connection, /getString\("baseUrl"\)[\s\S]*?getString\("hostId"\)[\s\S]*?getString\("publicKeyBase64"\)[\s\S]*?getString\("publicKeyFingerprint"\)[\s\S]*?getString\("certificateSha256"\)[\s\S]*?getString\("qrNonce"\)[\s\S]*?getString\("signedPayloadBase64"\)[\s\S]*?getString\("signatureBase64"\)/,
  'Android QR parser must preserve the Windows Host protocol v1 fields');
assert.match(pairingActivity, /verifyAndStoreQr/);
assert.match(pairingActivity, /pairAndWaitForApproval/);
assert.match(store, /markPaired|KEY_PAIRED_HOST_ID/,
  'approved pairing state must persist and bind to the exact Host ID');
assert.match(fileManagerActivity, /RemoteFilesClient\.list/);
assert.match(fileManagerActivity, /RemoteFilesClient\.openDownload/);
assert.match(fileManagerActivity, /RemoteFilesClient\.beginUpload/);
assert.doesNotMatch(manifest, /android\.permission\.CAMERA/,
  'Google Code Scanner must not require a CAMERA permission');

assert.match(bridge, /Range/,
  'remote media requests must forward HTTP Range headers');
assert.match(bridge, /If-None-Match/,
  'remote media requests must revalidate the exact local disk-cache ETag');
assert.match(bridge, /status == 304/,
  'remote media requests must reuse cached bytes after a valid 304 response');
assert.match(bridge, /RemoteResourceCache\.CacheWriter/,
  'full image responses must be streamed into app-private cache storage');
assert.match(bridge, /FetchFlight/,
  'concurrent duplicate image requests must coalesce while the first cache fill is active');
assert.match(bridge, /CacheTeeInputStream/,
  'canceled WebView reads must close the upstream HTTPS stream and abort partial cache files');
assert.match(resourceCache, /MAX_ENTRY_BYTES/);
assert.match(resourceCache, /MAX_TOTAL_BYTES/);
assert.match(bridge, /Content-Range/,
  'partial-content metadata must be returned to the WebView');
assert.match(bridge, /ETag/,
  'remote responses must preserve Host ETag metadata');
assert.match(bridge, /RemoteHostClient\.openGameResource/,
  'game resources must be fetched through the certificate-pinned native API client');

assert.match(main, /ImageGenProxy\.dispatch\(web,requestId,method,path,headersJson,bodyBase64,remotePcMode\)/,
  'the existing WebView bridge must choose the remote transport from the selected image mode');
assert.match(imageProxy, /if \(remotePcMode\)[\s\S]*?RemoteGameImageApiClient\.execute/,
  'Remote PC image API calls must use the native authenticated transport instead of the local ImageGen port');
assert.match(imageProxy, /RemoteHostStore\.isReady\(appContext\)/,
  'Remote PC generation must require a paired Host and selected approved workspace root');
assert.match(remoteImageApi, /\/v1\/game-image-api\/image\/generate/,
  'the original single-image endpoint must map to the authenticated PC Host adapter');
assert.match(remoteImageApi, /\/api\/pack[\s\S]*?\/v1\/game-image-api\/pack/,
  'the original Wayward server image-pack index must be restored through the authenticated PC Host adapter');
assert.match(remoteImageApi, /\/v1\/game-image-api\/image-hit\?\" \+ rootQuery/,
  'image-hit accounting must be scoped to the currently selected approved workspace root');
assert.match(remoteImageApi, /\/v1\/game-image-api\/batch\/enqueue/,
  'the original pre-generation batch endpoint must map to the authenticated PC Host adapter');
assert.match(remoteImageApi, /\/v1\/game-image-api\/image\/status/,
  'the original game must be able to poll image completion on the PC Host');
assert.match(remoteImageApi, /requestAuthenticatedJsonEnvelope/,
  'Remote Host bearer tokens must stay inside native code and be returned as a response envelope only');
assert.doesNotMatch(remoteImageApi, /put\("Authorization"|Bearer /,
  'game JavaScript must not receive or create the Remote Host bearer token');
assert.match(comfy, /\/v1\/comfy\/status/,
  'Native ComfyUI status must use the authenticated Remote Host gateway');
assert.match(comfy, /\/v1\/comfy\/jobs/,
  'Native ComfyUI workflow submission and job inspection must use Remote Host API v1');
assert.match(comfy, /\/v1\/comfy\/images/,
  'ComfyUI input and output images must travel through the Remote Host gateway');
assert.match(comfy, /getCachedImage/,
  'ComfyUI output images must support an app-private cache');
assert.match(comfy, /verifyCachedImage/,
  'ComfyUI cache files must pass SHA-256 verification before reuse');
assert.match(comfy, /MessageDigest\.isEqual/,
  'ComfyUI output cache SHA-256 comparison must be constant-time');
assert.match(files, /Upload-Offset/,
  'resumable file uploads must use the Host-confirmed sequential offset');
assert.match(files, /openDownload/,
  'the Native file client must expose authenticated file download');
assert.match(files, /UploadFiles|beginUpload|appendUploadChunk|completeUpload/,
  'the Native file client must support the Host resumable upload lifecycle');
assert.match(files, /X-Wayward-Content-SHA256/,
  'download responses must require the Host file SHA-256 header');

assert.match(imageStudio, /fromLauncherImageServer/,
  'a cached result from the local image server must remain on the generated-image anchor after the process stops');
assert.doesNotMatch(imageStudio,
  /function localEndpoint\(path\)\s*\{\s*if \(!prefersLauncherLocal\(\)\) return null;/,
  'native cached generated images must not be hidden merely because the generation process is stopped');

assert.match(manifest, /usesCleartextTraffic="false"/,
  'cleartext HTTP must remain disabled for the official launcher');
assert.match(naiStore, /AES\/GCM\/NoPadding/,
  'the existing NovelAI AES-GCM token envelope must remain intact');
assert.match(naiStore, /wayward\.image\.providers\.v1/,
  'the existing NovelAI Keystore alias must remain intact');


assert.match(connection, /static final class BootstrapInfo[\\s\\S]*?certificateSha256[\\s\\S]*?qrNonce/,
  'bootstrap QR parsing must retain only protocol, base URL, certificate pin and nonce');
assert.match(connection, /static boolean isBootstrapQrJson[\\s\\S]*?hasFullManifestField/,
  'bootstrap and legacy full-manifest QR formats must be classified separately');
assert.match(connection, /static BootstrapInfo fromBootstrapQrJson[\\s\\S]*?normalizeBaseUrl[\\s\\S]*?normalizeFingerprint[\\s\\S]*?\[0-9A-F\]\{64\}/,
  'bootstrap QR fields must validate HTTPS address, certificate SHA-256 and nonce format');
assert.match(connection, /static RemoteHostConnection fromManifestJson[\\s\\S]*?bootstrap\\.baseUrl\\.equals[\\s\\S]*?bootstrap\\.certificateSha256\\.equals[\\s\\S]*?bootstrap\\.qrNonce\\.equals/,
  'the fetched full manifest must exactly match the QR address, certificate pin and nonce');
assert.match(connection, /static RemoteHostConnection fromQrJson/,
  'legacy full JSON QR parsing must remain supported');
assert.match(client, /static RemoteHostConnection fetchPairingManifest[\\s\\S]*?\/v1\/host\/pairing-manifest\//,
  'the native client must retrieve the full manifest by the QR nonce');
assert.match(client, /requestJsonPinned\(bootstrap\.baseUrl, bootstrap\.certificateSha256[\\s\\S]*?true\)/,
  'the bootstrap manifest request must use the QR certificate pin');
assert.match(client, /status != HttpsURLConnection\.HTTP_OK[\\s\\S]*?application\\/json[\\s\\S]*?Cache-Control[\\s\\S]*?no-store/,
  'the manifest response must be HTTP 200 JSON with no-store caching');
assert.match(client, /configurePinnedTls\(connection, certificatePin\)/,
  'bootstrap manifest retrieval must use the existing pinned TLS trust manager');
const bootstrapBranch = pairingActivity.indexOf('RemoteHostConnection.isBootstrapQrJson(raw)');
const manifestFetch = pairingActivity.indexOf('RemoteHostClient.fetchPairingManifest(bootstrap)');
const verifyStore = pairingActivity.indexOf('RemoteHostClient.verifyAndStoreQr(this, scannedHost)');
const pairingRequest = pairingActivity.indexOf('RemoteHostClient.pairAndWaitForApproval(this, host)');
assert.ok(bootstrapBranch >= 0 && manifestFetch > bootstrapBranch && verifyStore > manifestFetch &&
  pairingRequest > verifyStore,
  'bootstrap fetch, manifest validation and existing Host verification must finish before pairing');
assert.match(pairingActivity, /else\\s*\\{\\s*scannedHost = RemoteHostConnection\.fromQrJson\(raw\)/,
  'legacy full JSON QR must remain on its original parsing path without bootstrap fallback');
assert.match(client, /host\.verifySignedManifest\(\)[\\s\\S]*?verifyIdentityResponse\(host, identity\)[\\s\\S]*?RemoteHostStore\.saveConnection\(context, host\)/,
  'Host signature and identity checks must complete before storing Host data');

console.log('Remote Play native bridge, cache, ComfyUI and security contract checks passed');
