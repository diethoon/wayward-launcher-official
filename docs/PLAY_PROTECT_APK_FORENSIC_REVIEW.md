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

## Policy assessment

Google Play's current `REQUEST_INSTALL_PACKAGES` policy says this restricted permission is for apps whose core functionality includes sending/receiving app packages and enabling user-initiated package installation. It explicitly says the permission may not be used for self-updates unless the app is an eligible enterprise device-management app. WAL Launcher is not an enterprise device-management app. Therefore, the Official self-update implementation is not an appropriate use of this permission for a Google Play-distributed build, even though the final installer handoff requires user action.

This is a confirmed Official-only policy-sensitive implementation and should be removed independently of whether it caused the observed Play Protect prompt.

## Play Protect classification — not yet proven

The warning text supplied verbally is not enough to map the event to a specific Google classification.

Google's developer guidance distinguishes:
- Internet-sideloading + specific sensitive permissions (RECEIVE_SMS, READ_SMS, notification-listener, ACCESSIBILITY) — neither APK declares these.
- Harmful App / PHA classification — Google has identified the package as malware or mobile unwanted software; requires the actual Play Protect result and can be appealed after review.
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

## Remaining verification

- CI build, unit tests, APK signing verification, and merged-manifest permission check are pending for PR #43.
- The exact Play Protect classification cannot be confirmed from APK static analysis alone.
- No Play Protect device scan has been run in this environment; no claim of clean classification is made.
- No MobSF or VirusTotal result is claimed. Uploading a private APK to public multi-engine services requires a separate privacy/disclosure decision.
- No physical Samsung SM-S938N install/upgrade test has been run.
- No Google Play Console access is available in the connected tool set; Play Console registration, Play App Signing, internal test track, and official Play Protect recognition remain external tasks.
