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
- No physical Android handset, physical Windows Host or WAN router path was exercised. The Manager still creates the QR from an active private IPv4 Host listener. Once the QR payload is shared, its signed public endpoint can also carry initial Host identity/pairing requests if the private route is unreachable; the existing nonce and explicit Manager approval remain mandatory. Later reconnect can also use that signed endpoint where router mapping and upstream access allow it.

## WebView SAF image-pack directory picker — initial attempt (superseded) — 2026-11-16

> Historical first attempt only. The `MODE_OPEN_FOLDER` callback approach below did not solve Android 15 selection. It was superseded by the native button bridge and Android 15 DocumentsUI acceptance recorded in the later section, which is the current result.

- Latest Android implementation baseline before this fix: `work/remote-play-ux-v1-20261115` @ `ab185a4005b6ac86784c49b76e454d9a86d2c1e4`. Default `main` was newer by a documentation-only commit; the active product-code branch above was used.
- Code commit: `5f8303ae1bcda8fea697a19410b07c5d6e152035`.
- `WebFileChooser` now separates WebView `MODE_OPEN_FOLDER` (public value 2) from ordinary/single/multiple file requests. Folder requests launch `ACTION_OPEN_DOCUMENT_TREE` without `CATEGORY_OPENABLE`; the read + persistable + prefix grant is requested.
- On successful selection, the result must be a `content://` tree URI with a read grant. Manager calls `takePersistableUriPermission` before returning that tree URI via the existing `ValueCallback<Uri[]>`, allowing the WebView File System Access directory handle to read the chosen folder after restart. Invalid/cancelled/unpersistable selections are returned as cancellation.
- Ordinary file and multiple ZIP flows remain on the original `ACTION_OPEN_DOCUMENT` path. No changes to Faith/game code, `MainActivity` game file loading, `SafTree`, `SafImageDocumentResolver`, `SafImageCache`, image URL routing, directory scans, or cache behavior.
- Added regression contract assertions for the distinct intents, tree URI validation, persistable read permission and callback URI return; added the test to Android Actions.
- Android Actions [run #43773814527](https://github.com/diethoon/wayward-launcher-official/actions/runs/43773814527): **completed / success** on the code commit. All contract tests passed, Debug/Release APK builds passed, Release APK OAuth signing SHA-1 verification passed, and Android 15 emulator startup/native instrumentation passed (7 instrumentation tests completed).
- APK artifact ID `14185278869`, name `wayward-launcher-official-apks`, archive SHA-256 `be64cfcc097c1d50364f26f4f6c6669472ef8ed35453912e8c3bbc2c59bedfd9`.
- Android 15 UI diagnostics artifact ID `14184968422`, archive SHA-256 `d9feaab00d7fb7b7212e5c7df21126e662a910bffe46d256b9902136c56a334e`.
- Not verified in CI or on a physical handset: the live DocumentsUI screen showing the Korean `이 폴더 사용` button, selection of a real `image-packs` directory, reading its actual contents from Faith, and persistence after a physical app restart. The emulator smoke validates app startup/health and existing instrumentation, not this user-driven SAF dialog. The branch has not been merged to `main` or released.

## Native SAF image-pack picker — Android 15 acceptance — 2026-11-16

- Corrective implementation commit: [6e53f683bb1c44fe15282d3c5f73316fba6ec3e5](https://github.com/diethoon/wayward-launcher-official/commit/6e53f683bb1c44fe15282d3c5f73316fba6ec3e5). Follow-up permission/test commits: [01468dc154464efb77d4ee4650ada86417692bfd](https://github.com/diethoon/wayward-launcher-official/commit/01468dc154464efb77d4ee4650ada86417692bfd), [a03acb5e9eaf8de8a335a565dd6374e5c9d1485b](https://github.com/diethoon/wayward-launcher-official/commit/a03acb5e9eaf8de8a335a565dd6374e5c9d1485b), and [11bb760c309ce6af69164f2574698056dc459953](https://github.com/diethoon/wayward-launcher-official/commit/11bb760c309ce6af69164f2574698056dc459953).
- The WMLHost.chooseImagePackFolder() native bridge opens ACTION_OPEN_DOCUMENT_TREE directly. The game WebView receives a document-start showDirectoryPicker() adapter backed by ImagePackDirectoryAccess; selected SAF tree URI and persistable grants are retained per active game profile. Directory children are queried on demand and files are opened by document ID. No whole-tree scan, pre-index, or persistent image cache was added.
- WebFileChooser no longer relies on the API 37 MODE_OPEN_FOLDER signal. It is dedicated to the original ACTION_OPEN_DOCUMENT ordinary/multiple file path, preserving CATEGORY_OPENABLE, multiple-selection intent and ClipData handling.
- Android Actions [run #43782278549](https://github.com/diethoon/wayward-launcher-official/actions/runs/43782278549), head 11bb760c309ce6af69164f2574698056dc459953: completed / success. JavaScript and native bridge contract checks, Debug/Release APK builds, Release APK signing check, and Android 15 acceptance all passed.
- Android 15 DocumentsUI evidence: screenshot shows the selected WaywardImagePackFixture directory and active USE THIS FOLDER button, followed by Android's explicit ALLOW confirmation. The automated acceptance clicked the actionable confirmation button, waited for the modal to close, and received the selected tree URI.
- Native WebView bridge acceptance read the fixture pixel.png (70 bytes; PNG signature checked) from the selected directory. The app process was then force-stopped and restarted; the saved URI and Android persistable URI permission were restored, and the same file was read again successfully. CI log markers: PASS picked directory=WaywardImagePackFixture bytes=70 and PASS persisted-after-restart directory=WaywardImagePackFixture bytes=70.
- APK artifact ID: 14188329883, archive SHA-256: 2d60b13741ee548b170c18793f5905b51e9eb8851e3bb14cc1ae75a1e69a7348. Extracted Debug APK SHA-256: 096b0fdc968d9e2bbee4cf77847ddd026379f264cdc749562e0ba90680d5279b. Extracted signed Release APK SHA-256: 10d9bdc421069799784d06dd20481ecf6c79213b215ba0b12eed0910bbe4c359. Release signing certificate SHA-1 matched registered OAuth identity: d9be7443eb247d27c958cc4250d7f5d1e078f740.
- Android 15 diagnostics artifact ID: 14189330388, archive SHA-256: 572161f17053140fb485784fb612e100dd7acaf3df19f061aa9c48630fb9db57. It includes the DocumentsUI screenshots/XML, launcher home screenshot/XML and existing instrumentation result report (7 tests, 0 failures).
- Verification boundary: the Android 15 acceptance uses a debug-only WebView acceptance activity with the same production ImagePackDirectoryAccess class and document-start JS adapter; production MainActivity WMLHost registration, document-start asset injection and result routing are also checked in source contract tests. This confirms actual SAF selection, permission retention, and image bytes read through the same native bridge, but it is not an end-to-end run inside the live Faith game / production Image Studio view with a real user image-pack library. That in-game renderer integration remains unverified.
- Ordinary file/ZIP selection passed source-contract regression tests and remains on the original ACTION_OPEN_DOCUMENT path; a full interactive ZIP multi-select DocumentsUI run was not part of this acceptance.
- No physical handset was used. The UI acceptance was run in Android 15 (API 35) emulator DocumentsUI. No merge to main, public release, or deployment was performed. At this acceptance, main remained at 574c8cff9ffd5fbba3dbde7e6646bcde6989a488; active work branch head is 11bb760c309ce6af69164f2574698056dc459953.


## Android image-pack / diagnostics finalization — 2026-11-17

### 기준점 및 CI 실패 원인
- 저장소 diethoon/wayward-launcher-official, 브랜치 fix/android-qr-pairing-20261116.
- 조사 기준 HEAD 32bef8980509adaa78cd0f489b77206b35621905.
- Actions Run #43985651545는 계약 검사, Debug/Release 빌드, Release 서명 검증까지 통과했고 Android 15 emulator smoke의 Recents 검사에서 실패했다.
- android15-recents-after-game-back.txt에는 Wayward MainActivity Task ID 8이 canonical Recent tasks 목록에 한 번만 기록되어 있었다. 동일 Task ID 8은 뒤의 Visible recent tasks 요약에서도 반복 표시된다.
- 기존 파서는 dumpsys 전체를 분할해 요약 블록의 동일 Task를 두 번째 Task로 중복 집계했다. 증거상 제품의 이중 Task 생성이 아니라 테스트 파서의 false positive다.
- 수정은 canonical Recent tasks 절의 실제 Task 엔트리와 정확한 Activity component/Task ID만 검사한다. MainActivity, game-session routing, BACK, FLAG_ACTIVITY_REORDER_TO_FRONT 제품 코드는 변경하지 않는다. 회귀 테스트는 요약 중복, 실제 2개 Task, callingPackage 오탐, canonical 절 누락을 검증한다.

### 개인정보 및 자동 전송 경계
- DiagnosticsUploader는 DiagnosticsLogger가 관리하는 런처 이벤트(launcher.ndjson, launcher.previous.ndjson)만 읽는다. WARN/ERROR 이벤트 및 앱 resume 시 재시도하며, imagepack.ndjson나 사용자가 직접 공유하는 ZIP은 읽거나 전송하지 않는다.
- 런처 업로드 이벤트는 id/timestamp/level/component/event, 생성된 deviceId, appVersion, SDK/model/process/PID 및 redaction 처리된 details를 포함한다. 기존 이벤트 범위와 HTTPS endpoint는 유지한다.
- 런처와 이미지팩 로그에 content URI 및 외부 저장소/앱 내부 절대 경로 정제를 추가한다. ZIP 자동 검증은 현재/이전 로그 포함, 게임 미디어 제외, 인증정보/URI/절대 경로 패턴 검사를 수행하도록 강화한다.
- 기존 메인 화면 진단 버튼, ZIP exporter, FileProvider 공유 시트는 재사용한다. ZIP 공유 예외는 기존 MainActivity에서 로컬 진단 로그와 사용자 Toast로 기록한다.

### 백업 및 실제 제품 검증 경계
- backup_rules.xml과 data_extraction_rules.xml의 cloud-backup/device-transfer 모두 sharedpref/wayward_launcher.xml, root/app_webview/, file/diagnostics/ 제외가 이미 적용되어 있다. android:allowBackup="true"는 유지한다. 앱 전체 백업을 비활성화하지 않는다.
- Android 15 CI 게임 페이지는 workflow가 생성하는 HTML fixture다. 실제 MainActivity와 production native bridge를 통과하지만 Faith/실제 게임에 배포된 Image Studio 자산 자체를 실행한 검증은 아니다. 물리 Android 기기도 사용하지 않았다.
- 이전 실패 실행은 Recents assertion에서 중단되어 뒤의 진단 ZIP/share-sheet 검증은 실행되지 않았다. 다음 Actions 결과와 artifact에서 ZIP/share/privacy 검증을 별도 확인한다.


### Automatic upload scope correction — 2026-11-17
- Final source audit found that a WARN/ERROR event triggered upload but the pending reader could also enqueue INFO/DEBUG records. The image-pack journal and user-shared ZIP were not included, but the launcher-event severity boundary was broader than the stated policy.
- Added a dedicated severity policy and made the pending reader discard non-WARN/ERROR records before building the HTTPS payload. Successfully uploaded urgent IDs are acknowledged as before; non-urgent events remain local.
- Added dependency-free severity tests for WARN/ERROR and rejection of INFO/DEBUG/TRACE/empty/null, plus a source contract ensuring the logger's reader invokes the policy.
- The Android 15 smoke fixture now ensures both previous journal files exist and verifies their contents, as well as the active journals and metadata, in the manual ZIP. It continues scanning every included NDJSON file for credentials, full content URIs, and absolute paths.
- Previous full CI run on the pre-correction commit: [Run #44007028392](https://github.com/diethoon/wayward-launcher-official/actions/runs/44007028392) succeeded, including Android 15 emulator UI and existing native instrumentation. This follow-up requires its own final CI run.


### Previous-journal ZIP verification detail — 2026-11-17
- The Android 15 export test retains pre-existing rotated files; when either is absent/empty in the clean emulator, it writes a small valid NDJSON fixture without replacing existing content.
- ZIP acceptance parses every previous-journal line as JSON and confirms newly seeded fixture records are preserved. This verifies actual rotated-log inclusion rather than only checking exporter source.


### Clean-install diagnostics directory fixture correction — 2026-11-17
- The latest Android 15 run reached and passed the MainActivity directory-handle, pack.json multi-select, ZIP multi-select, BACK, and one-Task Recents checks. It then failed only while seeding the previous-journal ZIP test because the clean install had not yet created `files/diagnostics/`.
- Updated the smoke fixture to create that app-private diagnostics directory with `run-as mkdir -p` before writing its test journal records. This is test preparation only; it does not create or reset user game data. The new CI run must re-verify the diagnostic ZIP, previous journals and privacy scans.


### Game launch lifecycle audit — 2026-11-17
- Source inspection found `launchProfileSession()` always started a new `MainActivity` with no reuse/dedupe. Since the manifest uses the default `standard` launch mode, tapping the same game's launch button from the in-session launcher panel could push another MainActivity and WebView for the same profile. `RUNNING_SESSIONS` was only used to block profile removal; it did not prevent this duplicate launch.
- Added per-Activity and per-WebView IDs, task/profile/request correlation, and explicit local events for activity create/new-intent/resume/pause/destroy, launch requested, session open, WebView create/destroy, page start/finish/error, session visible, launcher return, and session destroy.
- Same-profile relaunch from its own in-session launcher panel now reuses that Activity and existing WebView; when a profile already has another live session Activity, launch routes to its existing session intent using `FLAG_ACTIVITY_REORDER_TO_FRONT`. The Activity launch mode and manifest task behavior remain unchanged.
- Page load success is only recorded after a matching main-frame game URL finishes without a main-frame network/HTTP or renderer error. UI visibility by itself does not set the success marker.
- Android 15 CI will be extended to exercise same-profile relaunch, switch to another registered profile, route back to an existing session, app background/restart and actual interaction with the generated game fixtures. These fixtures use MainActivity's production SAF/WebView route but are not the live Faith game assets; real game execution still requires a physical-device check with the user's actual game folder.


### Activity/Task count assertions — 2026-11-17
- Android 15 smoke deduplicates ActivityRecord instances by their stable record token and checks both Activity count and Task IDs. The two live profile sessions and the route back to a previous session must remain in one Wayward task.
- Background/foreground and post-force-stop relaunch use the explicit MAIN/LAUNCHER intent, matching an actual app-icon launch rather than a generic explicit Activity start.


### Multiple-game folder registration correction — 2026-11-17
- Android 15 repeated-session testing failed before the second profile was registered. The final UI hierarchy showed the launcher home with only the first game profile; the second `chooseFolder()` invocation did not present a usable sibling-folder location.
- Root cause in `MainActivity.chooseFolder()`: every subsequent `ACTION_OPEN_DOCUMENT_TREE` request supplied the previously selected game `treeUri` as `EXTRA_INITIAL_URI`. That points the document navigator back into the current game's selected subtree rather than a neutral location from which to choose another independent game root.
- Removed the prior-profile initial-URI hint for game registration. Each registration now opens the provider's normal location and allows selection of a different root; the existing persisted permission handling and profile de-duplication remain unchanged. Added a source contract that prevents reintroducing the trapping initial-URI behavior.
- The next Android 15 run must prove two profiles can be registered through the production UI before the game session reuse/switch scenario can proceed.


### SAF registration acceptance diagnostics — 2026-11-17
- The #44011729007 Android 15 smoke returned to the MainActivity home with zero registered profiles after the first DocumentsUI attempt. The job log did not distinguish a cancelled picker from a permission callback failure or a folder row navigation error.
- Instrumented the smoke helper to snapshot each distinct DocumentsUI state, and to persist launcher.ndjson plus Activity state if MainActivity returns without the requested profile name being visible. The helper now refuses to treat the permanent home label "내 게임" as proof of successful registration; it requires the selected folder's actual registered profile name.
- This is test-only diagnostic hardening; it does not alter the product picker or weaken the acceptance assertion.


### Emulator diagnostics artifact retention and timeout — 2026-11-17
- Added the new per-state DocumentsUI XML and launcher/Activity diagnostic files (`android15-folder-picker-*`) to the Android 15 diagnostics artifact upload.
- Increased only the Android 15 emulator step timeout from 8 to 15 minutes to accommodate the newly expanded two-profile Activity/WebView lifecycle scenario. The build and deterministic test steps are unchanged.


### Existing-session route-back diagnostics — 2026-11-17
- The first full two-profile Android 15 run passed the image-pack directory bridge, pack.json multi-select, ZIP multi-select, same-profile Activity/WebView reuse, live fixture button interaction, and independent second-profile launch.
- It then timed out waiting for the first profile's HTML heading after routing back to the existing Activity. The final UI hierarchy showed a focused WebView with no exposed text nodes; this alone does not distinguish a blank renderer from a WebView accessibility-tree refresh delay.
- Added targeted capture on this route timeout: full Activity/Task/Recents state, screenshot/UI XML, launcher and image-pack journals, relevant logcat, and a parsed summary of Activity/WebView/session lifecycle events and identities. These files are included in the Android 15 diagnostics artifact. The next run will establish whether `onNewIntent` reaches the original Activity and whether its original WebView is visible and loaded before deciding on any further product change.


### SAF picker race in UI smoke test — 2026-11-17
- The #44104931480 run captured the first UI hierarchy immediately after tapping `+ 현재 기기 폴더`. It was still the launcher home because `startActivityForResult(ACTION_OPEN_DOCUMENT_TREE)` is asynchronous. The added diagnostic guard incorrectly treated that first pre-transition frame as a completed picker cancellation.
- Corrected the test to require observing a non-home DocumentsUI state before it can interpret a later return to home as cancellation. If the picker never appears, it now waits a bounded number of home frames and reports that distinct failure. Product code is unchanged.


### URI-keyed game document tasks — 2026-11-18
- Root cause was confirmed by Android 15 run #44106553418: after launching profile two and then requesting profile one, the expected profile-one Activity did not receive `onNewIntent`. Both profiles use the same Activity component (`MainActivity`), and `FLAG_ACTIVITY_REORDER_TO_FRONT` reorders by component within the current task; it does not use the Intent data URI to select among multiple same-component Activities. The most recent MainActivity could receive the other profile's URI and replace its own session, while the original Activity remained underneath.
- Changed only game-session intents to `FLAG_ACTIVITY_NEW_DOCUMENT` (without MULTIPLE_TASK). Android document-task matching uses the base Intent ComponentName and data URI, equivalent to `documentLaunchMode="intoExisting"`; each profile's `wayward://session/{profileId}` therefore owns a distinct task, and relaunch targets that task's root Activity and `onNewIntent`. MainActivity remains `standard`; no global manifest launch-mode/document setting was added.
- The Image Studio return route to its owner session uses the same URI-keyed document intent to avoid component-only reordering ambiguity.
- Android 15 acceptance asserts launcher + profile one occupy two tasks, two profiles occupy three tasks, route-back preserves all three Activity identities and WebView instances, and finishing both document tasks returns to the one launcher task. The event reader includes previous and active launcher journals so log rotation cannot hide the initial instance IDs.


### WebView internal-history test hit target — 2026-11-18
- The #44115683699 Android 15 run proved URI-keyed session routing: same-profile Activity/WebView reuse, independent second-profile Activity/WebView, and return to the original first-profile Activity/WebView all passed, including the matching `onNewIntent` identity assertion and original JavaScript interaction state.
- The remaining failure happened only at the extra `SECOND_SESSION_PAGE` navigation used to verify WebView-history BACK. Its link was the last element after the file-picker controls, producing a fragile bottom-of-screen accessibility target. The final UI hierarchy was DocumentsUI, not the expected second page.
- Moved this fixture-only navigation control next to the top session controls as a real button, wired to `window.location.assign("session-second.html")`, and made the test require exactly one exact-text clickable node. It now captures the pre-tap screen and, on timeout, records full UI/screenshot/Activity/Task/WebView lifecycle diagnostics. No production game or WebView routing code changed in this test-only adjustment.


### Background/foreground intent verification — 2026-11-18
- The previous Android 15 run passed URI-keyed per-profile tasks, same-profile Activity/WebView reuse, independent second-profile launch, and return to the first existing Activity/WebView.
- Its last failure was the smoke test launching MAIN/LAUNCHER without the task flags supplied by an Android app-icon launch. Updated the two app-resume/restart invocations to use `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` (`0x10200000`). This models the actual launcher task-selection semantics and does not weaken Activity/Task assertions or change product launch mode.


### Dedicated game-task affinity — 2026-11-18
- The #44119453386 Android 15 run still reported duplicate launcher Activity records when the smoke test returned from HOME using real MAIN/LAUNCHER task flags. Per-profile document routing itself passed: directory bridge, pack.json and ZIP multi-select, same-profile Activity/WebView reuse, independent second-profile launch, and return to the original Activity/WebView.
- Root cause: game document Tasks inherited the same default `taskAffinity` as the real app-icon MainActivity. Android's launcher MAIN/LAUNCHER task selection could choose a game document Task as the matching app task, then stack a second launcher MainActivity into it.
- Added a non-exported `GameSessionActivity` alias targeting the existing `MainActivity`, with affinity `${applicationId}.games`. Game-session and Image Studio return intents target this alias with their per-profile `wayward://session/{id}` URI and `FLAG_ACTIVITY_NEW_DOCUMENT`. The real app icon continues targeting the original exported MainActivity under the default application affinity.
- The alias instantiates the same MainActivity Java class; no duplicate Activity implementation or WebView is created. Pairing, QR, Remote Play, and other launcher entry points remain on the original MainActivity. Updated the ActivityRecord counter to include both alias records and the shared Java target.


### Game session alias contract test correction — 2026-11-18
- The first run after adding the dedicated affinity alias failed only because the source-level test over-escaped the JavaScript regular expression for whitespace. The manifest value itself was present exactly as intended. Replaced the over-escaped multi-line regex with isolated alias-block attribute checks; product files were not changed.


### Corrected game-session affinity component — 2026-11-18
- The #44121982747 run still failed only at app-icon background/foreground with duplicate launcher Activity records. The preceding session route checks continued to pass.
- Confirmed the alias implementation was invalid for this purpose: Android's `<activity-alias>` attributes are only a subset of `<activity>`; `taskAffinity` is not one of the alias-supported attributes, so the alias inherited MainActivity's default affinity. The static test and build did not reveal that runtime manifest behavior.
- Replaced the alias with a real, minimal `GameSessionActivity extends MainActivity` implementation. MainActivity's existing execution, WebView, file chooser, image-pack bridge, diagnostics, OAuth and lifecycle code remains the single shared implementation; MainActivity is now non-final solely to permit this subclass. The new Activity alone has `${applicationId}.games` taskAffinity, while the exported launcher MainActivity and its MAIN/LAUNCHER filter remain unchanged.
- All profile launch and Image Studio owner-session intents now target GameSessionActivity with their per-profile `wayward://session/{id}` URI and existing `FLAG_ACTIVITY_NEW_DOCUMENT`. No alias is used.


### Real Activity task-affinity correction — 2026-11-18
- The previous CI failed in the source contract because its matcher expected a literal newline in the manifest opening tag. Replaced it with a whitespace-tolerant element match and added a direct assertion that the target Java class extends MainActivity.
- The task-affinity implementation itself is now a real `GameSessionActivity extends MainActivity`, not an activity alias. Android documents that alias attributes are a subset of activity attributes and that non-supported attributes inherit from the target; therefore the alias could not override task affinity. The actual Activity declaration supports `android:taskAffinity`.
- Also fixed the Android 15 smoke to persist the complete Activity/Task dump before asserting the one-launcher-Activity background/foreground invariant; on failure it now captures UI, screenshot, Task/Recents, journals and Activity diagnostics at that exact point instead of losing the dump.


### User-triggered Cloudflare diagnostic ZIP upload — 2026-11-18
- Added `DiagnosticsBundleUploader` for the existing Cloudflare Worker `https://wayward-diagnostics-ingest.trpgengine.workers.dev`. It calculates the ZIP SHA-256, requests a one-use ten-minute ticket from `POST /v1/ticket` using the stable local device ID normalized to 32 hex characters, then sends the same ZIP to the returned `/v1/upload` endpoint as `application/zip` with the one-time Bearer ticket. The code enforces the Worker 5 MiB maximum, bounded connect/read timeouts, and validates the response and server path.
- The top-right diagnostics button now creates one filtered ZIP, starts the user-triggered Cloudflare upload, and opens the existing Android sharesheet with that exact same ZIP. Upload success/failure is reported in a Toast and appended to the local image-pack diagnostic journal. A failed upload leaves the ZIP in app cache for manual sharing/retry.
- Existing `DiagnosticsUploader.uploadPending` WARN/ERROR event queue to the prior Vercel endpoint is unchanged. The new uploader does not read raw journals, send the event queue, acknowledge events, or automatically transmit image-pack logs outside the user's explicit diagnostics-button action; only the generated privacy-filtered ZIP is sent.
- Cloudflare Worker source inspected in the account: validates installId/SHA-256/byteLength, rate-limits per IP/install, issues one-time tickets, verifies exact content length and digest, and stores the ZIP privately in R2 with D1 metadata.


### Developer-only QR image import — 2026-11-18
- The existing camera flow remains first: tapping Pairing still opens Google Code Scanner immediately. Only after scanner cancellation or scanner failure does the temporary `QR 이미지에서 가져오기 · 개발자용` button appear on the pairing page.
- The option uses `ACTION_OPEN_DOCUMENT` with `image/*` and ML Kit's QR-only image reader. Zero QR results and multiple QR results receive distinct messages and return to the main launcher. Exactly one QR is automatically handed to the existing `handleQr()` path, retaining the original signed-manifest, nonce, approval, and pairing checks.
- Selected image URI and QR contents are never logged or persisted. Added Google Play services ML Kit image scanner API `play-services-mlkit-barcode-scanning:18.3.1`; existing Code Scanner camera dependency is unchanged. This temporary developer control is isolated from the regular camera scan path.


### Test parser correction for the game Activity declaration — 2026-11-18
- The combined feature run exposed an invalid JavaScript regular expression in the new manifest contract assertion. Replaced the expression with a literal XML element boundary search; product code and task behavior are unchanged.


### QR image success log privacy contract — 2026-11-18
- Updated the image-recognition success log to use a separate numeric `qrCharacterCount` variable rather than referencing the QR payload variable in the Log call. The logged value remains length-only; QR contents are never emitted.


### Preserve existing ZIP-share failure event names — 2026-11-18
- Restored the original `diagnostics_share_button_tapped` and `diagnostics_share_failed` event names so existing diagnostics contracts and downstream triage remain compatible. The new Cloudflare upload continues to use its separate `diagnostics_cloud_upload_*` event family.


### Same-ZIP upload/share contract — 2026-11-18
- Updated the diagnostics contract to assert that the Cloudflare uploader and Android native sharesheet receive the same generated `archive` object. The old one-argument share contract no longer describes the preserved share path after adding upload.


### Cloudflare diagnostic upload end-to-end acceptance — 2026-11-18
- Extended the Android 15 MainActivity diagnostics-button test to wait for the durable `diagnostics_cloud_upload_succeeded` event in the local image-pack journal. A ticket rejection, upload HTTP error, timeout, or absent completion event now fails acceptance with the safe result code.
- This verifies the user-triggered Cloudflare `/v1/ticket` + checksum-bound `/v1/upload` path, not merely ZIP generation and share-sheet presentation. The test emulator sends only its generated fixture diagnostics ZIP; no installed user's game folder or selected game files are involved.


### Launcher Activity singleTop — 2026-11-18
- Android 15 run #44128228790 retained the full activity/task dump after the final background/foreground failure. Task 10 contained two `MainActivity` records: the original root (ActivityRecord `912f62d`) and a second top instance (ActivityRecord `64d6d85`) created from the second MAIN/LAUNCHER intent. They shared the launcher Task ID; per-profile GameSessionActivity Task affinity and URI routing were already correct.
- Set `android:launchMode="singleTop"` only on the exported launcher `.MainActivity`. When the launcher Activity is already the top entry after game sessions close, tapping the app icon now delivers `onNewIntent` to that existing home instance instead of stacking another MainActivity. This is deliberately not `singleTask`.
- `.GameSessionActivity` does not inherit this manifest launch mode; it remains standard and continues to create/reuse URI-keyed per-profile document tasks under `${applicationId}.games`. QR pairing, Remote Play, and other activity entry points are unchanged.


### Android diagnostic fixture seeding — 2026-11-18
- The #44129928356 Android 15 run passed the launcher singleTop/background resume check; the ActivityRecord count stayed at one and Android reported that the existing launcher task was brought to front. The final failure was only test fixture setup: shell redirection while seeding `launcher.previous.ndjson` failed with "No such file or directory" and toybox `printf` argument diagnostics.
- Reworked `ensure_previous_diagnostic_journals()` to use the exact absolute `/data/user/0/{package}/files/diagnostics` path (matching Android `getFilesDir()`) and create the directory plus write the Base64 fixture in one shell command using `echo | base64 -d`. This affects only emulator test data preparation, not app files or diagnostics storage behavior.


### Android 15 UI hierarchy dump resilience — 2026-11-18
- The #44131983988 Android 15 run reached the late game/session phase and failed at `uiautomator dump` with process exit 137. Its output explicitly said the hierarchy had already been dumped to `/sdcard/wayward-main-activity-game.xml`; the failure was the dumper subprocess being killed after writing, not an app/Activity failure.
- Updated `dump_ui` to remove the old remote XML before each attempt, run the dumper, pull the newly produced file even when the dumper exit code is non-zero, and validate the XML root and at least one node before accepting it. If the process is killed after producing a valid fresh hierarchy, the test records a warning and uses that verified current-screen hierarchy; otherwise it retries once and fails with both dump and pull diagnostics. This does not accept stale XML or weaken any screen, Task, or Activity assertion.


## ④ PC 외부 테스트 QR 공인 IPv4 호환성 — 2026-11-18
- PC 읽기 전용 계약 확인: `diethoon/wayward-remote-host` branch `work/remote-play-ux-v1-20261115`, HEAD `b8c1047a42fec078c399f519a85557cb38b16696`. 발급 스크립트는 `/v1/host/qr?external=true` 전체 서명 매니페스트를 사용하며, exact same `baseUrl`/`externalBaseUrl`, root HTTPS URL, 명시적 IPv4 port, active TCP mapping, internalPort 8765, exact externalAddress/port, `wayward-auto-*` ownership, RFC1918 localAddress를 요구한다.
- Android의 일반 `normalizeBaseUrl()`은 계속 RFC1918 private IPv4만 받는다. public base URL은 두 서명 URL 필드가 완전히 같고 위 PC mapping tuple이 검증된 외부 테스트 QR에서만 허용한다. existing globally-routable IPv4 validator는 RFC1918, CGNAT, loopback, link-local, documentation, benchmarking, multicast/reserved 범위를 계속 거부한다.
- 검증된 외부 매핑 튜플을 Host 저장 JSON에도 유지해 재시작 후 `fromStoredJson()`가 다시 확인한다. 기존 Host P-256 signature, Host ID, 공개키 fingerprint, certificate SHA-256 pin, nonce/expiry 검증은 유지한다. 정상 LAN QR은 private baseUrl과 선택적 distinct external fallback을 그대로 사용한다.
- endpoint 후보 생성 함수를 분리해 동일한 external-test `baseUrl`/`externalBaseUrl`에는 요청을 한 번만 하고, LAN + 별도 signed external endpoint에는 기존 순서대로 fallback한다. TLS/pin failure 및 POST body 전송 후 재시도 금지는 변경하지 않는다.
- Android 15 instrumentation에 PC 형식의 P-256 서명 외부 QR 생성 및 파싱/저장 재검증, signature tamper, 매핑 상태/프로토콜/소유권/포트/주소 오류, RFC1918 local target, reserved/CGNAT 주소, URL 오염, endpoint dedupe 검증을 추가했다.
- 실제 PC Host와 별도 모바일 데이터망 페어링은 실기기/외부망 접근 없이는 입증할 수 없으므로 별도 미검증 항목으로 유지한다.


### External QR mapping defensive-copy compile fix — 2026-11-18
- Latest external QR contract commit `e8f69f6` failed Java compilation because `new JSONObject(String)` throws checked `JSONException` inside the `RemoteHostConnection` constructor. Retained the defensive deep copy and converted malformed mapping JSON into a clear `IllegalArgumentException`; no contract or validation semantics changed.


### Android 15 diagnostics fixture quoting + external IPv4 reserved-range filter — 2026-11-18
- The #44139876260 run passed the complete game-session sequence (same-profile reuse, two independent URI-keyed game tasks, original WebView return, BACK navigation, launcher home restoration, and exactly one canonical Wayward Recents task). It then failed while seeding deterministic previous diagnostic journals: `adb shell run-as ... sh -c` received the pipe/redirection as extra command arguments, so the inner `mkdir` received no path.
- Fixed the fixture writer by creating the diagnostics directory with a direct `run-as mkdir -p` call and shell-quoting the complete `sh -c` payload (including its separately quoted Base64 and output path). This preserves app-scoped access and correctly pipes the seeded NDJSON into the exact previous-journal file.
- Hardened the external QR public IPv4 filter to reject the deprecated `192.88.99.0/24` 6to4 relay anycast block and added test addresses `192.88.99.1` and `.2`. The IANA IPv4 Special-Purpose Registry marks this deprecated block as non-globally reachable; the rest of the existing RFC1918/CGNAT/link-local/documentation/reserved filters are unchanged.


### Public IPv4 external bootstrap QR compatibility — 2026-11-18
- Cross-checked the Android parser against the actual PC branch `work/remote-play-ux-v1-20261115`: `HostQrCodeGenerator.CreatePayload(..., useBootstrap: true)` deliberately emits only the four bootstrap fields `protocolVersion`, `baseUrl`, `certificateSha256`, and `qrNonce` for both LAN and external test QR images. The external test tool validates the full PC-signed manifest and its active Wayward-owned TCP mapping before generating the compact QR.
- Android's `normalizeBootstrapBaseUrl` was still restricted to RFC1918 IPv4, so it rejected the PC-generated public-IP external bootstrap QR before reaching the pinned TLS manifest fetch. It now accepts either a valid RFC1918 LAN IPv4 or a strictly validated globally routable HTTPS IPv4 with an explicit port. After fetch, `fromManifestJson` still requires exact base URL, certificate pin and nonce matches; `fromQrJson` verifies the Host signature and full external mapping tuple (active TCP, Wayward auto ownership, local RFC1918 address, internal 8765, exact external address/port).
- Added Android instrumentation coverage for successful public-IP compact-bootstrap → pinned-manifest validation, altered external address rejection, and deprecated 192.88.99 reserved-range rejection. No PC code was changed.


### Public bootstrap IP precondition correction — 2026-11-18
- Follow-up source review found the public-IP acceptance branch had been added to the bootstrap URL normalizer's return path but the earlier guard still required RFC1918 only. Updated that guard to accept exactly one of the two validators: RFC1918 private IPv4 or globally routable public IPv4. Special-purpose/reserved, private, CGNAT, link-local and non-numeric hosts remain rejected.


### Journal fixture contract aligned with safe adb shell quoting — 2026-11-18
- Updated the static SAF/diagnostics contract after the Android 15 run confirmed that `adb shell run-as ... sh -c` requires a quoted complete shell payload. It now asserts the direct app-scoped `run-as mkdir -p` and quoted writer separately instead of requiring the old single shell compound command.


### ② 진단 ZIP 실패 재시도 — 2026-11-18
- 진단 버튼은 기존 `*.zip.upload-pending` 마커가 있으면 새 ZIP을 생성하지 않고 해당 ZIP을 재사용해 Cloudflare에 재전송한다. 업로드가 이미 실행 중인 중복 터치는 새 요청이나 새 공유 ZIP을 만들지 않고 안내한다.
- 업로더는 서버가 `ok=true`와 유효한 `submissionId`를 반환한 경우에만 재시도 마커를 삭제한다. 네트워크, 타임아웃, HTTP/서버 실패는 ZIP을 cache에 남기고 fsync한 retry 마커를 유지한다.
- cache pruning으로 ZIP이 사라지고 마커만 남으면 stale marker를 지우고 새 ZIP을 생성한다. 기존 Android 공유 시트는 업로드와 동일한 ZIP을 계속 사용한다.


### ② 진단 ZIP 재시도 런타임 계측 — 2026-11-18
- Added `DiagnosticsBundleUploaderInstrumentedTest`: creates a private cache ZIP fixture, asserts no pending marker initially, simulates failed upload by writing the pending marker, verifies the exact same filename is selected for retry, and verifies the marker disappears only when the success-path cleanup is called. The test always removes its own fixture.


### ③ Developer QR image memory safety — 2026-11-18
- Replaced full-resolution `InputImage.fromFilePath()` decoding with a bounded decode: read dimensions only first, reject invalid or extreme (>100,000 px) source dimensions, choose power-of-two sampling to cap output to 4096 px per side and 8 million pixels, preserve EXIF rotation, then decode and pass the sampled bitmap to ML Kit.
- The temporary bitmap is recycled after ML Kit success/failure, after optional scanner module setup fails, or if the Activity is finishing. The selected URI and QR payload are never logged or persisted.
- Added Android instrumentation covering standard image no-resize, 12k×8k sampling and pixel ceiling, 4096-square source sampling, zero dimensions and extreme dimensions.
