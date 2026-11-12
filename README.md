# Wayward Launcher — Official 1.0.0

공식 배포 전용 저장소입니다. 개발용 `diethoon/wayward` 저장소와 독립적으로 유지합니다.

## 최초 배포 범위
- SAF를 통한 게임 폴더 등록
- 등록된 게임별 독립 Android 문서 세션
- 세션별 WebView와 로컬 게임 origin 분리
- 게임 원본 파일을 직접 수정하지 않는 읽기 전용 실행
- 게임 세션 종료 후 런처로 복귀
- Google Drive CloudSave의 인증은 별도 서버 없이 Google 공식 인증 API를 직접 사용

ZIP 모드 관리 UI는 최초 배포 범위에서 제외합니다. ImageGen/ComfyUI/NovelAI/FLUX 서버와 공급자 코드, 원격 진단 업로더는 공식 1.0.0 소스에서 제거했습니다. 런처 진단 기록은 기기 내부에만 저장하며 외부 서버로 전송하지 않습니다.

## Google Drive CloudSave 인증

브라우저의 인증 페이지는 다음 주소 하나를 사용합니다.

`https://jeiky224.github.io/cloud/`

- 브라우저는 GitHub Pages 인증 화면과 Google Identity Services Token Model을 사용합니다.
- Android 공식 런처는 Android Google Identity `AuthorizationClient`를 직접 호출합니다.
- GitHub Pages는 사용자 승인 후 짧은 수명의 액세스 토큰을 게임 창으로 전달합니다.
- Android는 기존 Google 권한이 있는 경우 `AuthorizationClient.authorize()`로 새 액세스 토큰을 다시 받을 수 있습니다.
- 로그인 유지 설정은 게임의 로컬 저장소에 정책만 저장합니다. 액세스 토큰과 Refresh Token은 게임 저장소에 기록하지 않습니다.
- 명시적 로그아웃은 로컬 토큰과 Android 캐시 토큰을 삭제하고, 다음 인증부터 계정 선택을 요청합니다.
- Google 앱 권한 철회는 별도 작업이며 브라우저 GIS `revoke()` 또는 Android `AuthorizationClient.revokeAccess()`로 수행합니다.
- Cloudflare, 별도 인증 서버, Worker, Durable Objects, WebSocket 인증 중계, OAuth Client Secret은 CloudSave 인증에 사용하지 않습니다.

Google OAuth Web Client의 Authorized JavaScript origins에 다음 Origin을 등록해야 합니다.

`https://jeiky224.github.io`

이 브라우저 Token Model에는 OAuth Redirect URI나 Client Secret이 필요하지 않습니다. GitHub Pages 저장소 `jeiky224/cloud`의 Pages 게시 설정은 별도 완료되어야 실제 브라우저 인증이 가능합니다.

## 로그인 정책의 차이

- **로그인 상태 유지:** 브라우저는 새로고침 후 Google GIS 토큰 요청에 사용자 동작이 필요하므로 재연결 버튼을 제공합니다. 기존 Google 로그인/승인 상태를 재사용하도록 시도합니다. Android 공식 런처는 CloudSave 화면을 열 때 기존 승인 상태를 복구합니다.
- **새로고침할 때마다 로그인 해제:** 다음 페이지 초기화부터 인증 복구를 시도하지 않습니다. 다음 인증은 계정 선택 화면을 요청합니다.
- **로그아웃:** 로그인 상태 유지 정책을 해제하고 의도적 로그아웃 상태를 기록합니다. 다음 인증은 계정 선택을 요청합니다.
- **Google 앱 권한 철회:** 로그아웃과 별개이며 성공/실패를 별도 표시합니다.

## Android 인증 경로

런처의 WebView에서 WML은 다음 네이티브 브리지 메서드를 사용합니다.

- `WMLHost.startGoogleDriveAuthWithPolicy(nonce, selectAccount)`
- `WMLHost.takePendingStateForNonce(nonce)`
- `WMLHost.clearGoogleAccessToken(token)`
- `WMLHost.revokeGoogleDriveAccess(nonce)`

`selectAccount=true`이면 Android Google Identity의 `AuthorizationRequest.Prompt.SELECT_ACCOUNT`를 지정합니다. 로그아웃하지 않은 상태에서 기존 승인 권한이 남아 있으면 `authorize()`는 사용자 입력 없이 새 액세스 토큰을 반환할 수 있습니다. 명시적 계정 변경/로그아웃 후에는 계정 선택을 표시합니다.

Android `revokeAccess()`는 앱에 부여된 권한을 철회합니다. 이 API는 해당 앱에서 승인된 다른 범위도 함께 철회할 수 있습니다.

## 릴리스 서명

GitHub Actions에서 서명된 Release APK를 만들려면 저장소 Actions secrets에 다음 값을 등록합니다.

- `ANDROID_KEYSTORE_BASE64`: release PKCS#12 파일의 Base64 전체 내용
- `ANDROID_KEYSTORE_PASSWORD`: PKCS#12를 열 때 사용하는 내보내기 암호
- `ANDROID_KEY_ALIAS`: `wayward-release`

키 파일과 암호는 저장소 파일이나 코드에 넣지 않습니다. 세 secret 중 하나라도 없으면 CI는 Debug APK와 unsigned Release APK를 생성합니다.

## 기준

- 개발 저장소: https://github.com/diethoon/wayward
- 런처 기준 commit: `44bbbce00f50da66c4046d696eb1234f25ada979`
- 브라우저 인증 페이지: https://jeiky224.github.io/cloud/

## 검증 제한

CI는 네이티브 인증 브리지 계약, JavaScript 테스트, Gradle Debug/Release 빌드 및 Android 15 emulator startup을 검사합니다. 실제 Google 계정 선택, 계정 변경 및 Google 권한 철회 end-to-end는 실기기와 실계정으로 별도 확인해야 합니다. CI 통과만으로 Google 계정 인증 성공을 주장하지 않습니다.
