# Wayward Launcher — Official 1.0.0

공식 배포 전용 저장소입니다. 개발용 diethoon/wayward 저장소와 독립적으로 유지합니다.

## 최초 배포 범위
- SAF를 통한 게임 폴더 등록
- 등록된 게임별 독립 Android 문서 세션
- 세션별 WebView와 로컬 게임 origin 분리
- 게임 원본 파일을 직접 수정하지 않는 읽기 전용 실행
- 게임 세션 종료 후 런처로 복귀

ZIP 모드 관리, ImageGen/ComfyUI/NovelAI/FLUX, 원격 진단 업로드는 최초 UI 및 실행 경로에서 제외했습니다. 관련 개발 소스가 남아 있더라도 공식 1.0.0에서 활성화하거나 지원한다고 간주하지 않습니다.

## 기준
- 개발 저장소: https://github.com/diethoon/wayward
- 기준 커밋: 9c52b690229808c9f6b188fcf55c073468b81341
- 기준 경로: android-external-folder-launcher/

## Google Drive CloudSave
현재 개발본의 OAuth는 팝업의 window.opener/postMessage 반환 방식입니다. Android 외부 브라우저에서는 opener가 없어 현재 상태로 호환되지 않습니다. 모바일용 안전한 1회성 인증 전달 경로가 구현·실기기 검증되기 전에는 Google Drive 연결을 지원한다고 표시하지 않습니다.

## 검증 상태
현재 CI에서 Debug APK와 unsigned Release APK 빌드가 성공했습니다. Android 실기기/에뮬레이터의 다중 세션 동작과 Google Drive OAuth는 아직 미검증이므로 APK는 시험 산출물이며 정식 배포본이 아닙니다.
