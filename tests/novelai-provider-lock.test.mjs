import assert from "node:assert/strict";
import fs from "node:fs";

const read = (path) => fs.readFileSync(path, "utf8");
const main = read("app/src/main/java/com/wayward/launcher/MainActivity.java");
const service = read("app/src/main/java/com/wayward/launcher/ImageGenService.java");
const proxy = read("app/src/main/java/com/wayward/launcher/ImageGenProxy.java");
const tokenStore = read("app/src/main/java/com/wayward/launcher/NovelAiTokenStore.java");
const settingsUi = read("app/src/main/java/com/wayward/launcher/NovelAiSettingsActivity.java");
const embed = read("scripts/embed_imagegen_backend.py");

assert.match(main, /final String providerMode = "novelai"/,
  "every Image Studio game session must select NovelAI, never a stored external provider");
assert.doesNotMatch(main, /ImageProviderStore|ImageProviderClient|ImageGenLocalProviderServer|launcherProvider|selectedMode = "launcher"/,
  "MainActivity must not retain the retired external provider route");
assert.match(main, /startManagedImageGenServer\("novelai",\s*""\)/,
  "the home generation control must start the dedicated NovelAI backend");
assert.doesNotMatch(main, /startManagedImageGenServer\("launcher"/,
  "the launcher must not expose the retired provider mode");
assert.match(service, /WAYWARD_FORCE_NOVELAI",\s*"1"/,
  "the managed NovelAI engine must receive an explicit provider-lock flag");
assert.match(service, /NovelAiTokenStore\.readToken\(this\)/,
  "the backend token must be loaded from the persistent token store");
assert.match(service, /builder\.environment\(\)\.put\("NAI_TOKEN",\s*token\)/,
  "the backend must receive the token from the launcher");
assert.doesNotMatch(service, /ImageProviderStore|ImageProviderClient|ImageGenLocalProviderServer|launcherProvider|prepareLauncherProviderToken/,
  "the service must not retain the old local external-provider adapter");
assert.match(proxy, /settings\.remove\("naiToken"\)/,
  "game settings must not replace the launcher-owned NovelAI token");
assert.match(proxy, /settings\.remove\("naiTokenClear"\)/,
  "game settings must not clear the launcher-owned NovelAI token");
assert.match(proxy, /settings\.put\("imageProvider",\s*"nai"\)/,
  "native proxy must pin generation settings to the NovelAI provider");

assert.match(tokenStore, /KEY_ALIAS = "wayward\.image\.providers\.v1"/,
  "the previous Keystore alias must be preserved for installed users");
assert.match(tokenStore, /new File\(context\.getFilesDir\(\), "image-providers\.enc"\)/,
  "the encrypted credential file path must be preserved");
assert.match(tokenStore, /AES\/GCM\/NoPadding/,
  "the saved token must remain encrypted");
assert.match(tokenStore, /root\.optString\("novelAiToken", ""\)/,
  "the prior encrypted schema's token must be recovered");
assert.match(tokenStore, /root\.optInt\("schemaVersion", 0\) < 2/,
  "legacy provider data must be migrated");
assert.match(tokenStore, /root\.put\("schemaVersion", 2\)/,
  "new credentials must be written as token-only schema v2");
assert.doesNotMatch(tokenStore, /cloudflare-worker|workers\.dev|launcherProvider|openai-compatible/,
  "the token store must no longer implement external providers");
assert.match(settingsUi, /NovelAiTokenStore\.saveToken\(this, ""\)/,
  "token deletion must occur only through the explicit clear action");
assert.match(settingsUi, /직접 삭제할 때까지/,
  "the settings UI must clearly state the persistence policy");

for (const retired of [
  "app/src/main/java/com/wayward/launcher/ImageGenLocalProviderServer.java",
  "app/src/main/java/com/wayward/launcher/ImageProviderActivity.java",
  "app/src/main/java/com/wayward/launcher/ImageProviderBridge.java",
  "app/src/main/java/com/wayward/launcher/ImageProviderClient.java",
  "app/src/main/java/com/wayward/launcher/ImageProviderStore.java"
]) {
  assert.equal(fs.existsSync(retired), false, "retired external provider source must be removed: " + retired);
}
assert.doesNotMatch(embed, /launcherProvider|imageProvider === 'launcher'|launcher-provider-v1/,
  "the embedded backend builder must not re-introduce the retired adapter");
assert.match(embed, /process\.env\.WAYWARD_FORCE_NOVELAI === '1' \? 'nai'/,
  "embedded backend startup and live EasyGen settings must remain pinned to NovelAI");
assert.match(embed, /persistedSettings = \{ \.\.\.settings, naiToken: '' \}/,
  "the embedded backend must not write tokens into its JSON settings file");

console.log("NovelAI-only routing contract passed.");
