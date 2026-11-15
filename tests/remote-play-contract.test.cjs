const assert = require('node:assert/strict');
const fs = require('node:fs');

const read = path => fs.readFileSync(path, 'utf8');
const main = read('app/src/main/java/com/wayward/launcher/MainActivity.java');
const client = read('app/src/main/java/com/wayward/launcher/RemoteHostClient.java');
const connection = read('app/src/main/java/com/wayward/launcher/RemoteHostConnection.java');
const store = read('app/src/main/java/com/wayward/launcher/RemoteHostStore.java');
const activity = read('app/src/main/java/com/wayward/launcher/RemoteHostActivity.java');
const bridge = read('app/src/main/java/com/wayward/launcher/RemoteGameResourceBridge.java');
const manifest = read('app/src/main/AndroidManifest.xml');
const naiStore = read('app/src/main/java/com/wayward/launcher/NovelAiTokenStore.java');

assert.match(main, /private String sessionDomain\(String id\)\s*\{\s*return "localhost";/,
  'the existing per-game localhost WebView origin must remain unchanged');
assert.match(main, /setDomain\(domain\+":"\+originPort\)/,
  'the existing per-game origin and port must continue to host localStorage/IndexedDB');
assert.match(main, /RemoteGameResourceBridge\.serve\(this,encodedPath,requestHeaders,samples\)/,
  'Remote Play image requests must be proxied by the Native WebView resource interceptor');
assert.match(main, /IMAGE_MODE_REMOTE_PC/,
  'the existing serialized image routing manager must own the Remote Play mode');
assert.match(main, /registerRemotePcServerControl\(/,
  'Remote Play must use the existing server-mode transition coordinator');

assert.match(connection, /verifySignedManifest\(\)/,
  'the Host QR must be validated before trust is stored');
assert.match(connection, /verifyHostSignature\(/,
  'Host pairing challenges and approval receipts must be verified using the pinned Host public key');
assert.match(connection, /certificateSha256/,
  'the signed Host manifest must bind the TLS certificate fingerprint');
assert.match(client, /checkServerTrusted/,
  'TLS must validate the exact pinned certificate rather than trust arbitrary self-signed certificates');
assert.match(client, /MessageDigest\.isEqual\(expected, actual\)/,
  'TLS certificate pin comparison must use constant-time digest comparison');
assert.match(client, /AndroidKeyStore/,
  'the Remote Host device signing key must live in Android Keystore');
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

assert.match(activity, /_wayward-remote-host\._tcp/,
  'the official app must discover Remote Hosts using the Host DNS-SD service type');
assert.match(activity, /verifyAndStoreQr/,
  'QR registration must verify the signed Host identity before storing it');
assert.match(activity, /pairAndWaitForApproval/,
  'pairing must wait for explicit approval in the Windows Manager');
assert.match(activity, /getWorkspaceRoots/,
  'the app must let the user select one Host-approved file root');

assert.match(bridge, /Range/,
  'remote media requests must forward HTTP Range headers');
assert.match(bridge, /If-None-Match/,
  'remote media requests must forward ETag revalidation headers');
assert.match(bridge, /Content-Range/,
  'partial-content metadata must be returned to the WebView');
assert.match(bridge, /ETag/,
  'remote responses must preserve Host ETag metadata');
assert.match(bridge, /RemoteHostClient\.openGameResource/,
  'game resources must be fetched through the certificate-pinned native API client');

assert.match(manifest, /usesCleartextTraffic="false"/,
  'cleartext HTTP must remain disabled for the official launcher');
assert.match(manifest, /android:name="\.RemoteHostActivity"/,
  'the Remote Host pairing screen must be registered');
assert.match(naiStore, /AES\/GCM\/NoPadding/,
  'the existing NovelAI AES-GCM token envelope must remain intact');
assert.match(naiStore, /wayward\.image\.providers\.v1/,
  'the existing NovelAI Keystore alias must remain intact');

console.log('Remote Play native bridge and security contract checks passed');
