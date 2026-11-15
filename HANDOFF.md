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

## Work remaining
1. Audit local image anchor routing and QR nonce compatibility against the current Windows Manager payload.
2. Complete Android pairing UX, PC folder browsing and separate Remote PC file manager using existing native clients. Do not duplicate crypto/network clients.
3. Identify exact original game HTML /api/image/*, /api/batch/* and image-pack contracts before implementing adapters. Keep generated results app-private; never expose Host credentials to JavaScript.
4. Run JS/unit, Android Debug/Release, Android 15 emulator and instrumentation CI on the final active branch commit.

## Inherited verification (earlier branch only)
- CI run 43682267815 passed on 39ac66aa6d6d15a706b079197f42543561f33fec, including Debug/Release APK build and 3 Android instrumentation tests.
- This does not validate current branch b682b3f or later commits. Re-run CI on the active branch.
- No physical Android-to-Windows Wi-Fi test is available.

## Constraints
- No merge, official release, public endpoints, port opening or firewall changes.
- Do not add Android-side pairing removal; revocation is PC-managed only.
- Preserve auth/encryption/storage contracts absent explicit approval.
- Append every milestone with commit SHA, tests and remaining blockers.