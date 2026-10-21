# 카카오 지도 실행

1. 이 저장소의 최신 main을 받습니다.
2. 프로젝트 루트에서 `powershell -ExecutionPolicy Bypass -File .\setup-kakao.ps1`을 실행하고 기존에 발급한 **네이티브 앱 키**를 입력합니다. 키는 git에 올리지 않는 local.properties에만 저장합니다.
3. 카카오 개발자 콘솔 Android 플랫폼의 패키지명을 `kr.co.estate.portal`로 등록합니다. 이전 OpenEstate의 패키지명과 다릅니다. 이 PC의 디버그 서명 키 해시도 등록해야 합니다.
4. Android Studio에서 Gradle 동기화 후 실제 갤럭시로 Run합니다.

## 화면과 기능

- 단지검색 화면 상단에서 native Kakao MapView가 시작되며 기본 위치는 부산입니다.
- 시도/시군구/읍면동 선택에 따라 지도 중심을 이동합니다.
- 단지 API가 latitude/longitude 또는 lat/lng를 주면 해당 단지 마커를 표시합니다.
- 좌표가 없는 경우 선택한 단지만 Android Geocoder로 위치를 조회합니다. 이름 검색이므로 정확도는 반환 주소에 의존합니다. 동 중심을 단지 위치로 표시하지 않습니다.
- 단지 마커를 누르면 해당 kaptCode를 갖는 기존 실거래 상세 화면으로 이동합니다.
- Geocoder 실패 시에도 지역 선택과 검색 버튼으로 기존 상세 기능을 이용할 수 있습니다.
- 지도 SDK 2.15.1에 맞춰 라벨 클릭 콜백에서 Boolean을 반환합니다.
- 서버 주소는 기존에 외부 접속을 확인한 DDNS `http://cncvision.ipdisk.co.kr:8001`을 사용합니다.

## 확인 상태

SDK Maven 배포 파일과 클래스 메서드 형식을 확인했습니다. 개발 환경에 Android SDK가 없어 전체 Android 빌드 및 실제 지도 인증/표시는 아직 검증하지 못했습니다. 기기에서 지도 표시, 확대/축소, 지역 이동, 마커 클릭, 앱 복귀를 확인해야 합니다.
