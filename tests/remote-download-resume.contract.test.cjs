const assert = require("node:assert/strict");
const fs = require("node:fs");

const activity = fs.readFileSync(
  "app/src/main/java/com/wayward/launcher/RemotePcFileManagerActivity.java",
  "utf8",
);
const policy = fs.readFileSync(
  "app/src/main/java/com/wayward/launcher/RemoteDownloadResumePolicy.java",
  "utf8",
);

for (const marker of [
  "KEY_PENDING_DOWNLOAD",
  "takePersistableUriPermission",
  "FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
  "savePendingDownload(metadata)",
  'Collections.singletonMap("Range", "bytes=" + offset + "-")',
  "response.getContentRange()",
  "RemoteDownloadResumePolicy.validate(",
  "sha256File(temporary)",
  "copyVerifiedDownloadToDestination",
  "임시 파일은 보존됩니다",
  "discardPendingDownload",
]) {
  assert.ok(activity.includes(marker), "missing resumable-download path: " + marker);
}
assert.ok(policy.includes("Content-Range"));
assert.ok(policy.includes("statusCode != 206"));
assert.ok(policy.includes("responseSha256"));
assert.ok(!activity.includes("getContentResolver().delete(destination"),
  "interrupted downloads must not delete the saved resume state");
console.log("Remote PC download resume contract passed.");
