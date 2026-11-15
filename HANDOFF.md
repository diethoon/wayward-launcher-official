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


## CloudSave native account-selection bridge wiring — 2026-11-16

### 기준선 및 변경 범위
- 시작 기준: `work/remote-play-ux-v1-20261115` @ `128ee994c059fcb82c649f2527806be5adf606cc`.
- 검증 대상 코드 HEAD: `96387e622fe2cf8c36e222f8840d3a72f6dee961`.
- 변경 파일은 다음 세 개로 제한했다.
  - `app/src/main/assets/wayward-google-auth-bridge.js`
  - `scripts/test-google-auth-bridge.cjs`
  - `tests/cloudsave-native-auth-selection.test.cjs`
- `MainActivity.java`, WebView Origin/초기화, SAF, NovelAI, Remote Play 페어링/TLS/이미지 생성, CloudSave 암호화/세이브 형식/동기화 코드는 변경하지 않았다.

### 구현한 인증 경로
- CloudSave의 기존 브리지 호출이 전달하는 `login=remember` 또는 `login=select_account`을 Android document-start bridge에서 읽는다.
- 새 API가 있으면 모든 요청을 `startGoogleDriveAuthWithPolicy(nonce, forceSelectAccount)`로 전달한다.
  - 일반 연결: `forceSelectAccount=false`.
  - 명시적 로그아웃 후 재인증: `forceSelectAccount=true`.
- 새 API가 없는 구형 런처는 일반 `remember` 연결에 한해 기존 `startGoogleDriveAuth(nonce)`를 사용한다. 강제 계정 선택 요청은 legacy API로 조용히 대체하지 않고 명시적인 오류를 반환한다.
- 기존 `takePendingStateForNonce(nonce)` polling, nonce 검사, WebView `message` source/origin 반환, timeout 및 iframe 정리 흐름은 재사용했다.
- 인증 허용 Origin 목록은 기존 `https://wayward-cloud-auth.vercel.app`을 유지하면서 현재 CloudSave Pages Origin `https://jeiky224.github.io`를 추가했다. URL Origin이 목록에 없으면 원래 `window.open` 동작을 유지한다.
- Native 측은 기존 `startGoogleDriveAuthWithPolicy` → `beginGoogleDriveAuth(nonce, forceSelectAccount)` → Google Identity `authorize` → `REQUEST_GOOGLE_AUTH` 결과 처리 → nonce별 결과 회수 경로를 그대로 사용한다. `forceSelectAccount=true`일 때 기존 Native 코드가 `AuthorizationRequest.Prompt.SELECT_ACCOUNT`를 설정한다.
- 로그아웃 시 기존 CloudSave 모드의 WebView 상태 초기화와 `clearGoogleAccessToken(token)` 호출, 기존 bridge의 CloudSave 로그아웃 감지/네이티브 토큰 캐시 정리 로직을 재사용했다. 이는 access-token cache clear이며 Google 앱 권한 철회 성공을 의미하지 않는다.

### 회귀 테스트 및 Android CI
- GitHub Actions [Android validation run #43728860155](https://github.com/diethoon/wayward-launcher-official/actions/runs/43728860155) — `96387e622fe2cf8c36e222f8840d3a72f6dee961`, **completed / success**.
- 성공 단계:
  - JavaScript 문법 및 diff whitespace 검사.
  - Official launcher scope 및 Remote Play native bridge contract.
  - 기존 Google OAuth bridge contract.
  - CloudSave native account-selection policy contract.
  - Image Studio server/image-pack isolation, NovelAI provider lock/token persistence.
  - image-server mode ordering 및 SAF image resolver.
  - Debug/Release APK 빌드.
  - Release APK 서명 인증서의 등록된 OAuth SHA-1 일치 검증.
  - Android 15 emulator launcher startup/accessibility smoke 및 기존 native crypto instrumentation.
- 신규 런타임 모의 테스트는 `select_account`에서 새 API에 `true` 전달, 일반 로그인에서 `false` 전달, legacy API 일반 로그인 호환, legacy-only 런처에서 강제 선택을 안전하게 거부하는 네 경로를 검사한다. 기존 로그아웃 캐시 정리, reload 후 저장 토큰 정리, Drive 401 invalidation 및 비대상 URL 보호 테스트도 통과했다.
- Android 15 emulator smoke는 Debug APK 설치/실행, 런처 프로세스 생존, 홈 화면 accessibility labels 및 screenshot, 기존 Remote Play/Keystore native crypto instrumentation을 검사했다. 이 smoke는 Google 계정 UI 인증이나 CloudSave Drive save/restore를 실행하지 않는다.

### APK 산출물 및 SHA-256
- APK artifact ID: `14167223207`, name: `wayward-launcher-official-apks`.
- GitHub artifact archive SHA-256: `31c7436b2ba5aa1b22c17164f43439cb5e2df7ac8db30bea15a17ae00f5685d9`.
- Extracted Debug APK: `app-debug.apk`, SHA-256: `2ed089fcbdddc566fb32770e2597623b3ae86a93de2359353944c134ae804e49`.
- Extracted Release APK: `app-release.apk`, SHA-256: `875ab8bd7a65f7c66a4bb9827b6e9edddb6bfbea67c643f1f497846f429c7613`.
- Android 15 diagnostics artifact ID: `14166499804`, archive SHA-256: `73115fd426931eac4ab26fe3cb1a2f819b1463631e1e60148a9fc97263e30589`.
- Local verified copies were extracted as `Wayward_Launcher_1.0.7-debug.apk`, `Wayward_Launcher_1.0.7-release.apk`, and `Wayward_Launcher_1.0.7_APK_SHA256SUMS.txt`. The uploaded APK artifact remains the authoritative CI artifact.

### 아직 실환경에서 검증하지 않은 항목
- 실제 물리 Android 기기에서 기존 Google 계정으로 Drive 저장목록이 열리는지.
- CloudSave 명시적 로그아웃 후 Google 계정 선택 화면이 실제로 열리는지.
- 다른 Google 계정을 선택한 뒤 새 계정으로 저장목록 조회·세이브·복원이 되는지.
- 기존 계정 재선택 및 기존 계정의 저장 데이터가 유지되는지.
- 사용자 WebView의 실제 세션/로컬 상태와 Native Google access-token cache가 기기에서 모두 정리되는지.
- 위 항목은 Android CI/에뮬레이터 및 모의 브리지 테스트만으로 완료 판정하지 않는다. 실제 handset + Google account + Drive를 이용한 별도 acceptance test가 남아 있다.

### 보호 경계
- 현재 Remote Play 브랜치만 수정했으며 main 병합, PR 병합, 정식 릴리스, 운영 배포 또는 사용자 기기 설치는 하지 않았다.
- 기존 CloudSave save/restore/encryption format, NovelAI, SAF, WebView Origin, Remote Play pairing/TLS/image generation 영역은 변경하지 않았다.
- 다음 검증자는 APK를 실기기에 설치해 기존 계정 sync → CloudSave logout → 다른 계정 선택 → 재인증 → 새 계정 save/load 및 기존 계정 재선택 경로를 확인하고, 결과와 실제 Google 계정 화면 증거를 이 HANDOFF에 추가해야 한다.


## 공통 시간 효율·독립 검증 표준 적용

이 저장소의 모든 후속 작업은 [Wayward 공통 엔지니어링·검증 운영 표준](https://github.com/diethoon/wayward/blob/feat/cloudsave-mobile-prep-preview-20261115/docs/WAYWARD_ENGINEERING_STANDARD.md)을 필수 준수한다. 기존 HANDOFF의 간략한 시간 효율 원칙을 대체·구체화하며, 엔지니어 실행 방법과 도구/플랫폼 선택, 자동화, 병렬화, 테스트 전략뿐 아니라 총괄참모의 독립 검증까지 적용한다.

필수 실행 요약:
- 최신 HEAD·기존 구현·HANDOFF·테스트·CI·로그·산출물을 먼저 확인하고 재사용한다. 중복 조사·구현·테스트를 금지한다.
- 작업별 최적 도구를 선택하고 반복 가능한 탐색·검사·빌드·패키징은 스크립트와 CI로 자동화한다. Vercel·Render·Cloudflare·GitHub는 각자의 강점과 실제 권한이 확인된 범위에서만 사용한다.
- 독립 작업은 충돌 없이 병렬화하고, 의존 작업만 순차 처리한다. 실패 지점만 수정·재검증한다.
- 총괄참모는 보고를 그대로 승인하지 않고 HEAD·diff·핵심 코드·테스트 유효성·CI Job·artifact·체크섬을 독립 확인한다.
- Mock/정적 테스트/에뮬레이터/실기기/실서비스 결과를 구분한다. 실환경 검증이 없으면 미검증으로 명시한다.
- 보안·데이터 무결성·기존 호환성·사용자 승인 경계를 시간 단축을 이유로 생략하지 않는다.
- 의미 있는 단계마다 HANDOFF를 갱신하고 다음 담당자가 중복 조사 없이 이어갈 증거와 다음 조치를 기록한다.

 
## Remote Host public endpoint compatibility — 2026-11-16

- Active branch: `work/remote-play-ux-v1-20261115`.
- Android implementation commit: `6349b92d535a89ee80b461c445e85ac77b13d532`.
- Changes: `RemoteHostConnection` persists an optional Host-signed `externalBaseUrl`; its validator accepts only HTTPS + numeric globally routable IPv4 + explicit port and rejects private/loopback/link-local/CGNAT/hostname/cleartext values. Legacy signed manifests without the field remain accepted.
- `RemoteHostClient` uses the original private LAN URL first and tries the optional signed public endpoint only for connection-establishment failures. Both endpoints use the same pinned TLS certificate SHA-256 and Host identity. SSL/pin failures and HTTP application/auth responses fail closed; request bodies are not replayed after a write starts.
- Added Android instrumentation coverage for the signed external QR field, JSON persistence/recovery, signature tampering and rejected URLs; the existing QR nonce, P-256 proof, signed Host challenge and receipt checks remain in place.
- Android Actions [run #43733766512](https://github.com/diethoon/wayward-launcher-official/actions/runs/43733766512): completed / success. Contract checks, Debug/Release builds, Release signing identity validation, Android 15 emulator startup and Native crypto instrumentation passed.
- APK artifact ID: `14169294143`, `wayward-launcher-official-apks`, archive SHA-256: `a727385675c866433be0ff3d88a9841fb10fb669003f62f79187c6c01461febc`.
- Android 15 diagnostics artifact ID: `14170075280`, archive SHA-256: `56498ee0826a38ffaeddc5ac16eedabd543ef10303c31149ec16e9199a7454fe`.
- No physical Android handset, physical Windows Host or WAN router path was exercised. First pairing is still expected over the LAN; later reconnect can use the signed external endpoint where router mapping and upstream access allow it.
