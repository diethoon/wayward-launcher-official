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

## 최종 구현·검증 기준선 — 2026-11-15
- Active branch: work/remote-play-ux-v1-20261115
- Final Android source HEAD: e9307b75131809aba70ee6072bd37ee820b06532
- Native legacy API mapping: a54a3192f73b8c8eb3de237c897b2d326e98779e; authenticated response envelope: 0eeac29c5b00776d0f4ed3247e375f1b21f8dd3f; Remote PC proxy branch: ecf3dd23bc8da4acdfa464b73b6b5aa7f9a584c7; generated anchor routing: 9f29c18bd09b15200a27df9f5b09ff01a7464539; pack mapping: 5a0648c4c441cb67ad79a5a67b7e4f5c198b41e3; route contract tests: e9307b75131809aba70ee6072bd37ee820b06532.
- Android CI 43723628767 passed on the final Android source HEAD: JS contracts, Official scope, Google OAuth, NovelAI, SAF, Debug/Release APK build, Release signing verification and Android 15 emulator/instrumentation.
- Android native instrumentation: 4 passed, 0 failed, 0 errors, 0 skipped. Includes Windows Manager camelCase QR nonce parsing/tamper checks, stable Android Keystore P-256 identity, HTTPS/private IPv4 enforcement and expiry-safe Host identity validation.
- APK artifact 14165572345 SHA-256: 6763a4299beb20f50817f0dabde6196da7b3faa85e3da391cf308e8e592a0a11. Release APK SHA-256: 5df1cb7ef04000077cb2932a156b87c0f0a9c3e1434fc5297c91bc4f5d7a2023. Debug APK SHA-256: 4930140154a3e2555a9971cd281851220d85ee2f8ded83d22d082b32eed9df48.
- Android 15 diagnostics artifact 14166185507 SHA-256: 054b8e7f315e39473c5572e5af6e10e73f26c7c0f522c4f4f8c25faf20d526fe.
- Existing Android QR pairing, PC approved-root picker, separate Remote PC file manager, native TLS pin/auth/session and resource cache were reused. Legacy game paths /api/image/health, /api/pack, /api/image/generate, /api/image/status/{id}, /api/image/cancel, /api/image-hit and /api/batch/* route through native code; bearer tokens are not exposed to page JavaScript.
- Remote image resource anchors under /images/__wayward_generated__/ are fetched from the selected approved PC root through the pinned HTTPS Native resource bridge. The per-game HTTPS localhost WebView origin/storage remains unchanged.
- Device approval defaults to ReadOnly. PC Manager must explicitly grant UseComfyUi to the paired Android device for image generation; BrowseGameLibrary is also needed for resource fetches.
- No physical Android handset, user's Windows Host, ComfyUI process or GPU is connected in this environment. Real Wi-Fi request, real GPU inference, handset image receipt and rendered portrait/IndexedDB behavior remain unverified. Android CI is not a substitute for those steps.
- Next engineer: install the delivered APK on the physical handset, pair with the delivered Host over private Wi-Fi, select the approved game root, grant UseComfyUi in Manager, generate one known portrait, then verify image hash/length on Android and its rendered game portrait. Do not change models/LoRAs/workflows or open public ports.

## Constraints
- No merge, official release, public endpoints, port opening or firewall changes.
- Do not add Android-side pairing removal; revocation is PC-managed only.
- Preserve auth/encryption/storage contracts absent explicit approval.
- Append every milestone with commit SHA, tests and remaining blockers.

## 필수 실행 원칙 — 시간 대비 결과 극대화
- 작업 전 최신 HEAD, 기존 구현, 이미 수행된 테스트·로그를 확인하고 재사용한다. 중복 조사·구현·테스트를 금지한다.
- 요구 결과와 완료 판정 기준을 먼저 고정하고, 가장 짧고 명료하며 정확한 구현·검증 경로를 선택한다.
- 서로 독립적인 조사·구현·테스트는 안전하게 병렬 처리한다. 선행 의존성이 있는 작업만 순차 진행한다.
- 큰 작업은 독립 검증 가능한 단계로 나누고, 각 단계에서 성공·실패 로그와 산출물을 즉시 확보한다.
- 실패 시 전체 과정을 반복하지 말고 실패 지점만 수정·재검증한다. 불필요한 대기, 과도한 추상화, 중복 서비스·테스트, 목적 없는 설계를 제거한다.
- 속도 최적화가 보안, 인증, 데이터 무결성, 기존 기능 호환성 또는 실제 기기 검증을 희생해서는 안 된다.
- 완료는 실제 동작과 증거로만 판정한다. 커밋 SHA, 실행한 테스트/CI 링크 및 결과, 생성 산출물, 미검증·차단 항목을 기록한다. Mock/정적 테스트를 실환경 성공으로 표현하지 않는다.
- 다음 작업자가 즉시 이어갈 수 있도록 이 HANDOFF를 각 의미 있는 단계마다 갱신한다.


## Branch tip after documentation and CI-filter updates
- Current branch tip: 7ce31c122642309d8f1635c25083910c90f11ed9. Product code remains validated at e9307b75131809aba70ee6072bd37ee820b06532.
- Workflow path-filter commit f5477707c7b1240713ddf4d39bed7bdf4dba71e0 was validated by Android CI 43724977457 (success). Documentation-only commits after that are ignored by push/pull_request builds.
- Final APK rebuilt in workflow run 43724977457; artifact 14165644599 SHA-256 8aaf2400d1e36f20d8a1e56a497bf0bc60b37c90537624df4dc2b6aaccdb7c96.

## CloudSave native account-selection and logout bridge restoration — 2026-11-15
- Starting Remote Play baseline: `3930d61fbd3e4e5a51171958eaba538c1e3ea420`. The active branch was confirmed identical to this baseline before edits.
- The request's source branch `feat/cloudsave-mobile-prep-preview-20261115` was not present under that exact name. The repository contains `feat/mobile-cloudsave-embedded-preview-20261115`; it adds only the CloudSave preview-index helper and does not contain the native account-selection change. The supplied abbreviated SHA `2c197eb7` could not be resolved in this repository.
- Actual native account-selection source found in `fix/cloudsave-native-auth-selection-20261114`, commit `0ef46f7ad3271d0b284319596af5168074bee8fb` (`fix(android): expose native Google account selection for CloudSave`). Ported only the native API/selection logic; intentionally did not port that commit's app versionCode/versionName bump.
- Explicit logout token-cache clearing source was found on `work/cloudsave-explicit-native-logout-20261112` and reused minimally. The WebView bridge now remembers the successful access token, can recover the saved token from `wml-google-drive-session-token-v1` after reload, and calls the existing native `clearGoogleAccessToken` only when the exact CloudSave `로그아웃` button inside `.wml-gd-wrap` is clicked. The existing native Google Identity `clearToken` method is reused. CloudSave's own logout handler remains responsible for clearing its WebView session/local data; no encrypted save data or sync code was changed.
- Changes on this branch:
  - `910c24da3e00e33afa22b3a11f0c517760ce4c80`: `MainActivity.java` adds `startGoogleDriveAuthWithPolicy(nonce, forceSelectAccount)`; legacy `startGoogleDriveAuth(nonce)` delegates with `false`; `SELECT_ACCOUNT` is set only for true.
  - `6aab654ed74440571814a2a9da0f8537df8d9949`: `wayward-google-auth-bridge.js` captures successful access token and clears the native token cache on explicit CloudSave logout.
  - `deaf7a80bd1421a4d3f9e7ad0b7a56c7e4e05027`: extends `scripts/test-google-auth-bridge.cjs` for CloudSave logout, persisted-token-after-reload and Drive 401 invalidation.
  - `bf98add05705701a8142b88b668181f6eead73a5`: adds `tests/cloudsave-native-auth-selection.test.cjs` for the dual native API contract, conditional account prompt, single native authorization builder, native token clear path and absence of a parallel GIS OAuth path.
  - `9702735d6b799c9bff1b20f388cda5db23905aa1`: adds the new policy contract test to `.github/workflows/android-validation.yml`.
- Latest confirmed branch tip at this handoff: `9702735d6b799c9bff1b20f388cda5db23905aa1`. The active Remote Play branch remains separate; main was not changed, and no merge or release was performed.
- Static GitHub source review confirms exactly one native `AuthorizationRequest.builder()`, both JavaScript-interface methods, the conditional `SELECT_ACCOUNT` prompt, reuse of existing `clearToken`, logout cache-clear listener, and no `google.accounts.*` / `gapi.auth2` alternate OAuth flow in the bridge.
- Verification status: pre-existing Remote Play/NovelAI/SAF CI results remain valid for the previous code baseline, not this latest CloudSave patch. The new CI run/build has not yet been surfaced by the available Actions-run lookup (the queried commit status and PR-run lookup returned no records). Therefore this patch's automated tests, Debug/Release APK build, artifact and SHA-256 remain unverified here.
- Real Google account sign-in, forced account chooser after logout, switching to a different account, and CloudSave sync/save/restore on a physical Android device remain unverified. Do not mark the overall task complete until those steps and the latest CI/APK are verified.
- Protected scope: no CloudSave encryption/storage/sync code, user backup/data format, NovelAI provider/token behavior, Remote Play pairing/TLS/image/file functionality, WebView origin, SAF behavior or installed model/workflow assets were modified.
