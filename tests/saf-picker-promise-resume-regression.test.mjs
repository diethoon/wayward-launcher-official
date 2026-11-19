import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const java = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java", import.meta.url), "utf8");
const activity = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const bridge = readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js", import.meta.url), "utf8");

// Activity Result may be delivered while the Activity/WebView is still paused.
assert.match(java, /private boolean activityResumed;/);
assert.match(java, /private PendingDelivery pendingDelivery;/);
assert.match(java, /void onPause\(\)\s*\{\s*activityResumed = false;/);
assert.match(java, /void onResume\(\)\s*\{\s*activityResumed = true;/);
assert.match(java, /native_js_result_queued_until_activity_resume/);
assert.match(java, /native_js_result_resume_flush/);
assert.match(java, /private void deliver\(String requestId, JSONObject result\)\s*\{\s*if \(!activityResumed\)/);
assert.match(java, /private void deliverNow\(String requestId, JSONObject result\)/);

const resumeBlock = activity.slice(activity.indexOf("@Override protected void onResume()"), activity.indexOf("@Override protected void onPause()"));
assert.ok(resumeBlock.indexOf("web.onResume()") < resumeBlock.indexOf("imagePackDirectoryAccess.onResume()"),
  "WebView must resume before queued SAF Promise delivery is flushed");
const pauseBlock = activity.slice(activity.indexOf("@Override protected void onPause()"), activity.indexOf("@Override protected void onNewIntent"));
assert.ok(pauseBlock.indexOf("imagePackDirectoryAccess.onPause()") < pauseBlock.indexOf("super.onPause()"));
assert.ok(pauseBlock.indexOf("web.onPause()") < pauseBlock.indexOf("super.onPause()"));

// Dispatch count alone is not the consumer acknowledgement: record the JS
// Promise continuation and the post-await continuation separately.
assert.match(bridge, /native_request_promise_continuation_entered/);
assert.match(bridge, /webkitdirectory_picker_await_continued/);
assert.match(bridge, /documentUriPresent: !!\(selection && selection\.documentUri\)/);
assert.match(bridge, /treeUriPresent: !!\(selection && selection\.uri\)/);

// Confirm the actual batched directory request/first-batch boundary is traced.
assert.match(bridge, /directory_entries_native_call/);
assert.match(bridge, /directory_entries_first_batch_received/);
assert.match(bridge, /directory_enumeration_completed/);
assert.match(bridge, /webkitdirectory_filelist_constructed/);
assert.match(bridge, /webkitdirectory_input_event_dispatched/);
assert.match(bridge, /webkitdirectory_change_event_dispatched/);
assert.match(bridge, /faith_scanner_change_trigger_dispatched/);

// User-visible progress must distinguish selection, enumeration, FileList
// construction, completion and failure; it must not include a URI/path.
for (const label of ["폴더 선택 완료", "파일 탐색 중", "파일 목록 구성 중", "게임 전달 완료"]) {
  assert.ok(bridge.includes(label), `missing progress label: ${label}`);
}
assert.match(bridge, /webkitdirectory_upload_failed/);
assert.match(bridge, /dispatchDirectoryCancel\(input\)/);
assert.match(bridge, /The selected folder contains no files/);

// Preserve existing independent hard limits and streaming constraints.
assert.match(bridge, /const MAX_UPLOAD_FILES = 100000/);
assert.match(bridge, /const MAX_UPLOAD_ENTRIES = 200000/);
assert.match(bridge, /const MAX_UPLOAD_DEPTH = 128/);
assert.match(java, /MAX_FILE_BYTES = 32 \* 1024 \* 1024/);
assert.match(java, /MAX_STREAM_CHUNK_BYTES = 64 \* 1024/);
assert.match(java, /MAX_OPEN_STREAMS = 4/);

console.log("SAF Promise resume, continuation, batch, FileList, FAITH trigger and progress contracts passed.");
