# Wayward Launcher — Official

이 저장소는 공식 배포본 전용입니다. 개발 저장소 diethoon/wayward와 별도 이력으로 관리합니다.

## 현재 상태
- 공식 작업 브랜치: work/official-v1-bootstrap-20261111
- 기준 개발 커밋: 9c52b690229808c9f6b188fcf55c073468b81341
- 목표 버전: 1.0.0
- 범위: 독립 다중 세션 런처 + Android 네이티브 Google Drive App Data 인증
- ZIP 모드 관리, ImageGen/ComfyUI/NovelAI/FLUX UI 및 원격 진단 업로드는 최초 버전에서 제외

공식 소스는 작업 브랜치에서 검증 중이며, 빌드·Android 실행·Google Drive 저장/불러오기 검증을 마친 뒤에만 main으로 병합합니다. Release 서명 키가 설정되지 않은 빌드는 unsigned 시험 산출물이며 정식 배포본이 아닙니다.

## CI
- Build official v1: .github/workflows/import-official-source.yml
- Android smoke: .github/workflows/android-smoke.yml
