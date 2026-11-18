# FAITH 3.0.0 SAF 실기기 인수인계

## 빌드 식별

- 저장소: `diethoon/wayward-launcher-official`
- 브랜치: `fix/android-qr-pairing-20261116`
- Android 패키지: `com.wayward.launcher.official`
- 버전: `1.0.7` (versionCode 107)
- 대상: Android 15 / API 35
- 원본: `diethoon/wayward:main/Wayward_Faith_v3.0.0.html`
- 원본 Git Blob SHA: `ac6b44ccb75584383ad2875f4f36a55138c228cf`
- 최종 빌드 코드 커밋: `fb1ad15d6e90b8cf89ade0918680a0df409e7762`
- Android validation run: [#659 성공](https://github.com/diethoon/wayward-launcher-official/actions/runs/44351299795)
- Gateway validation run: [#153 성공](https://github.com/diethoon/wayward-launcher-official/actions/runs/44351299775)
- APK artifact ID: `14448956114` (2026-12-02 16:49:35 UTC 만료)

이 문서는 실기기 테스트 절차다. 자동 테스트와 빌드 성공은 실제 WebView·DocumentsUI·SAF Provider 런타임 성공을 의미하지 않는다.

## 1. 기기 및 사전 준비

1. Android 15(API 35) 실기기를 준비한다.
2. Play 스토어에서 Android System WebView와 Chrome을 최신 버전으로 업데이트한다.
3. 설정 → 애플리케이션 → Android System WebView에서 버전을 기록한다. 기기에서 WebView 제공자 선택 메뉴가 보이면 개발자 옵션의 WebView 구현 메뉴에서도 실제 제공자를 확인한다.
4. 기존 Wayward Launcher를 설치한 상태라면 앱 데이터 삭제 없이 업데이트 설치한다.
5. FAITH 3.0.0 원본을 준비하고, 그 안에 실제로 등록 가능한 이미지팩이 포함된 폴더를 준비한다. 예시 구조는 다음과 같다.

```text
Faith Library/
└── image-packs/
    ├── character-a/
    │   └── catalogue.js
    └── character-b/
        └── catalogue.js
```

실제 이미지팩 JavaScript에는 FAITH가 요구하는 `/* WAYWARD_FAITH_IMAGE_PACK */` 마커와 유효한 이미지팩 데이터가 있어야 한다. 임의의 테스트 JavaScript를 정상 이미지팩으로 판정하지 않는다.

## 2. 설치 및 기본 확인

1. 제공된 Release APK를 설치한다.
2. 앱 정보에서 패키지와 버전을 확인한다.
3. Wayward 게임을 실행하고 FAITH 3.0.0 화면이 정상적으로 표시되는지 확인한다.
4. 일반 파일 선택 기능도 별도로 한 번 실행한다. 일반 파일 선택이 폴더 선택으로 잘못 전환되면 실패다.

기대 결과: 앱이 실행되고 게임 화면이 표시되며, 일반 파일 선택 경로는 기존 WebView 파일 선택기로 유지된다.

## 3. FAITH 이미지팩 폴더 업로드

1. FAITH 화면에서 **image-packs 폴더 선택**을 누른다.
2. Android DocumentsUI가 열리면 위에서 준비한 `Faith Library` 폴더를 선택한다. `image-packs` 하위 폴더가 포함된 상위 폴더를 선택한다.
3. DocumentsUI의 선택 완료를 승인한다.
4. 게임으로 돌아온 뒤 이미지팩 스캔 완료 메시지를 확인한다.
5. FAITH 이미지팩 목록 또는 해당 이미지팩을 사용하는 화면에서 등록 여부를 확인한다.

기대 결과:
- 선택한 루트와 하위 경로가 유지된다.
- `image-packs/` 아래의 JavaScript 파일만 스캐너 대상이 된다.
- 유효한 마커와 데이터가 있는 이미지팩이 등록된다.
- 잘못된 마커, 빈 파일, 경로 밖 파일은 등록되지 않는다.
- 동일 ID 충돌은 성공으로 처리되지 않고 오류가 표시된다.

실패 판정: 폴더 선택이 일반 다중 파일 선택으로 열림, 스캔 결과 0개인데 유효 팩이 존재함, 경로가 누락됨, 파일 읽기 실패가 성공으로 표시됨, 또는 중복 ID가 정상 등록됨.

## 4. 비동기 파일 목록 및 연속 선택

1. 이미지팩 폴더를 선택해 스캔을 시작한다.
2. 스캔 완료 전에는 UI가 입력을 비활성화하는지 확인한다.
3. 스캔 완료 후 같은 버튼으로 다른 폴더를 선택한다.
4. 두 번째 폴더의 팩이 정상적으로 스캔되는지 확인한다.
5. 앱을 종료하고 다시 실행한 뒤 폴더 선택을 반복한다.

기대 결과: 첫 번째 스캔의 파일 읽기가 완료되고, 두 번째 선택은 새 FileList로 처리된다. 두 번째 선택이 이전 목록을 재사용하거나 이전 스캔의 파일을 새 선택으로 오인하면 실패다.

## 5. SAF 권한 및 복원

1. 첫 폴더 선택에서 읽기 권한을 승인한다.
2. 앱을 완전히 종료하고 다시 실행한다.
3. 폴더를 다시 선택해 파일 목록과 스캔이 정상 동작하는지 확인한다.
4. 권한 요청이 표시되는 별도 흐름에서는 취소 버튼을 눌러 취소 결과를 확인한다.
5. 읽기/쓰기 권한을 요청하는 흐름에서는 실제로 선택한 동일 폴더에 대한 권한이 승인되었는지 확인한다.

기대 결과:
- 승인한 Tree URI의 읽기 권한은 앱 프로세스 재생성 후에도 유지된다.
- 권한이 없거나 취소된 경우 성공으로 응답하지 않는다.
- 쓰기 권한이 없는 Tree에서 쓰기 작업은 거부된다.
- 다른 폴더를 선택한 결과를 이전 DirectoryHandle의 권한으로 오인하지 않는다.

## 6. showDirectoryPicker 별도 확인

FAITH의 `webkitdirectory` 업로드와 별개로, 앱에서 DirectoryHandle 기반 폴더 기능(예: Image Studio의 폴더 선택·파일 관리)을 실행한다.

1. 해당 기능을 실행해 폴더를 선택한다.
2. 하위 폴더와 파일 목록이 표시되는지 확인한다.
3. 기존 파일을 읽어 내용을 확인한다.
4. 테스트용 파일을 생성하고 문자열 또는 작은 파일 데이터를 쓴다.
5. seek 후 부분 쓰기와 truncate가 반영되는지 확인한다.
6. 저장 후 다시 읽어 최종 내용을 확인한다.
7. 별도 테스트 파일에 데이터를 쓴 뒤 저장 전에 취소/abort하여 원본이 바뀌지 않는지 확인한다.

기대 결과: 파일 읽기·쓰기 결과가 일치하고, abort는 미저장 버퍼를 폐기한다. 쓰기 권한이 거부되면 파일이 변경되지 않아야 한다.

주의: 현재 쓰기 어댑터는 32 MiB 메모리 버퍼 기반의 제한적 FileSystemWritableFileStream 호환 구현이다. 브라우저 네이티브 WritableStream 전체 구현 또는 무제한 스트리밍으로 간주하지 않는다.

## 7. 취소·오류 테스트

- DocumentsUI에서 폴더 선택을 취소한다.
- 읽기 권한이 없는 상태에서 파일 읽기를 시도한다.
- 쓰기 권한이 없는 상태에서 쓰기를 시도한다.
- 32 MiB를 초과하는 파일을 읽거나 쓰려고 시도한다.
- 허용된 폴더 깊이·파일 수 제한을 넘는 테스트 폴더를 선택한다.

기대 결과: 취소·권한 오류·크기 제한·탐색 제한이 구분되어 표시되고, 성공으로 위장하지 않는다. 제한 초과 파일은 메모리 전체를 무제한 적재하지 않고 명확한 오류로 거부된다.

## 8. 로그 수집

실패 시 다음 자료를 같은 시각 기준으로 수집한다.

- WebView 콘솔 로그 및 JavaScript 예외
- Android Logcat의 `WaywardImagePackSAF`, `WaywardImagePackPicker`, `chromium` 관련 로그
- `onShowFileChooser()` 호출 및 결과
- SAF Activity 결과 코드와 URI 권한 상태
- 디렉터리 탐색 파일 수·깊이·오류 단계
- 파일 읽기/쓰기 성공 여부와 크기
- 원본 input/change 이벤트 발생 여부
- 원본 스캐너 완료 메시지 및 등록된 팩 수

예시:

```bash
adb logcat -v time -s WaywardImagePackSAF WaywardImagePackPicker chromium
```

로그에 인증 토큰, 파일 본문, 개인 문서명 또는 민감한 전체 경로를 포함하지 않는다. 공유 전 민감정보를 제거한다.

## 9. 실기기 합격 기준

아래 항목을 모두 확인해야 실기기 검수를 통과한다.

- [ ] Android 15 / API 35에서 앱 실행
- [ ] WebView 제공자와 버전 기록
- [ ] 일반 파일 선택 정상
- [ ] FAITH 폴더 선택이 SAF Tree Picker로 연결
- [ ] 중첩 `image-packs/` 경로 유지
- [ ] 원본 FAITH 스캐너가 실제 이미지팩 등록
- [ ] 두 번째 폴더 선택 정상
- [ ] 앱 재실행 후 SAF 읽기 권한 복원
- [ ] 권한 취소·거부가 성공으로 처리되지 않음
- [ ] DirectoryHandle 별도 읽기/쓰기 정상
- [ ] seek/truncate/abort 동작 확인
- [ ] 크기 제한 및 파일 읽기 오류가 명확히 표시

## 10. 최종 릴리스 기록

- Android 검증 run: [최종 Actions 실행](https://github.com/diethoon/wayward-launcher-official/actions)
- 최종 빌드 코드 커밋: `fb1ad15d6e90b8cf89ade0918680a0df409e7762`
- Release APK: `app-release.apk`
- Debug APK: `app-debug.apk`
- Release APK SHA-256: `b3d404be82c439f136de0f1cce44caf964f535a565d8c857d153f9dd80ea7656` (9,289,757 bytes)
- Debug APK SHA-256: `aae97cbb047f0d389ca3444590b20973a0ea4687356b62703919f60ee28ad55a` (10,934,320 bytes)
- APK ZIP SHA-256: `c0cff6b4c17b4ea6b97603e75d246faed6dc3483dc97a70f395adf894e47b6c9` (7,745,650 bytes)
- Artifact URL: [Download APK artifact](https://github.com/diethoon/wayward-launcher-official/actions/runs/44351299795/artifacts/14448956114)
- Gradle: `testDebugUnitTest assembleDebug assembleRelease` 성공; 81 tasks (80 executed, 1 up-to-date)
- APK Signature Scheme v2: verified; signer certificate SHA-1 `D9BE7443EB247D27C958CC4250D7F5D1E078F740` matches registered Android OAuth fingerprint
- Release 인증서 SHA-1: `D9BE7443EB247D27C958CC4250D7F5D1E078F740`
- Android OAuth 등록 지문 일치: CI 서명 검증 결과로 확인
- 실기기/에뮬레이터 실행: 이 작업 범위에서 수행하지 않음. API 35 emulator step was skipped by the branch condition; KVM setup alone is not runtime evidence.
