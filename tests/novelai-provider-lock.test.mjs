import assert from "node:assert/strict";
import fs from "node:fs";

const main = fs.readFileSync("app/src/main/java/com/wayward/launcher/MainActivity.java", "utf8");
const service = fs.readFileSync("app/src/main/java/com/wayward/launcher/ImageGenService.java", "utf8");
const proxy = fs.readFileSync("app/src/main/java/com/wayward/launcher/ImageGenProxy.java", "utf8");
const providerStore = fs.readFileSync("app/src/main/java/com/wayward/launcher/ImageProviderStore.java", "utf8");

assert.match(main, /startManagedImageGenServer\("novelai",\s*""\)/,
  "the image generation mode must start the dedicated NovelAI backend");
assert.match(service, /WAYWARD_FORCE_NOVELAI",\s*"1"/,
  "the managed NovelAI engine must receive an explicit provider-lock flag");
assert.match(service, /builder\.environment\(\)\.put\("NAI_TOKEN",\s*token\)/,
  "the backend must receive the token read by the launcher");
assert.match(proxy, /settings\.remove\("naiToken"\)/,
  "game settings must not replace the launcher-owned NovelAI token");
assert.match(proxy, /settings\.remove\("naiTokenClear"\)/,
  "game settings must not clear the launcher-owned NovelAI token");
assert.match(proxy, /settings\.put\("imageProvider",\s*"nai"\)/,
  "native proxy must pin generation settings to the NovelAI provider");
assert.doesNotMatch(proxy, /ImageProviderStore\.saveNovelAiToken\(appContext/,
  "WebView generation settings must never write the AndroidKeyStore token");
assert.match(providerStore, /AndroidKeyStore/,
  "the credential registry must remain protected by Android Keystore");

const configPath = "app/src/main/assets/imagegen-backend/wayward-imagegen/src/config.ts";
const settingsPath = "app/src/main/assets/imagegen-backend/wayward-imagegen/src/routes/generationSettings.ts";
assert(fs.existsSync(configPath), "the pinned backend config must be embedded before this test runs");
assert(fs.existsSync(settingsPath), "the pinned backend settings route must be embedded before this test runs");
const config = fs.readFileSync(configPath, "utf8");
const settings = fs.readFileSync(settingsPath, "utf8");

assert.match(config, /process\.env\.WAYWARD_FORCE_NOVELAI === '1' \? 'nai'/,
  "backend startup must force NovelAI even when its saved config selected another engine");
assert.match(settings, /process\.env\.WAYWARD_FORCE_NOVELAI === '1' \? 'nai'/,
  "live EasyGen setting updates must not switch away from NovelAI");
assert.match(settings, /const persistedSettings = \{ \.\.\.settings, naiToken: '' \}/,
  "the embedded backend must not persist the NovelAI token in its JSON settings file");

console.log("Managed NovelAI provider-lock test passed.");
