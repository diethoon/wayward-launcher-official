# Wayward Launcher — Official 1.0.0

공식 배포 전용 저장소입니다. 개발용 diethoon/wayward 저장소와 독립적으로 유지합니다.

## 최초 배포 범위
- SAF를 통한 게임 폴더 등록
- 등록된 게임별 독립 Android 문서 세션
- 세션별 WebView와 로컬 게임 origin 분리
- 게임 원본 파일을 직접 수정하지 않는 읽기 전용 실행
- 게임 세션 종료 후 런처로 복귀
- Android 네이티브 Google Identity Services를 통한 Drive App Data 권한 요청

ZIP 모드 관리, ImageGen/ComfyUI/NovelAI/FLUX, 원격 진단 업로드는 최초 UI 및 실행 경로에서 제외했습니다. 관련 개발 소스가 남아 있더라도 공식 1.0.0에서 활성화하거나 지원한다고 간주하지 않습니다.

## Google Drive CloudSave
Android 런처 안에서는 기존 CloudSave 0.4.4의 Vercel 팝업 URL을 네이티브 Google Identity Services AuthorizationClient로 중계합니다. 요청 범위는 Drive App Data(drive.appdata)이며, 결과 토큰은 JS에 전달하기 위한 프로세스 메모리의 nonce별 일회성 결과로만 보관합니다. 게임 모드의 기존 암호화·저장/불러오기 로직은 수정하지 않습니다.

일반 브라우저에서는 기존 Vercel OAuth 팝업 경로를 그대로 사용합니다. Android에서는 Google Cloud 프로젝트에 Android OAuth 클라이언트를 별도로 등록해야 합니다. 디버그 APK 패키지는 com.wayward.launcher.official.debug이며, SHA-1은 CI 로그에서 확인할 수 있습니다. 정식 Release는 별도의 배포 서명 키와 그 SHA-1 등록이 필요합니다.

## 기준
- 개발 저장소: https://github.com/diethoon/wayward
- 기준 커밋: 9c52b690229808c9f6b188fcf55c073468b81341
- 기준 경로: android-external-folder-launcher/

## 검증 상태
Android Debug/Release 빌드가 통과했습니다. Android 15 에뮬레이터 실행 및 네이티브 Google Drive 승인/CloudSave 실제 저장·불러오기는 별도 스모크 테스트 중입니다. 검증 전에는 APK를 정식 배포 완료본으로 표시하지 않습니다.
