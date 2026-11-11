# Wayward Launcher — Official 1.0.0

공식 배포 전용 저장소입니다. 개발용 diethoon/wayward 저장소와 독립적으로 유지합니다.

## 최초 배포 범위
- SAF를 통한 게임 폴더 등록
- 등록된 게임별 독립 Android 문서 세션
- 세션별 WebView와 로컬 게임 origin 분리
- 게임 원본 파일을 직접 수정하지 않는 읽기 전용 실행
- 게임 세션 종료 후 런처로 복귀
- Android 네이티브 Google Identity Services를 통한 Drive App Data 권한 요청

ZIP 모드 관리 UI는 최초 배포 범위에서 제외합니다. ImageGen/ComfyUI/NovelAI/FLUX 서버와 공급자 코드, 원격 진단 업로더는 공식 1.0.0 소스에서 제거했습니다. 런처 진단 기록은 기기 내부에만 저장하며 외부 서버로 전송하지 않습니다.

## Google Drive CloudSave
Android 런처 안에서는 기존 CloudSave 0.4.4의 Vercel 팝업 URL을 네이티브 Google Identity Services AuthorizationClient로 중계합니다. 요청 범위는 Drive App Data(drive.appdata)이며, 결과 토큰은 JS에 전달하기 위한 프로세스 메모리의 nonce별 일회성 결과로만 보관합니다. 게임 모드의 기존 암호화·저장/불러오기 로직은 수정하지 않습니다.

일반 브라우저에서는 기존 Vercel OAuth 팝업 경로를 그대로 사용합니다. Android에서는 기존 Web OAuth 클라이언트와 별도로 같은 Google Cloud 프로젝트에 Android OAuth 클라이언트를 등록해야 합니다.

- Debug: 패키지명 `com.wayward.launcher.official.debug`, SHA-1 `01:A6:82:34:B0:42:25:E9:86:39:99:4C:DC:91:66:D2:FF:6D:25:76`
- Release: 패키지명 `com.wayward.launcher.official`, SHA-1 `D9:BE:74:43:EB:24:7D:27:C9:58:CC:42:50:D7:F5:D1:E0:78:F7:40`

두 패키지/서명 조합은 각각 별도 Android OAuth 클라이언트로 등록합니다. Android 클라이언트에는 JavaScript 원본이나 리디렉션 URI를 넣지 않습니다. 일반 브라우저용 기존 Web OAuth 클라이언트와 Vercel 콜백은 변경하지 않습니다.

## 릴리스 서명
GitHub Actions에서 서명된 Release APK를 만들려면 저장소 Actions secrets에 다음 값을 등록합니다.

- `ANDROID_KEYSTORE_BASE64`: release PKCS#12 파일의 Base64 전체 내용
- `ANDROID_KEYSTORE_PASSWORD`: PKCS#12를 열 때 사용하는 내보내기 암호
- `ANDROID_KEY_ALIAS`: `wayward-release`

CI는 업로드된 P12의 크기(2,858 bytes), P12 비밀번호, 개인키-인증서 일치 여부, 인증서 SHA-1을 검사합니다. 이후 Java 17 호환 3DES 방식으로 임시 PKCS#12를 다시 포장하고 JKS로 변환해 서명합니다. 변환 과정에서 키 별칭도 명시적으로 유지합니다. `ANDROID_KEYSTORE_PASSWORD`에는 **P12 내보내기 암호**를 등록합니다. PEM 원본 파일을 만들 때 사용한 암호가 아니라 P12를 만들 때 설정한 암호입니다. 별도 `ANDROID_KEY_PASSWORD` secret은 사용하지 않으며, 기존에 등록했다면 삭제할 필요 없이 그대로 두어도 됩니다.

키 파일과 암호는 저장소 파일이나 코드에 넣지 않습니다. 세 secret 중 하나라도 없으면 CI는 Debug APK와 unsigned Release APK를 생성합니다.

## 기준
- 개발 저장소: https://github.com/diethoon/wayward
- 기준 커밋: 9c52b690229808c9f6b188fcf55c073468b81341
- 기준 경로: android-external-folder-launcher/

## 검증 상태
`.github/workflows/android-validation.yml`은 PR 생성/갱신 및 `main`, `work/**` 브랜치 push 시 자동 실행됩니다. Actions secrets가 모두 등록된 경우 PKCS#12를 임시 JKS로 변환해 Release APK에 서명하고, APK 인증서 SHA-1이 등록된 Android OAuth 지문과 일치하는지 확인합니다. 해당 워크플로에는 Node 문법 검사, 공식 런처 범위 검사, Google OAuth 브리지 계약 테스트, Gradle 8.9 Debug/Release APK 빌드, Android 15 에뮬레이터 시작 검사는 각 ADB 명령의 결과를 파일로 기록하고 단일 검사 명령으로 앱 프로세스와 crash 로그를 확인합니다.

이 워크플로 파일은 작업 브랜치에 반영되어 있습니다. 실제 CI 성공 결과는 아직 확인되지 않았습니다. 또한 Android 15 실기기에서의 Google 계정 승인 및 CloudSave 실제 저장·불러오기는 별도 검증이 남아 있습니다. 해당 검증 전에는 APK를 정식 배포 완료본으로 표시하지 않습니다.
