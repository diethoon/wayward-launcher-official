param(
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[1-9][0-9]*\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')]
    [string]$Version
)

$ErrorActionPreference = "Stop"
$expectedSigner = "D9BE7443EB247D27C958CC4250D7F5D1E078F740"

foreach ($name in @("ANDROID_KEYSTORE_PATH","ANDROID_KEYSTORE_PASSWORD","ANDROID_KEY_ALIAS")) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Existing Android Release signing environment is incomplete: $name"
    }
}
if (-not (Test-Path -LiteralPath $env:ANDROID_KEYSTORE_PATH -PathType Leaf)) {
    throw "The existing Release keystore path is unavailable."
}

$parts = [regex]::Match($Version,'^([1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')
if (-not $parts.Success) { throw "Version must use MAJOR.MINOR.PATCH without leading zeroes." }
$major = [long]$parts.Groups[1].Value
$minor = [long]$parts.Groups[2].Value
$patch = [long]$parts.Groups[3].Value
if ($minor -ge 1000 -or $patch -ge 1000) { throw "MINOR and PATCH must be below 1000." }
$versionCode = [int](1L + ($major - 1L) * 1000000L + $minor * 1000L + $patch)
if ($versionCode -gt 2100000000) { throw "Computed Android versionCode exceeds the supported range." }

$gradleArgs = @("--no-daemon","--stacktrace","clean","assembleOfficial","-PwalOfficialVersionName=$Version")
& gradle @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Official release build failed." }

$apkDir = "app/build/outputs/apk/official"
$apks = @(Get-ChildItem -LiteralPath $apkDir -Filter "*.apk" -File)
if ($apks.Count -ne 1) { throw "Expected exactly one Official APK; found $($apks.Count)." }
$apk = $apks[0].FullName
$sdk = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
$isWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$aaptName = if ($isWindows) { "aapt.exe" } else { "aapt" }
$apksignerName = if ($isWindows) { "apksigner.bat" } else { "apksigner" }
$aapt = Join-Path $sdk ("build-tools/35.0.0/" + $aaptName)
$apksigner = Join-Path $sdk ("build-tools/35.0.0/" + $apksignerName)
if (-not (Test-Path $aapt) -or -not (Test-Path $apksigner)) { throw "Android Build Tools 35.0.0 are unavailable under $sdk." }

$badging = (& $aapt dump badging $apk | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Could not read Official APK metadata." }
$identity = "package: name='com.wayward.launcher.official.wal' versionCode='$versionCode' versionName='$Version'"
if ($badging -notmatch [regex]::Escape($identity)) { throw "Official APK identity does not match $identity" }
if ($badging -notmatch "application-label:'WAL Android Launcher'") { throw "Official APK app label mismatch." }

$signature = (& $apksigner verify --verbose --print-certs $apk | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Official APK signature verification failed." }
$actualSigner = [regex]::Match($signature,"Signer #1 certificate SHA-1 digest:\s*([0-9A-Fa-f:]+)").Groups[1].Value -replace ":",""
if ($actualSigner.ToUpperInvariant() -ne $expectedSigner) { throw "Official APK signer does not match the registered Release certificate." }

$dist = Join-Path $PWD "dist/official"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$apkName = "WAL-Android-Launcher-$Version.apk"
$finalApk = Join-Path $dist $apkName
$sumFile = Join-Path $dist "SHA256SUMS.txt"
if ((Test-Path $finalApk) -or (Test-Path $sumFile)) { throw "Release output already exists; refusing to overwrite it." }
Copy-Item -LiteralPath $apk -Destination $finalApk
$hash = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $sumFile -Value "$hash  $apkName" -Encoding ascii
Write-Host "Verified Official release candidate:"
Write-Host "$finalApk | $((Get-Item $finalApk).Length) bytes | SHA256 $hash | versionCode $versionCode | signer $expectedSigner"
Write-Host "Attach this APK and SHA256SUMS.txt to the matching stable release in WAL-Official-Releases."
