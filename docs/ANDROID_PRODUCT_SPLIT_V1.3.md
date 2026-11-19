# WAL Android product split v1.3

This document records the product boundary for the isolated Android work branch. It does not replace the existing Android/Remote Play integration contract.

## Product identities

| Property | Development | Official |
|---|---|---|
| Build type | `debug`, `release` | `official` |
| Application ID | `com.wayward.launcher.official` | `com.wayward.launcher.official.wal` |
| Debug application ID | `com.wayward.launcher.official.debug` | Not published |
| Version | `1.0.9` / code `109` | SemVer tag / derived increasing code (`1.0.0` starts at `1`) |
| App label | WAL Development | WAL Android Launcher |
| Signing | Existing registered Release certificate | Same registered Release certificate |

The Official build is a separate Android package. Android therefore gives it an independent app sandbox, preferences, files, SAF grants, WebView state, and Android Keystore namespace. No Development data or device credentials are copied into Official.

## UI and behavior boundary

Official hides the PC folder picker, PC pairing entry, Remote PC file manager, ComfyUI/Remote Play image route, and PC discovery/registration hooks. The underlying PC and Remote Play implementation remains in source and in the Development product; it is not deleted or rewritten.

Official retains the device-folder picker, diagnostics, game-folder validation, game profiles/session handling, and local image-pack/local generation behavior. The stable updater engine and Official-only lifecycle/installer integration are present. Update checks run on Official home resume with a six-hour throttle; downloads are SHA-256 and signer checked, and installation is handed to Android's user-approved package installer.

## Stable updater contract

- Source of releases: public `wal-studio/WAL-Official-Releases`.
- Stable tags only: `vMAJOR.MINOR.PATCH`.
- Draft and prerelease entries are ignored.
- Exactly one APK asset is accepted: `WAL-Android-Launcher-MAJOR.MINOR.PATCH.apk`.
- SHA-256 is read from the GitHub asset digest or `SHA256SUMS.txt`.
- Before handing the APK to Android Package Installer, the updater verifies SHA-256, package ID, version/tag agreement, increasing versionCode, and the registered Release signer SHA-1.
- Android's package installer remains responsible for the user's explicit install approval.
- `versionName` is supplied with `-PwalOfficialVersionName=MAJOR.MINOR.PATCH`; `versionCode` is derived as `1 + (MAJOR - 1) * 1,000,000 + MINOR * 1,000 + PATCH` (`MAJOR >= 1`, `MINOR/PATCH < 1000`). The initial `1.0.0` build therefore remains code `1`, while `1.0.1` is code `2`.
- The Official app requests unknown-source install permission only when the user chooses to install an update.

## Validation status

The isolated branch is based on integrated baseline `040393d86e4812826d61a701b5b37f044438a667`. Product-split source contract checks passed. A self-hosted validation run completed Debug/Release/Official Gradle compilation, unit tests, and APK package identity checks successfully. That run had no repository-scoped signing secrets, so Release and Official APKs were unsigned; signer verification and distributable APK validation remain pending. The Samsung SM-S938N step completed, but its log was not retrievable, so physical installation/startup is not claimed. Updater runtime behavior and the Official Google OAuth package registration also remain unverified.

## External OAuth registration

Because Official has a new Android application ID, verify that the existing Google Android OAuth configuration recognizes `com.wayward.launcher.official.wal` with the same registered Release certificate SHA-1. The source build cannot create or modify a Google Cloud OAuth client. Do not claim Google sign-in/CloudSave is validated for Official until that package/SHA-1 registration is confirmed.
