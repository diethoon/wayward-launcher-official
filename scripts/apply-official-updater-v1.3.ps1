$ErrorActionPreference = "Stop"
$utf8 = [System.Text.UTF8Encoding]::new($false)
$nl = [string][char]10

function Read-Lf([string]$Path) {
    return [IO.File]::ReadAllText((Join-Path $PWD $Path)).Replace([Environment]::NewLine,$nl)
}
function Write-Lf([string]$Path,[string]$Value) {
    [IO.File]::WriteAllText((Join-Path $PWD $Path),$Value.Replace([Environment]::NewLine,$nl),$utf8)
}
function Replace-Exactly([string]$Text,[string]$Old,[string]$New,[string]$Label) {
    $Old=$Old.Replace([Environment]::NewLine,$nl)
    $New=$New.Replace([Environment]::NewLine,$nl)
    $count=0; $offset=0
    while($true) {
        $found=$Text.IndexOf($Old,$offset,[StringComparison]::Ordinal)
        if($found -lt 0){break}
        $count++; $offset=$found+$Old.Length
    }
    if($count -ne 1){throw "$Label expected exactly one anchor, found $count"}
    return $Text.Replace($Old,$New)
}

$main=Read-Lf "app/src/main/java/com/wayward/launcher/MainActivity.java"
$existingPaths=Read-Lf "app/src/main/res/xml/diagnostics_file_paths.xml"
$existingTest=Read-Lf "scripts/test-product-split-v1.3.cjs"
$existingOfficialManifest=Join-Path $PWD "app/src/official/AndroidManifest.xml"
$updaterAlreadyIntegrated =
    $main.Contains("OfficialUpdateManager.installPendingIfAllowed(this)") -and
    $main.Contains("OfficialUpdateManager.checkForUpdate(this") -and
    $existingPaths.Contains('<cache-path name="updates" path="updates/"/>') -and
    (Test-Path -LiteralPath $existingOfficialManifest -PathType Leaf) -and
    ([IO.File]::ReadAllText($existingOfficialManifest).Contains("android.permission.REQUEST_INSTALL_PACKAGES")) -and
    $existingTest.Contains("OfficialUpdateManager.checkForUpdate")
if($updaterAlreadyIntegrated){
    Write-Host "Official updater lifecycle, FileProvider path, manifest permission, and contract tests are already integrated; no changes required."
    exit 0
}
$old=@'
        }
    }
    @Override protected void onPause(){
'@
$new=@'
        }
        if(BuildConfig.WAL_OFFICIAL){
            OfficialUpdateManager.installPendingIfAllowed(this);
            if(home!=null&&home.getVisibility()==View.VISIBLE){
                OfficialUpdateManager.checkForUpdate(this, update -> {
                    if(isFinishing()||isDestroyed())return;
                    String message="새 WAL Android Launcher 업데이트 "+update.versionName+"이 있습니다.";
                    if(!update.notes.trim().isEmpty()){
                        String notes=update.notes.trim();
                        if(notes.length()>600)notes=notes.substring(0,600)+"…";
                        message+="\n\n"+notes;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("업데이트 확인")
                            .setMessage(message)
                            .setNegativeButton("나중에",null)
                            .setPositiveButton("업데이트",(dialog,which)->OfficialUpdateManager.install(this,update))
                            .show();
                });
            }
        }
    }
    @Override protected void onPause(){
'@
$main=Replace-Exactly $main $old $new "Official updater onResume integration"
Write-Lf "app/src/main/java/com/wayward/launcher/MainActivity.java" $main

$paths=Read-Lf "app/src/main/res/xml/diagnostics_file_paths.xml"
$paths=Replace-Exactly $paths '    <cache-path name="diagnostics" path="diagnostics/"/>' ('    <cache-path name="diagnostics" path="diagnostics/"/>' + $nl + '    <cache-path name="updates" path="updates/"/>') "Updater FileProvider cache path"
Write-Lf "app/src/main/res/xml/diagnostics_file_paths.xml" $paths

$officialManifest=@'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
</manifest>
'@
$officialDir=Join-Path $PWD "app/src/official"
New-Item -ItemType Directory -Force -Path $officialDir | Out-Null
Write-Lf "app/src/official/AndroidManifest.xml" $officialManifest

$test=Read-Lf "scripts/test-product-split-v1.3.cjs"
$anchor="console.log('WAL Android product split v1.3 contract checks passed');"
$extra=@'
const updater = fs.readFileSync('app/src/main/java/com/wayward/launcher/OfficialUpdateManager.java','utf8');
const officialManifest = fs.readFileSync('app/src/official/AndroidManifest.xml','utf8');
const providerPaths = fs.readFileSync('app/src/main/res/xml/diagnostics_file_paths.xml','utf8');
assert.match(main,/OfficialUpdateManager\.installPendingIfAllowed\(this\)/);
assert.match(main,/OfficialUpdateManager\.checkForUpdate\(this/);
assert.match(updater,/WAL-Official-Releases\/releases\?per_page=30/);
assert.match(updater,/optBoolean\("draft",\s*true\)\s*\|\|\s*release\.optBoolean\("prerelease",\s*true\)/);
assert.match(updater,/STABLE_TAG/);
assert.match(updater,/sha256:/);
assert.match(updater,/findChecksum/);
assert.match(updater,/apkCount != 1/);
assert.match(updater,/WAL-Android-Launcher-/);
assert.match(updater,/D9BE7443EB247D27C958CC4250D7F5D1E078F740/);
assert.match(updater,/context\.getPackageName\(\)/);
assert.match(updater,/versionCode\s*<=\s*installed\.getLongVersionCode\(\)/);
assert.match(updater,/FileProvider\.getUriForFile/);
assert.match(officialManifest,/android\.permission\.REQUEST_INSTALL_PACKAGES/);
assert.match(providerPaths,/name="updates" path="updates\//);
'@
$test=Replace-Exactly $test $anchor ($extra + $nl + $anchor) "Updater contract test insertion"
Write-Lf "scripts/test-product-split-v1.3.cjs" $test

Write-Host "Official stable updater integration changes applied."
