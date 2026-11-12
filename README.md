# Wayward Launcher — Official 1.0.0

공식 배포 전용 저장소입니다. 개발용 `diethoon/wayward` 저장소와 독립적으로 유지합니다.

## 최초 배포 범위
- SAF를 통한 게임 폴더 등록
- 등록된 게임별 독립 Android 문서 세션
- 세션별 WebView와 로컬 게임 origin 분리
- 게임 원본 파일을 직접 수정하지 않는 읽기 전용 실행
- 게임 세션 종료 후 런처로 복귀
- Google Drive CloudSave는 모든 환경에서 동일한 GitHub Pages OAuth 진입점을 사용

ZIP 모드 관리 UI는 최초 배포 범위에서 제외합니다. ImageGen/ComfyUI/NovelAI/FLUX 서버와 공급자 코드, 원격 진단 업로더는 공식 1.0.0 소스에서 제거했습니다. 런처 진단 기록은 기기 내부에만 저장하며 외부 서버로 전송하지 않습니다.

## Google Drive CloudSave 인증

모든 환경이 다음 한 주소만 사용합니다.

`https://jeiky224.github.io/cloud/`

게임은 nonce가 포함된 임시 WebSocket을 Cloudflare Durable Object에 먼저 연결한 뒤 인증 페이지를 엽니다. Android 공식 런처는 검증된 GitHub Pages URL만 외부 브라우저로 실행합니다. 일반 브라우저와 AppPorting은 동일 URL을 엽니다. GitHub Pages는 Google 계정 선택/승인 화면으로 바로 이동하고 콜백 후 동일한 nonce의 WebSocket으로 결과를 전달합니다. `window.opener`가 없어도 자동 복귀하며 JSON 복사·붙여넣기 경로는 사용하지 않습니다.

Google OAuth는 오프라인 액세스를 요청합니다. Cloudflare Worker가 승인 코드를 교환하고 refresh token은 저장하지 않은 채 게임으로 전달합니다. 게임 모드는 로그인 유지 설정이 켜져 있으면 refresh token을 기기에 저장하고 액세스 토큰 만료 시 자동 갱신합니다. 명시적 로그아웃은 기기 토큰을 삭제하고 Google revoke를 요청합니다. Google에서 refresh token을 철회/만료시키면 다시 로그인해야 하며, Google이 허용하지 않는 무기한 보존을 보장하지 않습니다.

Google Cloud Console의 기존 Web OAuth 클라이언트에 다음 Authorized redirect URI를 정확히 등록해야 합니다.

`https://jeiky224.github.io/cloud/`

Cloudflare Worker `wayward-auth-relay`에는 Secret `GOOGLE_CLIENT_SECRET`이 필요합니다. 비밀값은 저장소·페이지 소스·채팅에 넣지 않습니다. GitHub Pages 저장소 `jeiky224/cloud`의 파일 배포 권한과 Pages 배포 설정이 완료되어야 실제 브라우저 로그인이 활성화됩니다.

## Android 외부 브라우저 연결

런처의 `WMLHost.openGoogleAuthPage()`는 `https://jeiky224.github.io/cloud/` 주소와 nonce만 허용해 외부 브라우저로 엽니다. 인증 결과는 네이티브 Google Identity Services가 아니라 공통 WebSocket으로 전달됩니다. 기존 SAF·세션·게임 파일 경로는 변경하지 않습니다.

## 릴리스 서명

GitHub Actions에서 서명된 Release APK를 만들려면 저장소 Actions secrets에 다음 값을 등록합니다.

- `ANDROID_KEYSTORE_BASE64`: release PKCS#12 파일의 Base64 전체 내용
- `ANDROID_KEYSTORE_PASSWORD`: PKCS#12를 열 때 사용하는 내보내기 암호
- `ANDROID_KEY_ALIAS`: `wayward-release`

키 파일과 암호는 저장소 파일이나 코드에 넣지 않습니다. 세 secret 중 하나라도 없으면 CI는 Debug APK와 unsigned Release APK를 생성합니다.

## 기준

- 개발 저장소: https://github.com/diethoon/wayward
- 런처 기준 commit: `44bbbce00f50da66c4046d696eb1234f25ada979`
- OAuth 진입점: https://jeiky224.github.io/cloud/
- Cloudflare 인증 중계: https://wayward-auth-relay.trpgengine1.workers.dev

## 검증 제한

PR CI는 JavaScript 문법, Google OAuth 브리지 계약, 런처 범위, Gradle Debug/Release 빌드 및 Android 15 emulator startup 검사를 실행합니다. 실제 Google 계정 승인·refresh·revoke end-to-end는 GitHub Pages 배포, Google redirect URI 등록, Cloudflare `GOOGLE_CLIENT_SECRET` 설정 이후 실계정으로 확인해야 합니다. 그 검증 전에는 모든 환경에서 로그인 성공했다고 표시하지 않습니다.
