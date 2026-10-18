from pathlib import Path
root = Path.cwd()
source = Path(__file__).with_name('ServerApartmentSearch.kt')
target = root / 'app/src/main/java/kr/co/estate/portal/ServerApartmentSearch.kt'
main = root / 'app/src/main/java/kr/co/estate/portal/MainActivity.kt'
manifest = root / 'app/src/main/AndroidManifest.xml'
if not (root / 'settings.gradle.kts').exists() or not main.exists() or not manifest.exists():
    raise SystemExit('프로젝트 최상위 폴더에서 실행하십시오.')
s = main.read_text(encoding='utf-8')
if not target.exists():
    replacements = [
        ('    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }',
         '    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }\n    var selectedServerApartment by remember { mutableStateOf<ServerApartment?>(null) }'),
        ('BackHandler(selected != null || registration != null) { selectedId = null; registration = null }',
         'BackHandler(selected != null || selectedServerApartment != null || registration != null) { selectedId = null; selectedServerApartment = null; registration = null }'),
        ('Text(selected?.name ?: "부동산 포털", fontWeight = FontWeight.Bold)',
         'Text(selectedServerApartment?.name ?: selected?.name ?: "부동산 포털", fontWeight = FontWeight.Bold)'),
        ('Text(if (selected != null) selected.address else "단지에서 시작하는 부동산 정보", style = MaterialTheme.typography.labelMedium)',
         'Text(selectedServerApartment?.dong ?: if (selected != null) selected.address else "단지에서 시작하는 부동산 정보", style = MaterialTheme.typography.labelMedium)'),
        ('if (selected != null) IconButton(onClick = { selectedId = null }) {',
         'if (selected != null || selectedServerApartment != null) IconButton(onClick = { selectedId = null; selectedServerApartment = null }) {'),
        ('pageName = destination.name; selectedId = null',
         'pageName = destination.name; selectedId = null; selectedServerApartment = null'),
        ('            if (selected != null) DetailScreen(selected, listings.filter { it.apartmentId == selected.id })',
         '            if (selectedServerApartment != null) ServerApartmentDetail(selectedServerApartment!!)\n            else if (selected != null) DetailScreen(selected, listings.filter { it.apartmentId == selected.id })'),
        ('Page.Search -> SearchScreen(apartments, favorites, { selectedId = it }, ::toggle)',
         'Page.Search -> ServerSearchScreen { selectedServerApartment = it }'),
        ('            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {',
         '            if (selectedServerApartment == null && page != Page.Search) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {')
    ]
    for old, new in replacements:
        if s.count(old) != 1:
            raise SystemExit('MainActivity.kt 파일 구조가 예상과 다릅니다. 안전을 위해 적용하지 않습니다: ' + old[:65])
        s = s.replace(old, new, 1)
    original = main.read_text(encoding='utf-8')
    main.with_suffix('.kt.bak').write_text(original, encoding='utf-8')
    main.write_text(s, encoding='utf-8')
    print('MainActivity.kt 연결 완료')
else:
    if 'fun ServerSearchScreen' not in target.read_text(encoding='utf-8'):
        raise SystemExit('ServerApartmentSearch.kt 구조가 예상과 다릅니다. 변경 중단.')
    target.with_suffix('.kt.bak').write_text(target.read_text(encoding='utf-8'), encoding='utf-8')
ms = manifest.read_text(encoding='utf-8')
if 'android:usesCleartextTraffic' not in ms:
    manifest.write_text(ms.replace('<application ', '<application android:usesCleartextTraffic="true" ', 1), encoding='utf-8')
target.write_text(source.read_text(encoding='utf-8'), encoding='utf-8')
print('완료: 선택형 단지검색 적용, Android Studio에서 Run 실행')
