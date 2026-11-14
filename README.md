# Wayward Launcher — Official 1.0.6

공식 배포 전용 저장소입니다. 개발용 diethoon/wayward 저장소와 독립적으로 유지합니다.

## 최초 배포 범위
- SAF를 통한 게임 폴더 등록
- 등록된 게임별 독립 Android 문서 세션
- 세션별 WebView와 로컬 게임 origin 분리
- 게임 원본 파일을 직접 수정하지 않는 읽기 전용 실행
- 게임 세션 종료 후 런처로 복귀
- Android 네이티브 Google Identity Services를 통한 Drive App Data 권한 요청

이미지 출력은 런처 홈의 명시적 모드 스위치로 제어합니다.
- 기본 모드: 이미지팩만 사용. 모든 등록된 원격/로컬 이미지 서버를 중지하고, 선택한 게임 폴더의 로컬 이미지팩만 사용합니다.
- 이미지 생성 서버 실행: 사용자가 직접 눌렀을 때만 런처 내장 ImageGen 서버를 실행합니다. 실행 중에는 인게임 `/images/*` 요청이 생성 이미지 캐시로만 가며, 이미지팩 및 원본 CDN fallback을 사용하지 않습니다.
- NovelAI 경로 고정: 이미지 생성 버튼과 Image Studio 게임 세션은 모두 백엔드 엔진 및 게임 내 EasyGen 설정에서 NovelAI만 사용합니다. 게임 설정은 프롬프트·해상도 등 생성 옵션을 바꿀 수 있지만 공급자를 바꾸거나 런처 토큰을 변경·삭제할 수 없습니다. 서버나 NovelAI 요청이 실패하면 다른 외부 주소로 우회하지 않고 실패합니다.
- NovelAI 토큰은 기존 Android Keystore AES-GCM 키와 암호화 파일을 그대로 사용해 앱 업데이트·세션 전환·재부팅 후에도 유지합니다. 사용자가 설정 화면에서 명시적으로 삭제할 때만 지우며, 앱 데이터 삭제/앱 제거 또는 기기 변경은 별도입니다. 기존 1.0.5의 Cloudflare/외부 공급자 기록은 첫 읽기 때 토큰만 남기고 자동 정리합니다.
- 버튼 디자인: 일반 런처 버튼과 NovelAI 설정 액션, 이미지 모드 버튼을 모두 같은 반투명 유리 재질로 통일했습니다. 하늘색 그라데이션 대신 투명한 흰색 유리 표면, 얇은 반사 테두리, 부드러운 상단 하이라이트와 자연스러운 그림자를 사용합니다. 선택된 이미지 모드는 낮은 그림자와 안쪽 림으로 음각 처리됩니다. 서버 실행과 이미지팩 버튼은 모두 76dp로 높이를 통일합니다.
- 서버 실행 버튼 안쪽 오른쪽 톱니바퀴 아이콘: 별도 버튼이나 그리드 없이 아이콘만 표시하며, 터치하면 NovelAI Persistent API Token 설정 화면을 엽니다. 아이콘이 아닌 버튼 표면을 터치하면 서버를 실행/중지합니다. `image.novelai.net/user/subscription` 조회로 토큰을 확인하며, 이미지 생성 요청은 보내지 않습니다. 검증된 토큰은 Android Keystore 기반 AES-GCM 암호화 저장소에 보관합니다. 토큰이 없는 상태에서 서버 타일을 누르면 같은 설정 화면으로 이동하고, 저장 성공 후 요청했던 서버 시작을 이어갑니다. 톱니바퀴에서 토큰만 저장하면 서버는 자동 시작하지 않습니다.
- 게임 세션을 여는 것만으로 이미지 생성 서버가 자동 실행되지는 않습니다.
- Cloudflare Worker/FLUX 및 외부 이미지 서비스 등록·자동 선택 코드는 제거했습니다. 이미지 생성 경로는 NovelAI 전용이며, ComfyUI 연결은 별도 런타임 모드로만 유지합니다. 집 PC 원격 서버 실행 UI는 후속 작업에서 같은 서버 전환 관리자에 연결합니다.
- 원격 진단 업로드는 자동 실행하지 않습니다.

## 이미지 서버 모드와 NovelAI
이미지 생성 서버는 사용자가 홈 화면의 **이미지 생성 서버 실행** 타일을 눌렀을 때만 시작합니다. 서버 타일을 다시 누르거나 **이미지팩만 사용**을 누르면 서버 중지로 전환됩니다. 모드 전환은 기존 원격 서버 중지 → 로컬 이미지 생성 서버 중지 → 선택한 새 서버 시작 순서로 직렬화합니다. 새 서버 시작이 실패하면 이미지팩 전용 모드로 복귀합니다.

NovelAI Persistent API Token은 NovelAI 웹사이트의 Settings → Account → Get Persistent API Token에서 발급합니다. 런처 홈의 이미지 생성 서버 버튼 내부 오른쪽 톱니바퀴에서 토큰을 입력하면 구독 정보 조회로 인증을 확인한 뒤 이 기기의 Android Keystore 암호화 저장소에 기록합니다. 토큰 검증은 이미지 생성 API를 호출하지 않습니다. 토큰이 등록되지 않은 상태에서 서버 실행 타일을 누르면 토큰 설정 화면을 먼저 열고, 검증·저장에 성공하면 서버 시작을 자동으로 이어갑니다. 톱니바퀴를 통해 설정 화면을 직접 연 경우에는 토큰을 저장해도 서버를 자동 시작하지 않습니다.

서버 OFF 상태에서는 기존 게임 설정에 외부 생성 주소가 남아 있어도 인게임 Image Studio 브리지가 해당 주소를 probe하거나 호출하지 않습니다. 서버 ON 상태에서는 생성 API를 런처 내장 NovelAI ImageGen으로만 연결하고 다른 공급자나 이미지팩/CDN fallback으로 우회하지 않습니다.

## Google Drive CloudSave
Android 런처 안에서는 기존 CloudSave 0.4.4의 Vercel 팝업 URL을 네이티브 Google Identity Services AuthorizationClient로 중계합니다. 요청 범위는 Drive App Data(drive.appdata)이며, 결과 토큰은 JS에 전달하기 위한 프로세스 메모리의 nonce별 일회성 결과로만 보관합니다. 게임 모드의 기존 암호화·저장/불러오기 로직은 수정하지 않습니다.

일반 브라우저에서는 기존 Vercel OAuth 팝업 경로를 그대로 사용합니다. Android에서는 기존 Web OAuth 클라이언트와 별도로 같은 Google Cloud 프로젝트에 Android OAuth 클라이언트를 등록해야 합니다.

- Debug: 패키지명 `com.wayward.launcher.official.debug`, SHA-1 `01:A6:82:34:B0:42:25:E9:86:39:99:4C:DC:91:66:D2:FF:6D:25:76`
- ZIP 모드 파일 선택 결과가 Google 인증 결과 처리와 충돌하던 문제를 수정했습니다.
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
