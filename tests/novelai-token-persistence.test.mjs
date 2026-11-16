import assert from "node:assert/strict";
import fs from "node:fs";

const store = fs.readFileSync("app/src/main/java/com/wayward/launcher/NovelAiTokenStore.java", "utf8");
const activity = fs.readFileSync("app/src/main/java/com/wayward/launcher/NovelAiSettingsActivity.java", "utf8");
const service = fs.readFileSync("app/src/main/java/com/wayward/launcher/ImageGenService.java", "utf8");
const backup = fs.readFileSync("app/src/main/res/xml/backup_rules.xml", "utf8");
const extraction = fs.readFileSync("app/src/main/res/xml/data_extraction_rules.xml", "utf8");

assert.match(store, /KEY_ALIAS = "wayward\.image\.providers\.v1"/);
assert.match(store, /new File\(context\.getFilesDir\(\), "image-providers\.enc"\)/);
assert.match(store, /AES\/GCM\/NoPadding/);
assert.match(store, /KeyStore\.getInstance\("AndroidKeyStore"\)/);
assert.match(store, /StandardCopyOption\.ATOMIC_MOVE/);
assert.match(store, /root\.optString\("novelAiToken", ""\)/);
assert.match(store, /writeToken\(token\)/, "old provider records must be discarded by rewriting the old token");
assert.match(store, /root\.put\("novelAiToken", token\)/);
assert.match(activity, /NovelAiTokenStore\.saveToken\(this, token\)/);
assert.match(activity, /NovelAiTokenStore\.saveToken\(this, ""\)/,
  "only the explicit clear control should store an empty token");
assert.match(service, /NovelAiTokenStore\.saveToken\(this, token\)/,
  "legacy plaintext token migration must preserve the token in encrypted storage");
assert.doesNotMatch(service, /NovelAiTokenStore\.saveToken\(this, ""\)/,
  "server stop/start must never delete the saved token");
assert.match(backup, /exclude domain="file" path="image-providers\.enc"/);
assert.match(extraction, /exclude domain="file" path="image-providers\.enc"/,
  "device-bound ciphertext must not be restored without its Keystore key");

console.log("NovelAI token persistence/migration contract passed.");
