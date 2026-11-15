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
const files = read('app/src/main/java/com/wayward/launcher/RemoteFilesClient.java');
const manifest = read('app/src/main/AndroidManifest.xml');
const naiStore = read('app/src/main/java/com/wayward/launcher/NovelAiTokenStore.java');

assert.match(main, /private String sessionDomain\(String id\)\s*\{\s*return "localhost";/,
  'the existing per-game localhost WebView origin must remain unchanged');
assert.match(main, /setDomain\(domain\+":"\+originPort\)/,
  'the existing per-game origin and port must continue to host localStorage/IndexedDB');
assert.match(main, /RemoteGameResourceBridge\.serve\(this,encodedPath,requestHeaders,samples\)/,
  'Remote Play image requests must be proxied by the Native WebView resource interceptor');
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
assert.match(connection, /permissions.*expectedPermissions/,
  'the Pairing approval signature must bind the permission bitmask');

assert.match(connection, /certificateSha256/,
  'the signed Host manifest must bind the TLS certificate fingerprint');
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
assert.doesNotMatch(main, /PC Remote Play.*glassModeTile|RemoteHostActivity/,
  'Home-screen UI placement remains deferred until the user provides the UI specification');
assert.doesNotMatch(manifest, /CAMERA|RemoteHostActivity/,
  'the launcher manifest must not add camera permission or an unapproved Remote Host screen');

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
assert.match(files, /DownloadFiles|DownloadFile/,
  'the Native file client must expose authenticated file download');
assert.match(files, /UploadFiles|beginUpload|appendUploadChunk|completeUpload/,
  'the Native file client must support the Host resumable upload lifecycle');
assert.match(files, /X-Wayward-Content-SHA256/,
  'download responses must require the Host file SHA-256 header');

assert.match(manifest, /usesCleartextTraffic="false"/,
  'cleartext HTTP must remain disabled for the official launcher');
assert.match(naiStore, /AES\/GCM\/NoPadding/,
  'the existing NovelAI AES-GCM token envelope must remain intact');
assert.match(naiStore, /wayward\.image\.providers\.v1/,
  'the existing NovelAI Keystore alias must remain intact');

console.log('Remote Play native bridge, cache, ComfyUI and security contract checks passed');
