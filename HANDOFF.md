# Wayward Remote Play Handoff

Updated: 2026-11-15 (Asia/Seoul)

## Active baseline
- Repository: diethoon/wayward-launcher-official
- Branch: work/remote-play-ux-v1-20261115
- Baseline before this handoff: b682b3fe37506bff8f98515b3bbfe24ddac2689a
- Earlier native integration branch: work/remote-play-integration-20261115 (39ac66aa6d6d15a706b079197f42543561f33fec). Compare; do not overwrite.
- Main branch and PRs remain untouched.

## Inherited implementation
- Native Host identity/QR signature verification, QR nonce verification, exact TLS certificate pinning, Android Keystore P-256 identity, signed pairing receipts, challenge/session authentication and revocation-aware reconnect.
- Native clients for ComfyUI gateway, approved-root files, resumable upload and game image resource bridge/cache.
- Existing per-game localhost WebView origin/storage remains unchanged.
- Latest commit b682b3f retains locally generated images behind the generated-image anchor after the local image server stops; it adds an instrumentation test for Windows Manager camelCase QR JSON nonce parsing and tamper/missing/malformed cases.
- Preserve NovelAI, CloudSave, SAF and current WebView routing.

## Current implementation commits
- `a54a3192f73b8c8eb3de237c897b2d326e98779e`: native `RemoteGameImageApiClient` maps the original game API to allowlisted Host routes.
- `0eeac29c5b00776d0f4ed3247e375f1b21f8dd3f`: native authenticated JSON response envelope with in-memory bearer token and 401 re-auth.
- `ecf3dd23bc8da4acdfa464b73b6b5aa7f9a584c7`: `ImageGenProxy` remote-PC transport branch.
- `9f29c18bd09b15200a27df9f5b09ff01a7464539`: generated portrait anchor routes to the PC Host in Remote PC mode; runtime mode metadata exposes `remote-pc`.
- `948ab8bd02e3d063bf3b990ebe7f77eba6f97543`: static contract tests cover original API route allowlist and native-only authentication.
- Existing scanner/file manager UI, QR signature/nonce validation, PC folder picker and Keystore pairing remain reused.

## Current validation and remaining work
- Active CI run `43720579709` on `948ab8bd02e3d063bf3b990ebe7f77eba6f97543` is the validation authority; await its full Debug/Release APK, Android 15 emulator and instrumentation result.
- Prior current-branch run `43717310911` passed on pre-bridge commit `543a0c6e5d51b5c7b7a2e29ed8c3f78cb1c54103`; this does not validate the new bridge.
- Physical Android-to-Windows Wi-Fi, actual GPU inference, final portrait registration through real game IndexedDB, and reconnect under PC reboot remain unverified.
- Ensure local NovelAI/CloudSave/SAF/WebView routing remains unchanged; no token is stored in JS or persistent preferences.

## Constraints
- No merge, official release, public endpoints, port opening or firewall changes.
- Do not add Android-side pairing removal; revocation is PC-managed only.
- Preserve auth/encryption/storage contracts absent explicit approval.
- Append every milestone with commit SHA, tests and remaining blockers.