# PC + Android 통합 정상화 — Android 인수인계 / 기준점

작성: 2026-11-19 (Asia/Seoul)  
상위 작업: 전체 기능 통합 포렌식 및 정상화 v1.0

## 1. 작업 격리 및 기준점

- 저장소: `diethoon/wayward-launcher-official` (public)
- 기준 구현 브랜치: `work/android-file-engine-v2.1-complete-20261119`
- 기준 HEAD: `b5203bb119c7c8b1cc16cd9d538d4aea79c6e218`
- 새 격리 작업 브랜치: `work/full-system-normalization-20261119`
- 새 브랜치 생성 기준: 위 기준 구현 브랜치 HEAD와 동일
- 저장소 기본 브랜치 `main`: `ff64801c8b676d25c6e9f6a7c27fe366241b4906`
- main 대비 비교: 작업 기준 브랜치 589 commits ahead / 49 behind, merge-base `46b98afcd0c1ad588c2d395eafd930857cf0aaa6`. 상당히 분기된 브랜치이므로 main으로 재기반화하거나 병합하지 않는다.
- 원격 Git 스냅샷: GitHub API에서 읽은 커밋 트리 기준. 원격 API는 미커밋 로컬 변경을 보여주지 않으므로 실제 로컬 working-tree clean 여부는 확인 불가이며 clean이라고 간주하지 않는다.
- 로컬 checkout/worktree: 이 인수인계 작성 시점에는 없음. PC 저장소와 분리된 디렉터리에 clone한다.
- Android package: `com.wayward.launcher.official`; versionName `1.0.7`; versionCode `107`; compileSdk/targetSdk 35; minSdk 29; Java 17.
- 기준 파일 Git blob SHA:
  - `app/build.gradle`: `bdbcdf1886667ce14e0f7fbf6bc818ef0871b641`
  - `docs/android-file-access-engine-v2.1.md`: `e922a1cb7b33ea5ed1b07d46d8250cff205cb0cb`
  - `docs/remote-play-native-integration.md`: `574eb4c92a9d0fe4ba9dc1bd607da40263b87281`
- Git blob SHA는 SHA-1 object id이며 파일 SHA-256과 동일하지 않다. 원본 파일 SHA-256/바이트 크기는 sandbox checkout 후 계산한다.

## 2. 기존 기능과 통합 관련 알려진 상태

- Android branch에는 RemoteHost QR identity/TLS pin 검증, P-256 Keystore proof, Manager approval receipt, 단기 session token, mDNS rediscovery, 승인된 루트 기반 파일 탐색, resumable upload/download, app-private image cache, Remote ComfyUI client, Remote pairing/file-manager UI 등이 포함되어 있다. 이는 코드 존재 근거이지 실제 PC 연결 PASS 근거는 아니다.
- SAF File Access Engine v2.1은 32-entry batch, 단일 in-flight ack, cancellation signal/Future/Cursor 관리, 최대 100,000 files / 200,000 entries / depth 128, 32 MiB 파일 제한 등을 문서화한다. 단위/합성 테스트는 실제 DocumentsProvider의 동작 증명이 아니다.
- Android 기본 버전 1.0.7 / 107. Release signing은 환경변수 keystore 경로·암호·alias 설정 여부에 따라 결정된다. APK 서명 키를 바꾸거나 applicationId를 변경하지 않는다.
- 기존 원격 통합 문서는 Home 화면 UI가 과거에 의도적으로 보류되었다고 기록하지만, 현재 branch에 실제 RemotePairingActivity/RemotePcFileManagerActivity 및 UI 진입점이 있는지 코드에서 다시 확인한다.
- PC handoff 기준으로 Android `RemoteGameImageApiClient`는 전체 ImageGen 0.8.4 native API가 아닌 제한된 legacy route allowlist를 사용하는 것으로 기록되어 있다. 현재 HEAD에서 실제 파일/route를 재확인하고 /api 및 /images proxy parity를 테스트한다.
- 기존 Remote Play 문서에는 2026-11-16의 외부 endpoint fallback 설명이 남아 있으나, 현재 PC handoff는 router mapping/public endpoint 기능을 제거했다고 기록한다. 현재 실제 코드의 QR schema/endpoint fallback과 PC Host protocol을 대조해 오래된 문서와 구현 중 어느 쪽이 맞는지 판정한다. 임의로 WAN endpoint를 허용하지 않는다.
- 실물 Samsung Android 15 + 실제 Windows Host 연결/QR/approval/재접속/파일 전송/게임 실행/ComfyUI 추론은 아직 PASS로 간주하지 않는다.

## 3. 테스트 및 환경

- CI 워크플로: `.github/workflows/android-validation.yml` 등 현 branch에서 workflow 목록 및 최신 실행 상태를 재확인한다.
- 이전 컨텍스트에 남은 과거 APK/CI 결과는 현 HEAD의 증거가 아니다. 현 branch HEAD 기준 Debug/Release build, unit/contract, signature, emulator 및 artifact SHA를 새로 기록한다.
- 실제 기기 검증이 금지/비활성화된 워크플로 정책이 있는지 확인하고, 사용자 승인 없이 정책을 해제하거나 실제 장비를 원격 제어하지 않는다.
- Android device access, physical SAF providers, Android 15 behavior, installed app signature continuity는 각각 별도 판정한다.

## 4. 초기 기능 판정

| 영역 | 시작 판정 | 근거/다음 단계 |
|---|---|---|
| QR scan / PC approval | PARTIAL | Native crypto/client code exists; actual cross-device handshake required |
| Permanent pairing / session recovery | PARTIAL | Host trust store + device key exists; PC restart/reconnect E2E needed |
| mDNS / network changes | PARTIAL | Discovery code exists; real Wi-Fi/roaming validation blocked |
| Remote file browse | PARTIAL | Host client + SAF UI exists; real provider and permissions required |
| Upload/download/resume | PARTIAL | Offset/hash code exists; cross-repo transfer E2E needed |
| Game registration/launch | BLOCKED | Remote flow must be exercised with actual PC folder/game |
| ImageGen proxy | PARTIAL | Legacy adapter documented; full API parity and real inference not proven |
| ComfyUI | PARTIAL | Native client exists; actual Host permissions/model output unverified |
| Updates | BLOCKED | GitHub Releases, signature/hash/install/rollback must be audited |
| Diagnostics | PARTIAL | Android diagnostics exist; common request ID/schema and secret masking must be audited |

## 5. 보존 조건

- Existing SharedPreferences, Keystore keys, pairing metadata, CloudSave auth/session material, game profiles, WebView origins/storage, SAF persisted grants, image packs, saves and user folders are not reset or migrated without a proven need and explicit migration plan.
- Never put Host bearer tokens, device private keys, Google tokens, cookies, authorization headers or API secrets into WebView JavaScript, logs, diagnostic ZIPs or QR payloads.
- Host certificate pin and signed identity checks must remain mandatory.
- Unsupported file operations must be hidden/disabled, not shown as successful no-ops.

## 6. PC 대응 문서

PC 저장소의 `docs/FULL-SYSTEM-NORMALIZATION-HANDOFF-20261119.md`와 함께 유지한다. API 계약 표 및 E2E 시나리오 결과는 양쪽 문서에 동일한 판정과 evidence ID로 기록한다.

## 7. 기록 정책

각 검사에 실행 명령, commit SHA, Android SDK/Gradle/JDK, 종료 코드, 로그/산출물 SHA-256, 기기 모델/API level을 기록한다. 에뮬레이터·실기기 미실행은 BLOCKED 또는 NOT RUN으로 표시한다. 대형 파일은 4096-byte 바이너리 청크로 EOF까지 검사하고 전체 출력하지 않는다.


## 8. 1차 포렌식 증거 및 현재 판정 (2026-11-19)

### 기준점 / 실행 환경 갱신

- 인수인계 문서 초기 커밋: `8a8515eab47d5f658a0b79263ea7746c3d16bbc0`; 부모 코드 HEAD `b5203bb119c7c8b1cc16cd9d538d4aea79c6e218`. 코드 변경 없이 문서만 추가했다.
- 현재 작업 브랜치 HEAD: `8a8515eab47d5f658a0b79263ea7746c3d16bbc0`; tree `9de2dd37ce53cec4a2edc330e8399b6a17f5bff0`.
- 별도 checkout: `/vercel/normalization-android`; branch `work/full-system-normalization-20261119`; 테스트 생성물 정리 후 `git status --short` clean.
- 기준 파일 SHA-256:
  - `app/build.gradle`: `aa025153b2df676234d967291c4ce82bb5f8fc4ea2adc9b78c9250c1585f9dcb`
  - `RemoteHostClient.java`: `5ae8be37f4f2c63e9e803abde58a0bee48b0fdce85fe9a844184263b44f40bdf`
  - `RemoteGameImageApiClient.java`: `b5e09955b3ddf25f2b51afde30f76ec38a84b1d33473818dccf05e848e035851`
- Android SDK/Gradle/JDK/ADB는 이 sandbox에 설치되어 있지 않아 로컬 Gradle 빌드는 실행하지 않았다. 로컬 Node v24.22.1 및 Python 3를 사용했다.

### 실제 실행 결과

| 검사 | 결과 | 증거 |
|---|---|---|
| 모든 app assets JavaScript syntax | PASS | `node --check app/src/main/assets/*.js` |
| Node contract tests | PASS | tests/*.test.cjs, tests/*.test.mjs, scripts/test-*.cjs 16개 실행 |
| Python compile / parser tests | PASS | py_compile 성공, Android 15 Recents parser 4 tests OK |
| Android Debug/Release CI build | PASS | Actions run #44430996771, 기준 코드 commit `b5203bb...` |
| CI contract / policy tests | PASS | Remote Play, OAuth, CloudSave, SAF, ImageGen mode, diagnostics 등 모든 실행 step 성공 |
| APK artifact | PARTIAL | artifact `wayward-launcher-official-apks`, id `14485732844`, 9,535,651 bytes; 이 검사에서 artifact를 다운로드/설치하지 않음 |
| Android 15 emulator | BLOCKED | CI에서 KVM/emulator/native crypto/UI steps 32–35 skipped |
| 실제 PC ↔ Android QR/승인/파일/게임 | BLOCKED | 물리 PC/Android 통합 세션 미실행 |
| Production APK signing continuity | BLOCKED | workflow는 release keystore secrets가 없으면 Release APK를 unsigned로 생성하도록 설계됨. 현재 run에서 production keystore 사용 여부를 별도 증명하지 않음 |

### 확인된 구현과 결함

1. **QR / 페어링 / 영구 신뢰 / 세션**
   - Android `RemotePairingActivity`는 QR에서 Host identity를 검증하고 PC Manager 승인 후 signed approval receipt를 확인한다.
   - `RemoteHostConnection`은 P-256 Host 공개키 서명, Host ID, public-key fingerprint, TLS certificate SHA-256 pin, QR nonce 및 유효 시간을 검증한다.
   - `RemoteHostStore`는 Host metadata와 device identity를 저장하고, device private key는 Android Keystore에서 사용한다. PC의 영구 registry는 별도 저장소로 유지된다.
   - 세션 토큰은 메모리 기반이며 401 응답 시 세션을 비우고 새 challenge를 수행하는 재인증 경로가 있다. PC Host 재시작 후 실제 기기 재접속은 아직 실행하지 않았다.

2. **네트워크 / mDNS / 외부 접속**
   - `MainActivity`에서 `RemoteHostDiscovery`를 시작한다. NSD TXT는 discovery hint일 뿐이며 저장된 Host ID/key/certificate fingerprint가 일치하고 새 IP의 pinned HTTPS signed manifest 검증이 성공한 경우에만 주소를 갱신한다.
   - **결함 NET-01:** `RemoteHostConnection.normalizeBaseUrl()`은 HTTPS + RFC1918 private IPv4만 허용한다. `RemoteHostClient.endpointCandidates()`도 단일 `host.baseUrl`만 반환한다. 외부 public endpoint, fallback, WAN reconnect는 현재 지원되지 않는다.
   - mDNS 코드는 존재하지만 실제 Wi-Fi 전환/다중 NIC/공유기 변경 환경에서 재탐색과 Host 재연결은 실기기 검증이 필요하다.

3. **원격 파일 관리자**
   - Android PC 파일 관리자 UI는 PC에서 승인된 root를 받아 목록을 표시하고, SAF document picker로 다운로드 대상/업로드 원본을 선택한다. 모바일이 PC의 임의 절대 경로를 Host에 등록하는 API는 사용하지 않는다.
   - 업로드는 최대 512 MiB, 최대 1 MiB chunk, SHA-256/Upload-Offset 확인을 사용한다. uploadId/URI/root/path/size/hash를 저장하고 Host가 보고한 offset부터 재개한다.
   - **결함 FILE-01:** 다운로드 UI는 Range 요청 없이 처음부터 스트리밍하고, 오류 시 partial destination을 삭제한다. 따라서 다운로드 재개는 구현되지 않았다. client의 저수준 Range 전달 가능성과 UI 동작을 구분한다.
   - 원격 파일 다운로드 후 기존 SAF 게임 프로필에 등록하고 게임을 실행하는 실제 흐름은 아직 E2E 미검증이다.

4. **ImageGen / ComfyUI**
   - `ImageGenProxy`의 remote PC 모드는 `RemoteGameImageApiClient`를 호출한다. 이 client는 legacy `/api/image/health`, `/api/pack`, `/api/image/generate`, `/api/image/cancel`, `/api/image-hit`, `/api/batch/*`, `/api/image/status/{id}` 경로만 allowlist로 매핑하며 JSON body는 4 MiB로 제한한다.
   - **결함 IMG-01:** PC의 full `/v1/imagegen/*` gateway를 호출하는 Android client가 없다. 따라서 ImageGen 0.8.4 전체 API/이미지 경로/헤더/대용량 응답 parity는 확인되지 않았다.
   - `RemoteComfyClient`는 status/object-info/queue/workflow/job/image 메서드를 갖지만 call-site scan 결과 `MainActivity`의 `getStatus()`만 호출된다. Android UI에서 workflow submit/job polling/image transfer를 사용하는 call site는 확인되지 않았다. PC의 legacy game-image API를 통한 Comfy backend generation과 직접 Comfy workflow 기능은 별도 항목으로 검증한다.
   - 실모델 로딩/추론/결과 저장/표시는 실물 GPU/PC 연결이 없어 BLOCKED다.

5. **업데이트**
   - **결함 UPDATE-01:** 현재 Android 소스에는 GitHub Releases 조회, stable/beta channel, APK SHA-256 검증, PackageInstaller 기반 업데이트, 설치 결과 확인 및 rollback 구현이 없다. CI는 APK build/artifact upload만 수행한다.
   - Release signing은 선택적 secret 설정이다. production signing key가 사용되지 않으면 workflow 자체가 Release APK를 unsigned로 만들 수 있으므로, 업데이트 구현 시 인증서 SHA-256/SHA-1 continuity를 강제해야 한다.

6. **진단**
   - `DiagnosticsLogger`는 appVersion/SDK 및 redacted NDJSON event를 기록하고 `DiagnosticsBundleExporter`가 Android version/device metadata를 내보낸다. 로그 redaction은 bearer/token/secret/cookie/content URI/absolute path를 대상으로 한다.
   - **결함 DIAG-01:** `RemoteHostClient`는 공통 `X-Request-ID`를 발급/전달하지 않고, PC Host도 동일 ID를 로그에 남기는 공통 middleware가 확인되지 않았다. PC와 Android 이벤트를 하나의 요청으로 묶을 수 없다.
   - PC 진단은 managerVersion의 dotted assembly version을 IP로 오인하지 않도록 예외 처리한다. Android appVersion은 IPv4 치환 규칙을 사용하지 않는다.

7. **API 계약 문서**
   - PC `docs/protocol/openapi-v1.yaml`은 현재 Host status/identity/stop 세 경로만 정의하고 실제 pairing/auth/files/game/comfy/imagegen API를 빠뜨린다. Android call-site 계약표와 함께 갱신이 필요하다.

### 1차 기능 판정 갱신

| 기능 | 판정 | 비고 |
|---|---|---|
| Android 앱 build / contract tests | PASS | CI 및 로컬 Node/Python checks; emulator 제외 |
| QR / 승인 / 영구 pairing | PARTIAL | 코드/contract; 실 PC E2E 미실행 |
| mDNS 재탐색 / network change | PARTIAL | 구현/호출 연결 확인; 실제 Wi-Fi 전환 미실행 |
| 외부 WAN / port mapping | FAIL | private IPv4 only; external endpoint/UPnP/NAT-PMP/PCP 없음 |
| 원격 browse / upload | PARTIAL | UI/코드 및 contract; 실 전송 미실행 |
| 다운로드 재개 | FAIL | UI가 partial 삭제, Range resume 미구현 |
| 게임 등록 / 실행 | BLOCKED | 실제 다운로드→프로필 등록→실행 미검증 |
| Legacy ImageGen game API | PARTIAL | 제한된 route allowlist; PC 연결/실제 생성 미실행 |
| Full ImageGen 0.8.4 API | FAIL | full gateway client 미연결 |
| Direct ComfyUI workflow UI | FAIL | client methods exist; UI call-site absent |
| GitHub Releases updater | FAIL | 미구현 |
| PC↔Android 통합 진단 | PARTIAL | 개별 logger/exporter; common request ID 없음 |
