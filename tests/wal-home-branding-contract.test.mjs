import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const main = readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java", import.meta.url), "utf8");
const manifest = readFileSync(new URL("../app/src/main/AndroidManifest.xml", import.meta.url), "utf8");
const gradle = readFileSync(new URL("../app/build.gradle", import.meta.url), "utf8");

assert.match(main, /uiText\("WAL",\s*17/);
assert.match(manifest, /android:label="\$\{appLabel\}"/);
assert.match(gradle, /manifestPlaceholders\s*=\s*\[appLabel:\s*[\'"]WAL Android Launcher[\'"]\]/);
assert.match(main, /uiText\("Wayward Android Launcher",\s*11/);
assert.doesNotMatch(main, /여러 게임을 독립 세션으로 실행/);
assert.doesNotMatch(main, /독립 세션으로 실행되며 브라우저 저장 데이터도 세션별로 분리됩니다/);
assert.match(main, /identity\.setGravity\(android\.view\.Gravity\.CENTER_VERTICAL\)/);
assert.match(main, /name\.setEllipsize\(android\.text\.TextUtils\.TruncateAt\.END\)/);
assert.match(main, /meta\.setEllipsize\(android\.text\.TextUtils\.TruncateAt\.END\)/);
assert.match(gradle, /applicationId\s+'com\.wayward\.launcher\.official'/);
assert.match(gradle, /versionCode\s+109/);
assert.match(gradle, /versionName\s+'1\.0\.9'/);

console.log("WAL home branding and alignment contract: PASS");
