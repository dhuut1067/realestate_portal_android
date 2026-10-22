package kr.co.estate.portal

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import java.util.Locale

private val Green = Color(0xFF087F68)
private val Red = Color(0xFFC65757)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EstateTheme { PortalApp() } }
    }
}
@Composable fun EstateTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF75DCC0))
    else lightColorScheme(primary = Green, secondary = Color(0xFF46645C),
        background = Color(0xFFF5F7F6), surface = Color.White,
        primaryContainer = Color(0xFFD9F3E9), onPrimaryContainer = Color(0xFF004D3D))
    MaterialTheme(colorScheme = colors, content = content)
}
private enum class Page(val label: String, val icon: ImageVector) {
    Search("단지찾기", Icons.Default.Search), Listings("매물", Icons.Default.Home),
    Saved("관심단지", Icons.Default.FavoriteBorder), My("내정보", Icons.Default.PersonOutline)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PortalApp(repository: PortalRepository = ExamplePortalRepository()) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("estate", Context.MODE_PRIVATE) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())!!.toSet()) }
    var pageName by rememberSaveable { mutableStateOf(Page.Search.name) }
    val page = Page.valueOf(pageName)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var selectedServerApartment by remember { mutableStateOf<ServerApartment?>(null) }
    var registration by rememberSaveable { mutableStateOf<String?>(null) }
    val apartments = remember(repository) { repository.apartments() }
    val listings = remember(repository) { repository.listings() }
    val selected = apartments.find { it.id == selectedId }
    fun toggle(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putStringSet("favorites", favorites).apply()
    }
    BackHandler(selected != null || showDetails || registration != null) { selectedId = null; showDetails = false; registration = null }
    Scaffold(
        topBar = {
            TopAppBar(title = { Column {
                Text(selectedServerApartment?.name ?: selected?.name ?: "부동산 포털", fontWeight = FontWeight.Bold)
                Text(selectedServerApartment?.dong ?: if (selected != null) selected.address else "단지에서 시작하는 부동산 정보", style = MaterialTheme.typography.labelMedium)
            } }, navigationIcon = {
                if (selected != null || selectedServerApartment != null) IconButton(onClick = { selectedId = null; showDetails = false }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                }
            }, actions = {
                if (selected != null) IconButton(onClick = { toggle(selected.id) }) {
                    Icon(if (selected.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "관심단지 저장")
                }
            })
        }, bottomBar = {
            NavigationBar {
                Page.entries.forEach { destination ->
                    NavigationBarItem(selected = page == destination, onClick = {
                        pageName = destination.name; selectedId = null; showDetails = false
                    }, icon = { Icon(destination.icon, destination.label) }, label = { Text(destination.label) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selectedServerApartment == null && page != Page.Search) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("예시 데이터 · 실제 실거래 및 매물 정보가 아닙니다", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
            }
            if (selected != null) DetailScreen(selected, listings.filter { it.apartmentId == selected.id })
            else when (page) {
                Page.Search -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showDetails, onClick = { showDetails = false }, label = { Text("지도보기") })
                        FilterChip(selected = showDetails, onClick = { showDetails = true }, label = { Text("상세내용") })
                    }
                    Box(Modifier.weight(1f)) {
                        ServerSearchScreen(showMap = !showDetails) { selectedServerApartment = it; showDetails = true }
                        if (showDetails) Surface(Modifier.fillMaxSize()) {
                            val apt = selectedServerApartment
                            if (apt != null) ServerApartmentDetail(apt)
                            else Text("지도에서 아파트 금액을 눌러 단지를 선택하십시오.", Modifier.padding(20.dp))
                        }
                    }
                }
                Page.Saved -> ApartmentList(apartments.filter { it.id in favorites }, favorites,
                    "관심단지 ${favorites.size}곳", { selectedId = it }, ::toggle, "단지의 하트를 눌러 관심단지를 저장하세요.")
                Page.Listings -> ListingsScreen(listings, apartments) { selectedId = it }
                Page.My -> MyScreen(favorites.size, prefs.getString("draft", null)) { registration = it }
            }
        }
    }
    registration?.let { type -> RegistrationDialog(type, { registration = null }) { draft ->
        prefs.edit().putString("draft", draft).apply(); registration = null
    } }
}
@Composable private fun SearchScreen(apartments: List<Apartment>, favorites: Set<String>, open: (String) -> Unit, toggle: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("전체") }
    var age by rememberSaveable { mutableStateOf("전체") }
    var size by rememberSaveable { mutableStateOf("전체") }
    var unitPyeong by rememberSaveable { mutableStateOf(true) }
    val filtered = apartments.filter { a ->
        val p = a.area / 3.305785
        (query.isBlank() || "${a.name} ${a.address}".contains(query.trim(), ignoreCase = true)) &&
        (when (area) { "10평대" -> p >= 10 && p < 20; "20평대" -> p >= 20 && p < 30; "30평대" -> p >= 30 && p < 40; "40평대" -> p >= 40 && p < 50; "50평대" -> p >= 50 && p < 60; "60평+" -> p >= 60; else -> true }) &&
        (when (age) { "10년 이내" -> a.year >= 2016; "20년 이내" -> a.year >= 2006; else -> true }) &&
        (when (size) { "500+" -> a.households >= 500; "1,000+" -> a.households >= 1000; "3,000+" -> a.households >= 3000; else -> true })
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("지역·단지명 검색") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "검색어 지우기") }
            }, shape = RoundedCornerShape(16.dp))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("상세 조건", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { area = "전체"; age = "전체"; size = "전체"; query = "" }) { Text("초기화") }
            TextButton(onClick = { unitPyeong = !unitPyeong }) { Text(if (unitPyeong) "평 → m²" else "m² → 평") }
        }
        ChoiceRow("전용면적", listOf("전체", "10평대", "20평대", "30평대", "40평대", "50평대", "60평+"), area) { area = it }
        ChoiceRow("입주연도", listOf("전체", "10년 이내", "20년 이내"), age) { age = it }
        ChoiceRow("세대수", listOf("전체", "500+", "1,000+", "3,000+"), size) { size = it }
        ApartmentList(filtered, favorites, "검색 결과 ${filtered.size}개 단지", open, toggle,
            "조건에 맞는 단지가 없습니다. 검색어 또는 필터를 변경하세요.", unitPyeong)
    }
}
@Composable private fun ChoiceRow(label: String, options: List<String>, value: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        options.forEach { option -> FilterChip(selected = value == option, onClick = { change(option) }, label = { Text(option) }) }
    }
}
private fun areaLabel(area: Double, pyeong: Boolean) = if (pyeong) String.format(Locale.KOREA, "전용 %.1f평", area / 3.305785) else "전용 ${area}m²"
@Composable private fun ApartmentList(data: List<Apartment>, favorites: Set<String>, title: String,
    open: (String) -> Unit, toggle: (String) -> Unit, empty: String, pyeong: Boolean = true) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (data.isEmpty()) item { EmptyPanel(empty) }
        items(data, key = { it.id }) { a ->
            Card(onClick = { open(a.id) }, Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Apartment, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(a.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(a.address, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { toggle(a.id) }) { Icon(if (a.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "관심단지 저장", tint = MaterialTheme.colorScheme.primary) }
                    }
                    Text("${a.year}년 입주 · ${a.households}세대 · ${areaLabel(a.area, pyeong)}", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("예시 매매가", style = MaterialTheme.typography.labelMedium)
                        Text(a.sale, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                    Text("실거래 · 단지 정보 · 중개사·매물 보기  ›", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
@Composable private fun DetailScreen(a: Apartment, listings: List<Listing>) {
    var tab by rememberSaveable(a.id) { mutableIntStateOf(0) }
    var type by rememberSaveable(a.id) { mutableStateOf("매매") }
    var period by rememberSaveable(a.id) { mutableStateOf("3년") }
    var pyeong by rememberSaveable(a.id) { mutableStateOf(true) }
    Column {
        TabRow(selectedTabIndex = tab) {
            listOf("실거래", "단지정보", "중개사·매물").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${a.year}년 입주 · ${a.households}세대", style = MaterialTheme.typography.bodyMedium)
                        Text(a.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("전용 ${a.area}m² · ${String.format(Locale.KOREA,"%.1f",a.area / 3.305785)}평")
                    }
                }
            }
            when (tab) {
                0 -> {
                    item { ChoiceRow("유형", listOf("매매", "전세", "월세"), type) { type = it } }
                    item { Text(when(type) { "전세" -> a.rent; "월세" -> a.monthly; else -> a.sale }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    if (type == "매매") {
                        item { ChoiceRow("기간", listOf("1년", "3년"), period) { period = it } }
                        item { PriceChart(a.prices, a.counts, period) }
                        item {
                            Text("거래 내역 (예시)", style = MaterialTheme.typography.titleMedium)
                            Text("아래 자료는 차트 확인용으로 생성한 분기별 예시입니다.", style = MaterialTheme.typography.bodySmall)
                        }
                        items(if (period == "1년") (3..5).toList().reversed() else (0..5).toList().reversed()) { i ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(listOf("2023.12", "2024.06", "2024.12", "2025.06", "2025.12", "2026.06")[i])
                                Text("${a.area}m²")
                                Text("${String.format(Locale.KOREA,"%.2f",a.prices[i])}억", fontWeight = FontWeight.Bold)
                            }
                        }
                    } else item { EmptyPanel("전월세 실거래 내역은 전월세 API 연결 후 표시됩니다.") }
                }
                1 -> {
                    item { SectionCard("단지 기본정보") {
                        InfoRow("주소", a.address); InfoRow("입주", "${a.year}년"); InfoRow("세대수", "${a.households}세대")
                        InfoRow("전용면적", areaLabel(a.area, pyeong))
                        TextButton(onClick = { pyeong = !pyeong }) { Text("면적 단위 변경") }
                    } }
                    item { SectionCard("단지 위치") { Icon(Icons.Default.Map, null, Modifier.size(42.dp)); Text("지도 SDK 연결 대기", fontWeight = FontWeight.Bold); Text("현재는 지도와 위치 마커를 표시하지 않습니다.", style = MaterialTheme.typography.bodySmall) } }
                }
                2 -> {
                    item { SectionCard("등록 중개사 (예시)") {
                        Text("용호동 예시 공인중개사", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("담당 단지 · ${a.name}")
                        Text("연락처·중개사 등록번호는 서버 확인 후 표시됩니다.", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { Text("단지 매물 ${listings.size}건 (예시)", style = MaterialTheme.typography.titleMedium) }
                    if (listings.isEmpty()) item { EmptyPanel("이 단지의 예시 매물이 없습니다.") }
                    items(listings, key = { it.id }) { ListingCard(it, a.name) {} }
                }
            }
        }
    }
}
@Composable private fun PriceChart(prices: List<Float>, counts: List<Int>, period: String) {
    val points = if (period == "1년") prices.takeLast(3) else prices
    val bars = counts.takeLast(points.size)
    val min = points.min() - 0.3f; val max = points.max() + 0.3f
    val countMax = (bars.max() + 2).toFloat()
    SectionCard("실거래 추이 (예시)") {
        Text("● 가격(억, 왼쪽)   ▌ 거래건수(건, 오른쪽)", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(42.dp).height(150.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(max, (max+min)/2, min).forEach { Text(String.format(Locale.KOREA,"%.1f",it), color = Green, style = MaterialTheme.typography.labelSmall) }
            }
            Canvas(Modifier.weight(1f).height(150.dp)) {
                val step = size.width / points.size
                repeat(4) { i -> val y = size.height * i/3; drawLine(Color.Gray.copy(alpha = .2f), Offset(0f,y), Offset(size.width,y)) }
                bars.forEachIndexed { i, count ->
                    val h = count / countMax * size.height
                    drawRect(Red.copy(alpha = .35f), Offset(step*i+step*.25f,size.height-h), Size(step*.5f,h))
                }
                val path = Path()
                points.forEachIndexed { i, price ->
                    val x=step*i+step*.5f; val y=size.height*(1-(price-min)/(max-min))
                    if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                    drawCircle(Green,4.dp.toPx(),Offset(x,y))
                }
                drawPath(path,Green,style=androidx.compose.ui.graphics.drawscope.Stroke(width=3.dp.toPx()))
            }
            Column(Modifier.width(30.dp).height(150.dp), horizontalAlignment=Alignment.End, verticalArrangement=Arrangement.SpaceBetween) {
                listOf(countMax.toInt(),(countMax/2).toInt(),0).forEach { Text("$it", color=Red, style=MaterialTheme.typography.labelSmall) }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(if(period=="1년") "2025.06" else "2023.12", style=MaterialTheme.typography.labelSmall); Text("2026.06", style=MaterialTheme.typography.labelSmall) }
    }
}
@Composable private fun ListingsScreen(listings: List<Listing>, apartments: List<Apartment>, open: (String)->Unit) {
    var type by rememberSaveable { mutableStateOf("전체") }
    Column {
        ChoiceRow("거래유형",listOf("전체","매매","전세","월세"),type) { type=it }
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(listings.filter { type=="전체" || it.type==type },key={it.id}) { l -> ListingCard(l,apartments.find { it.id==l.apartmentId }?.name ?: "단지") { open(l.apartmentId) } }
        }
    }
}
@Composable private fun ListingCard(l: Listing,name: String,open:()->Unit) {
    Card(onClick=open,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            Text("${l.type} ${l.price}",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
            Text("전용 ${l.area}m² · ${l.floor}",style=MaterialTheme.typography.bodyMedium)
            Text(l.note,style=MaterialTheme.typography.bodySmall)
            Text("예시 매물 · 중개사 화면에서 상세 확인",style=MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable private fun MyScreen(count:Int,draft:String?,register:(String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { SectionCard("내 부동산") { Text("관심단지 $count 곳",style=MaterialTheme.typography.titleLarge); Text("관심단지는 이 기기에 저장됩니다.") } }
        item { SectionCard("중개사 등록") {
            Text("담당 단지와 매물을 연결해 소개합니다.")
            Button(onClick={register("중개사")},modifier=Modifier.fillMaxWidth()) { Text("중개사 등록 정보 작성") }
        } }
        item { SectionCard("집주인 매물 등록") {
            Text("보유 주택의 거래유형과 희망가격을 작성합니다.")
            OutlinedButton(onClick={register("집주인")},modifier=Modifier.fillMaxWidth()) { Text("내 집 등록 정보 작성") }
        } }
        item { SectionCard("작성한 초안") { Text(draft ?: "저장한 등록 초안이 없습니다."); Text("초안은 기기에만 저장되며 서버로 접수되지 않습니다.",style=MaterialTheme.typography.labelSmall) } }
    }
}
@Composable private fun RegistrationDialog(type:String,dismiss:()->Unit,save:(String)->Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest=dismiss,title={Text("$type 등록 초안")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("작성 내용을 기기에 저장합니다. 실제 접수는 서버 연결 후 가능합니다.")
            OutlinedTextField(name,{name=it},label={Text(if(type=="중개사") "중개사무소명" else "단지명")},singleLine=true)
            OutlinedTextField(address,{address=it},label={Text("주소")},singleLine=true)
            OutlinedTextField(detail,{detail=it},label={Text(if(type=="중개사") "등록번호·담당 단지" else "거래유형·희망가격")})
        }
    },confirmButton={TextButton(onClick={save("[$type] $name\n$address\n$detail")},enabled=name.isNotBlank()&&address.isNotBlank()&&detail.isNotBlank()) { Text("초안 저장") }},dismissButton={TextButton(onClick=dismiss) { Text("닫기") }})
}
@Composable private fun SectionCard(title:String,content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);content() }
    }
}
@Composable private fun InfoRow(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,style=MaterialTheme.typography.bodyMedium);Text(value,fontWeight=FontWeight.Medium) }
}
@Composable private fun EmptyPanel(text:String) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant) { Text(text,Modifier.padding(24.dp),style=MaterialTheme.typography.bodyMedium) }
}
@Preview(showBackground=true,widthDp=412,heightDp=892)
@Composable private fun PhonePreview() { EstateTheme { PortalApp() } }
@Preview(showBackground=true,widthDp=800,heightDp=1100)
@Composable private fun TabletPreview() { EstateTheme { PortalApp() } }
