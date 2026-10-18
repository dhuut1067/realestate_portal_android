from pathlib import Path

root = Path.cwd()
main = root / 'app/src/main/java/kr/co/estate/portal/MainActivity.kt'
manifest = root / 'app/src/main/AndroidManifest.xml'
server = root / 'app/src/main/java/kr/co/estate/portal/ServerApartmentSearch.kt'
if not main.exists() or not manifest.exists():
    raise SystemExit('프로젝트 최상위 폴더에서 실행하십시오. MainActivity.kt 또는 AndroidManifest.xml을 찾지 못했습니다.')
if not (root / 'settings.gradle.kts').exists():
    raise SystemExit('settings.gradle.kts가 없습니다. 프로젝트 최상위 폴더가 아닙니다.')
s = main.read_text(encoding='utf-8')
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
 '            if (selectedServerApartment == null && page != Page.Search) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {'),
]
for old, new in replacements:
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'MainActivity.kt가 예상 버전과 다릅니다. 대체할 내용({count}개): {old[:95]}')
    s = s.replace(old, new, 1)
ms = manifest.read_text(encoding='utf-8')
if 'android:usesCleartextTraffic' not in ms:
    ms = ms.replace('<application ', '<application android:usesCleartextTraffic="true" ', 1)
source = Path(__file__).with_name('ServerApartmentSearch.kt')
if not source.exists():
    raise SystemExit('ServerApartmentSearch.kt 파일이 같은 폴더에 있어야 합니다.')
# 모든 검증을 마친 후 실제 수정
main.write_text(s, encoding='utf-8')
manifest.write_text(ms, encoding='utf-8')
server.write_text(source.read_text(encoding='utf-8'), encoding='utf-8')
print('완료: MainActivity.kt / AndroidManifest.xml 수정, ServerApartmentSearch.kt 생성')
print('다음: ServerApartmentSearch.kt의 DJANGO_BASE_URL을 확인하고 Android Studio에서 Run 하십시오.')
