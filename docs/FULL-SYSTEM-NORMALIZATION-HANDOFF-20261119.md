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
