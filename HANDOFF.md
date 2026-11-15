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

## 필수 실행 원칙 — 시간 대비 결과 극대화
- 작업 전 최신 HEAD, 기존 구현, 이미 수행된 테스트·로그를 확인하고 재사용한다. 중복 조사·구현·테스트를 금지한다.
- 요구 결과와 완료 판정 기준을 먼저 고정하고, 가장 짧고 명료하며 정확한 구현·검증 경로를 선택한다.
- 서로 독립적인 조사·구현·테스트는 안전하게 병렬 처리한다. 선행 의존성이 있는 작업만 순차 진행한다.
- 큰 작업은 독립 검증 가능한 단계로 나누고, 각 단계에서 성공·실패 로그와 산출물을 즉시 확보한다.
- 실패 시 전체 과정을 반복하지 말고 실패 지점만 수정·재검증한다. 불필요한 대기, 과도한 추상화, 중복 서비스·테스트, 목적 없는 설계를 제거한다.
- 속도 최적화가 보안, 인증, 데이터 무결성, 기존 기능 호환성 또는 실제 기기 검증을 희생해서는 안 된다.
- 완료는 실제 동작과 증거로만 판정한다. 커밋 SHA, 실행한 테스트/CI 링크 및 결과, 생성 산출물, 미검증·차단 항목을 기록한다. Mock/정적 테스트를 실환경 성공으로 표현하지 않는다.
- 다음 작업자가 즉시 이어갈 수 있도록 이 HANDOFF를 각 의미 있는 단계마다 갱신한다.

## 최신 검증 상태 및 즉시 다음 작업 (2026-11-15 14:xx UTC)
- Android CI `43720845568` passed on code commit `9123a7c541d970468a81e859a5afb021cbc7ca80`: Remote Play routing contract, Google OAuth bridge, existing feature guards, Debug/Release APK build/signature verification, artifact upload, Android 15 emulator startup and native crypto tests all passed.
- Earlier run `43720579709` failed only at the Remote Play routing contract regex; fixed in `9123a7c` and the full workflow then passed. Do not repeat this investigation or rerun CI without a code change.
- The docs-only HEAD commit `8d3f55c` triggered another workflow (`43722031661`); it adds no code coverage. Treat `43720845568` as the latest meaningful code-validation result.
- Remaining critical proof: physical Android ↔ Windows Host Wi-Fi request, real ComfyUI GPU generation, returned image bytes reaching Android, and actual game IndexedDB/portrait anchor update. These are not proven by static contract tests or emulator crypto tests.
- Next: use the already-built APK and existing Host package; execute one deterministic end-to-end scenario with one known image request. Capture request ID, Host response/status, image hash/size, Android receive/cache result, and game anchor/IndexedDB result. Fix only the first failing boundary; do not redesign or repeat successful CI stages.
- If physical PC/GPU/device access is unavailable, complete the deterministic integration harness and provide the exact missing hardware action as a blocker; do not idle or label mock success as real-device success.
