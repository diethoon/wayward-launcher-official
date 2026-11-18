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
5. FAITH 3.0.0 원본을 춻�q�^