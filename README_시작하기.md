# 부동산 포털 Android UI

Jetpack Compose + Material 3 네이티브 프로젝트입니다. Android 7.0(API 24) 이상을 대상으로 합니다.

## 실행
1. ZIP을 `C:\RealEstatePortal`에 압축 해제합니다.
2. Android Studio에서 **Open → RealEstatePortal** 폴더를 엽니다. 기존 Django 프로젝트는 별도로 둡니다.
3. Gradle JDK를 17로 설정하고 Android SDK 36을 설치한 뒤 Sync합니다.
4. 휴대폰 USB 디버깅을 켜거나 에뮬레이터를 선택하고 Run을 누릅니다.
5. PowerShell 빌드: `cd C:\RealEstatePortal` 후 `.\gradlew.bat assembleDebug`.
6. APK 결과: `app\build\outputs\apk\debug\app-debug.apk`.

## 구현 화면과 동작
- 단지찾기: 지역·단지 검색, 전용면적 평형대, 입주연도, 세대수 필터 및 초기화.
- 관심단지: 하트 선택·해제, 기기 재시작 후 유지.
- 단지 상세: 실거래 / 단지정보 / 중개사·매물 탭, 면적 단위 전환.
- 실거래: 매매 예시 차트, 1년/3년 선택, 가격 왼쪽축·거래건수 오른쪽축.
- 매물: 매매·전세·월세 필터, 단지 상세 이동.
- 내정보: 중개사·집주인 등록 초안 작성, 필수항목 검증, 기기 저장.
- 시스템 다크모드, 스크롤, 시스템 뒤로가기, 화면 회전 상태 보존.

## 실제 서비스 연결 위치
`Models.kt`의 `PortalRepository`가 단지·매물 제공 경계입니다. 현재 `ExamplePortalRepository`에 예시 데이터만 있습니다.
서버 API 응답 모델과 엔드포인트를 확인한 뒤 실제 Repository로 교체하고 비동기 로딩·오류·재시도 상태를 추가합니다.
`PortalApp(repository = ...)`에서 주입합니다. API 주소·인증 키를 임의로 넣지 않았습니다.

지도 영역은 **지도 SDK 연결 대기**로 표시됩니다. 카카오 네이티브 지도 SDK의 앱 키·패키지·키해시 등록 후 Compose AndroidView 영역으로 연결합니다.
현재 지도 이동·위치 마커·로드뷰는 구현되어 있지 않습니다.
전월세 차트는 전월세 API 연결 후 표시하도록 대기 안내를 넣었습니다.
중개사 연락·가입·집주인 접수는 아직 서버에 전송되지 않습니다. 초안 저장만 동작합니다.
등록 초안은 마지막 한 건을 보관합니다. 프로필·인증·개인정보 동의는 실제 접수 연동 시 추가합니다.

## 파일
- `MainActivity.kt`: 앱 화면, 검색·상태·차트·관심단지·등록 초안.
- `Models.kt`: 모델, Repository 인터페이스, 예시 데이터.
- `app/build.gradle.kts`: Compose·Material 3 의존성.
- `AndroidManifest.xml`: 앱 이름과 실행 Activity.

## 검증 범위
XML 파싱, 프로젝트 구성, ZIP 무결성, Gradle Wrapper JAR 구성은 확인했습니다.
작성 환경에 Android SDK 및 에뮬레이터가 없어 Kotlin 컴파일·APK 빌드·기기 실행·시각 검증은 하지 못했습니다.
배포 전 Android Studio에서 빌드·폰/태블릿 확인이 필요합니다.
AGP 8.11.1 / Gradle 8.13 / Kotlin 2.1.20 / Compose BOM 2025.04.01로 버전을 고정했습니다.

실제 단지명과 함께 표시한 가격·거래량·매물 등도 모두 UI 예시이며 운영 데이터로 사용하지 않습니다.
