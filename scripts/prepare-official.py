#!/usr/bin/env python3
"""Prepare the isolated first official launcher build from the pinned development source."""
from pathlib import Path

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

app = Path("app/build.gradle")
text = app.read_text(encoding="utf-8")
text = text.replace("applicationId 'com.wayward.launcher'", "applicationId 'com.wayward.launcher.official'")
text = text.replace("versionCode 19", "versionCode 100")
text = text.replace("versionName '0.3.14'", "versionName '1.0.0'")
text = text.replace("debug { applicationIdSuffix '.imagegen039'; versionNameSuffix '-image-diagnostics' }",
                    "debug { applicationIdSuffix '.debug'; versionNameSuffix '-debug' }")
if "applicationId 'com.wayward.launcher.official'" not in text or "versionName '1.0.0'" not in text:
    raise SystemExit("Gradle identity/version patch failed")
app.write_text(text, encoding="utf-8")

manifest = Path("app/src/main/AndroidManifest.xml")
text = manifest.read_text(encoding="utf-8")
text = text.replace('android:label="Wayward Launcher · Image Services"', 'android:label="Wayward Launcher"')
blocks = [
    '        <activity android:name=".ImageProviderActivity" android:exported="false" />\n',
    '        <activity android:name=".ImageDiagnosticsActivity" android:exported="false" />\n',
    '        <activity android:name=".ImageGenPanelActivity" android:exported="false" />\n',
    '        <service android:name=".ImageGenService" android:process=":imagegen" android:exported="false"\n            android:foregroundServiceType="specialUse">\n            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"\n                android:value="사용자가 실행한 WML 이미지 생성 서버 유지"/>\n        </service>\n'
]
for block in blocks:
    if block not in text:
        raise SystemExit("Manifest feature block not found: " + block[:80])
    text = text.replace(block, "")
for permission in [
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>\n',
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>\n',
    '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>\n'
]:
    text = text.replace(permission, "")
manifest.write_text(text, encoding="utf-8")

main = Path("app/src/main/java/com/wayward/launcher/MainActivity.java")
text = main.read_text(encoding="utf-8")
start = text.find("        LinearLayout tiles=new LinearLayout(this);")
end = text.find("        home=page;", start)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("Could not isolate non-core home panels")
text = text[:start] + text[end:]
text = text.replace("            installImageGenDocumentStartScript(origin);\n", "")
text = text.replace("            DiagnosticsUploader.uploadPending(this);\n", "")
text = text.replace("        DiagnosticsUploader.uploadPending(this);\n", "")
old = """            SafTree sessionTree=tree;
            web.setVisibility(View.VISIBLE);
            DiagnosticsLogger.event(this,"INFO","launcher","game_webview_load_requested",null);
            web.loadUrl(gameUrl);
            // Image Studio is optional: its startup must never gate the game page.
            ensureImageGenServer(sessionTree,()->{
                if(web==null||!activeProfileId.equals(id))return;
                refreshImageGenStatus();
            });
"""
new = """            web.setVisibility(View.VISIBLE);
            DiagnosticsLogger.event(this,"INFO","launcher","game_webview_load_requested",null);
            web.loadUrl(gameUrl);
"""
if text.count(old) != 1:
    raise SystemExit("Could not isolate automatic Image Studio startup")
text = text.replace(old, new, 1)
text = text.replace('게임과 앱 전용 ZIP 모드 및 이미지 캐시를 새로 읽습니다.', '게임 파일을 다시 읽습니다.')
text = text.replace('return "0.3.2";', 'return "1.0.0";')
main.write_text(text, encoding="utf-8")

application = Path("app/src/main/java/com/wayward/launcher/LauncherApplication.java")
text = application.read_text(encoding="utf-8")
text = text.replace("            DiagnosticsUploader.uploadPending(this);\\n", "")
application.write_text(text, encoding="utf-8")
