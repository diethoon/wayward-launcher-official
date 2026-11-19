# WAL Android Launcher — Play Protect APK forensic review

Date: 2026-11-20
Scope: signed Official 1.0.0 vs signed Development 1.0.9 APKs supplied by the existing Actions artifact. No APK was re-uploaded by the user.

## Artifact identity

| Artifact | Bytes | SHA-256 | Package | Version |
|---|---:|---|---|---|
| WAL-Android-Launcher-1.0.0.apk | 10,225,434 | `06de489e3dea4e7d6429f16f31f3e318fc9d158a2a557ff0a157dbb7ac1d72d9` | `com.wayward.launcher.official.wal` | 1.0.0 / code 1 |
| WAL-Android-Launcher-Development-1.0.9.apk | 10,226,050 | `0c67b5c6a82b62de36353e04fe5d76b56696176b5bebc95e469bcb6b44bf1e80` | `com.wayward.launcher.official` | 1.0.9 / code 109 |
| WAL-Android-Launcher-Development-1.0.9-debug.apk | 11,882,365 | `3208469f5572cae98f9f74fbe053cba9a61b12a9f5ffd3fa7ec046fb1a33e248` | Debug variant | 1.0.9-debug |

The two Release APKs are both signed with the same certificate:
- Subject: `CN=Wayward Official Launcher, OU=Launcher, O=Wayward, L=Jeju city, ST=Jeju-do, C=KR`
- SHA-1: `d9be7443eb247d27c958cc4250d7f5d1e078f740`
- SHA-256: `f3ca95ad6c6c7d192327c88f82b59e53b2c9897c455a0f0bef2eafcf29a8d754`
- Validity: 2026-11-11 through 2054-03-29 UTC.

The Debug APK uses the standard Android Debug certificate (SHA-1 `860e4ef99d249caff4be0f329b3018452b07a7a4`), as expected. It is not the release artifact.

## APK container and manifest comparison

- Both Release APKs contain 562 ZIP entries, have no duplicate entry names, and pass Python ZIP CRC/decompression validation.
- Both contain one `classes.dex`, no native `.so` libraries, and the same 37 TypeScript backend source assets.
- Both target API 35 and require min API 29.
- Shared runtime permissions: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, ACCESS_NETWORK_STATE, plus each package's generated AndroidX dynamic receiver permission.
- No SMS permissions, notification-listener service permission, accessibility service permission, MANAGE_EXTERNAL_STORAGE, QUERY_ALL_PACKAGES, SYSTEM_ALERT_WINDOW, or legacy external-storage permissions were found in either merged manifest.
- Official-only manifest permission: `android.permission.REQUEST_INSTALL_PACKAGES`.
- Other manifest differences are package identity substitutions in generated AndroidX permission names, FileProvider authority, ML Kit provider authority, startup provider authority, and game-session task affinity, plus the expected app label/version/package ID. No unexpected exported component difference was observed in the parsed merged manifests.

## Official-only code path found

The permission originates from `app/src/official/AndroidManifest.xml` in the Official source tree. That file declared only `REQUEST_INSTALL_PACKAGES`.

The permission is actively used by the Official-only updater:
1. `MainActivity.onResume()` invokes `OfficialUpdateManager.installPendingIfAllowed()` and checks stable releases.
2. `OfficialUpdateManager.install()` stores a pending APK path and SHA-256.
3. If the app is not allowed to request installs, it opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for the Official package.
4. It then passes the APK through the app's FileProvider using an APK MIME type and launches Android's package installer.
5. The downloaded APK is checked for SHA-256, package ID, version, and the registered signing certificate before handoff.

The Development Release does not declare this permission and does not include these Official-only self-update strings in its DEX. The Official DEX's only relevant unique installer strings include `canRequestPackageInstalls`, `android.settings.MANAGE_UNKNOWN_APP_SOURCES`, and `application/vnd.android.package-archive`. Generic PackageInstaller/FileProvider references also exist in the Development DEX due to shared Android libraries and are not, by themselves, evidence of an Official-only installer.

## Additional runtime-execution risk signal

Static source review found a second material security signal, separate from the removed self-updater:

- `ImageGenService.ensureBun()` downloads the ARM64 Android Bun ZIP from the pinned GitHub release URL.
- It verifies the archive against a hard-coded SHA-256, extracts the Bun executable into app-private `getFilesDir()/imagegen/runtime`, marks the executable read-only, validates its version by executing it, and starts the ImageGen backend through `/system/bin/linker64`.
- The TypeScript backend is bundled in APK assets, but the native Bun executable is downloaded at runtime rather than shipped in the APK.

The pinned hash, HTTPS source, app-private storage, and executable validation are positive integrity controls. However, this is still downloaded native-code execution and is a more material review signal than ordinary WebView/network traffic. Android's security guidance recommends avoiding dynamic code loading and, when unavoidable, using trusted sources and integrity checks. This finding is **not proof** that Play Protect classified the APK because of Bun, but it is a concrete behavior to investigate against the PHA detection.

No change was made to this runtime because deleting it would remove the current local ImageGen server and break that feature. A replacement architecture (for example, an APK-bundled signed runtime or a non-native in-process execution design) needs a separate compatibility, size, and security decision; do not silently remove ImageGen.

## Policy assessment

Google Play's current `REQUEST_INSTALL_PACKAGES` policy says this restricted permission is for apps whose core functionality includes sending/receiving app packages and enabling user-initiated package installation. It explicitly says the permission may not be used for self-updates unless the app is an eligible enterprise device-management app. WAL Launcher is not an enterprise device-management app. Therefore, the Official self-update implementation is not an appropriate use of this permission for a Google Play-distributed build, even though the final installer handoff requires user action.

This is a confirmed Official-only policy-sensitive implementation and should be removed independently of whether it caused the observed Play Protect prompt.

## Play Protect classification — not yet proven

The user supplied the exact dialog title `유해한 앱 차단됨` (“Harmful app blocked”), with body text stating that the app may be harmful and the button `무시하고 설치하기`. Google's developer guidance identifies **Harmful App Blocked** as a Potentially Harmful Application (PHA) / malware or mobile unwanted software classification, not the ordinary “App Scan Recommended” or “Send App for Security Check” unknown-app prompt. The category is therefore identified; the precise detection reason or PHA family is not.

Google's developer guidance distinguishes:
- Internet-sideloading + specific sensitive permissions (RECEIVE_SMS, READ_SMS, notification-listener, ACCESSIBILITY) — neither APK declares these.
- Harmful App Blocked / PHA classification — this is the category matching the exact dialog title the user reported. Google describes it as malware or mobile unwanted software. The specific PHA category and detection reason remain unknown without Google's scan details.
- App Scan Recommended / unknown app — Play Protect has not seen the app before; the package's first-seen status and distribution history matter. Appeals do not remove this informational prompt; Google says Play Protect must become aware of the app.
- Send App for Security Check — a separate unknown-app scan prompt.

The Official package ID is new/different from Development, so an unknown-package / first-seen path is plausible. This is not proof of the exact warning. A screenshot of the full dialog (including title and buttons), Play Protect scan result, source app used to install, device model, Android/Play Protect version, and whether a scan completed are required to classify it accurately.

## Change prepared on a separate branch

Branch: `security/play-protect-review-20261120`
PR: https://github.com/wal-studio/wayward-launcher-official/pull/43

Changes:
- Remove the Official-only `REQUEST_INSTALL_PACKAGES` manifest declaration.
- Remove the unknown-app-source settings deep link and app-initiated APK installer handoff.
- Keep stable release discovery and validation of release metadata, APK SHA-256, package ID, version, and release signer before offering an update.
- Change the explicit Update action to open the allowlisted official GitHub release page in the browser. The user downloads and installs through the browser/Android installer flow, not through a self-updating launcher.
- Remove the pending installer lifecycle hook and obsolete FileProvider cache path.
- Add unit coverage for the allowlisted release-page URL.

This retains update discovery and user-directed update access but changes the final installation route. The new branch does not change the existing release package ID or signing key.

## Rebuild and verification result

CI: https://github.com/wal-studio/wayward-launcher-official/actions/runs/44618746978

Passed on the security branch:
- `git diff --check`
- source/contract tests (Official scope, OAuth, CloudSave, Remote Play, Image Studio, SAF/file access and updater policy)
- Gradle `testDebugUnitTest`
- Debug, Development Release and Official Release APK builds
- existing Release signing identity verification
- Official package/version/label/signature verification
- merged Official and Development APK permission audit
- ZIP duplicate-entry and CRC/decompression audit
- Android 15 Remote Play Keystore/crypto instrumentation

Rebuilt Official candidate:
- Size: 10,224,894 bytes
- SHA-256: `b1a1e2029a639473fc245de4b68b08c733c2c085baf12642fee72b96fdaef725`
- Package/version: `com.wayward.launcher.official.wal` / 1.0.0 (versionCode 1)
- Release signer SHA-1 and SHA-256 remain unchanged from the original certificate.
- Merged manifest no longer declares `REQUEST_INSTALL_PACKAGES` or the listed high-risk permissions.
- Rebuilt Official DEX no longer contains the self-installer/unknown-source settings code strings.

## Remaining verification

- Android 15 launcher UI/SAF emulator smoke test and Official/Development product-split install smoke test are still running in CI.
- The reported title maps to Google's Harmful App Blocked / PHA category, but the exact detection reason or PHA family cannot be determined from static analysis alone.
- No actual Play Protect scan result has been obtained; no clean classification is claimed.
- No MobSF or VirusTotal result is claimed. The APK was not submitted to public multi-engine services because that would disclose the sample to a third party.
- No physical Samsung SM-S938N install/upgrade or Play Protect screen capture has been completed. Earlier device-smoke attempts could not discover an authorized ADB device.
- No Google Play Console access is available in the connected tool set; Play Console registration, Play App Signing, internal test track, and official Play Protect recognition remain external tasks.
