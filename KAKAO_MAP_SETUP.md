# 카카오 네이티브 실거래 지도 실행

현재 버전은 `10-20-02`입니다. 지도 인증 성공 보존본은 `지도버전(10-20-01)` 브랜치에 있습니다.
`local.properties`에 카카오 콘솔의 '부동산앱 디버그' 네이티브 키가 포함되어 있습니다.
패키지명은 `kr.co.estate.portal`이며, 키를 새로 만들거나 재등록할 필요가 없습니다.

Android Studio PowerShell 터미널에서 실행합니다.

```powershell
git pull --ff-only
if ($LASTEXITCODE -ne 0) { throw 'Git pull failed.' }
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-map.ps1
```

이미 연결된 무선 디버깅 휴대폰에 재빌드·덮어쓰기 설치·실행합니다.
SDK/JBR 기본 경로 외에 설치한 경우 스크립트가 프로젝트의 `sdk.dir`를 먼저 확인합니다.

- 기본 중심은 서울시청입니다.
- 지도 화면에 포함된 단지의 실거래가와 전용평수를 표시합니다.
- 이동·확대·축소가 끝나면 Django 웹 지도와 같은 API로 다시 조회합니다.
- 시도→시군구→읍면동→아파트 선택 시 그 위치로 중심을 이동합니다.
- 단지 라벨을 누르면 실거래 상세 화면으로 연결합니다.
- 지역 선택창은 로딩·오류·빈 목록을 표시하고 '다시 조회'를 제공합니다.

자세한 동작과 확인 범위는 `MAP_VERSION.md`를 참고하십시오.
