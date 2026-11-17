#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = process.cwd();
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const main = read('app/src/main/java/com/wayward/launcher/MainActivity.java');
const policy = read('app/src/main/java/com/wayward/launcher/WmlModulePolicy.java');
const pipeline = read('app/src/main/java/com/wayward/launcher/ImagePipelineDiagnostics.java');
const cache = read('app/src/main/java/com/wayward/launcher/SafImageCache.java');
const resolver = read('app/src/main/java/com/wayward/launcher/SafImageDocumentResolver.java');
const app = read('app/src/main/java/com/wayward/launcher/LauncherApplication.java');

assert.match(main, /WmlModulePolicy\.isBlockedModId\(id\)/,
  'game-folder and ZIP module enumeration must filter Image Studio');
assert.match(main, /WmlModulePolicy\.isBlockedResourcePath\(p\)/,
  'direct requests for blocked module files must also be denied');
assert.match(policy, /IMAGE_STUDIO_ID\s*=\s*"imagestudio"/,
  'the policy must target the exact generator module');
assert.doesNotMatch(main, /ImageGen|ImageProvider|ComfyEndpoint|NovelAI|FLUX|__wayward_generated__/,
  'the launcher must not contain image generation server/provider integration');
assert.doesNotMatch(app, /imagegen_service/,
  'application startup must not retain an ImageGen service process marker');

const localImageStart = main.indexOf('private WebResourceResponse serveLocalImage(String relative)');
const gameServeStart = main.indexOf('WebResourceResponse serve(String raw)', localImageStart);
assert(localImageStart >= 0 && gameServeStart > localImageStart, 'local image serving method must exist');
const localImageServe = main.slice(localImageStart, gameServeStart);
assert.match(localImageServe, /tree\.openImage\(filePath\)/,
  'images from the selected game folder must still be served');
assert.match(localImageServe, /zipMods\.open\(filePath\)/,
  'images supplied by ZIP image packs must still be served');

const serveEnd = main.indexOf('static InputStream mobileViewport', gameServeStart);
assert(serveEnd > gameServeStart, 'game resource serving method boundary must exist');
const gameServe = main.slice(gameServeStart, serveEnd);
const policyAt = gameServe.indexOf('WmlModulePolicy.isBlockedResourcePath(p)');
const treeReadAt = gameServe.indexOf('tree.openImage(p)', policyAt);
assert(policyAt >= 0 && treeReadAt > policyAt,
  'blocked Image Studio files must be rejected before reading from SAF or ZIP');

assert.match(main, /isLocalOnlyCharacterImagePath\(path\)\)return serveImageRequest/,
  'new character images must remain local-only');
assert.match(main, /serveExternalImagePath\(path\.substring\(8\)\)/,
  'the existing non-character CDN fallback must remain unchanged');
assert.match(main, /SafImageDocumentResolver\.buildDocumentId/,
  'the SAF direct document-ID fast path must remain available');
assert.match(resolver, /com\.android\.externalstorage\.documents/,
  'the direct SAF path must remain restricted to Android external storage');
assert.match(cache, /class SafImageCache/,
  'the local image cache must remain in the launcher');

for (const file of [
  'ComfyEndpoint.java', 'ImageGenBackendInstaller.java', 'ImageGenLocalProviderServer.java',
  'ImageGenPanelActivity.java', 'ImageGenProxy.java', 'ImageGenService.java',
  'ImageGenStatusStore.java', 'ImageProviderActivity.java', 'ImageProviderBridge.java',
  'ImageProviderClient.java', 'ImageProviderStore.java', 'ImageDiagnosticsActivity.java'
]) {
  const full = path.join(root, 'app/src/main/java/com/wayward/launcher', file);
  assert.equal(fs.existsSync(full), false, file + ' must not be packaged');
}
assert.doesNotMatch(pipeline, /game_generate_|native_generation_|provider_http_|cloudflare-worker|openai-compatible/,
  'image generation diagnostics must not remain in the local image loader');
assert.match(pipeline, /"resolverMode"/,
  'the SAF resolver mode diagnostic must be retained');

console.log('Local image-pack preservation and Image Studio isolation checks passed');
