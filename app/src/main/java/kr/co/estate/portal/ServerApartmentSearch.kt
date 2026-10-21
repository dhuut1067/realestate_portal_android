package kr.co.estate.portal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 실기기에서 접속하는 운영 API. 검색과 실거래 화면에서 같은 주소를 사용합니다.
internal const val DJANGO_BASE_URL = "http://cncvision.ipdisk.co.kr:8001"

data class ServerApartment(
    val kaptCode: String,
    val name: String,
    val dong: String,
    val bjdCode: String,
    val latitude: Double? = null,
    val longitude: Double? = null
)

private data class RegionOption(val code: String, val name: String)

// 서버 일시 장애에도 시·도 자체는 선택할 수 있습니다. 하위 지역은 서버 자료만 사용합니다.
private val defaultSidos = listOf(
    "11" to "서울특별시", "26" to "부산광역시", "27" to "대구광역시",
    "28" to "인천광역시", "29" to "광주광역시", "30" to "대전광역시",
    "31" to "울산광역시", "36" to "세종특별자치시", "41" to "경기도",
    "51" to "강원특별자치도", "43" to "충청북도", "44" to "충청남도",
    "52" to "전북특별자치도", "46" to "전라남도", "47" to "경상북도",
    "48" to "경상남도", "50" to "제주특별자치도"
).map { RegionOption(it.first, it.second) }

private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")

private suspend fun getJsonArray(path: String): JSONArray = withContext(Dispatchers.IO) {
    val connection = URL("$DJANGO_BASE_URL$path").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 6000
        connection.readTimeout = 10000
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        if (status !in 200..299) error("서버 응답 오류: HTTP $status")
        val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        JSONArray(response)
    } finally {
        connection.disconnect()
    }
}

private suspend fun getRegions(path: String): List<RegionOption> {
    val array = getJsonArray(path)
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val code = item.optString("code")
        val name = item.optString("name")
        if (code.isBlank() || name.isBlank()) null else RegionOption(code, name)
    }.distinctBy { it.code }
}

private suspend fun getApartments(dongCode: String): List<ServerApartment> {
    // 기존 Django apartment_options_api: dong_code prefix로 필터링
    val array = getJsonArray("/api/apartment-options/?dong_code=${encoded(dongCode)}")
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val code = item.optString("kapt_code")
        if (code.isBlank()) return@mapNotNull null
        ServerApartment(
            kaptCode = code,
            name = item.optString("kapt_name"),
            dong = item.optString("dong_name"),
            bjdCode = item.optString("bjd_code"),
            latitude = item.optDouble("latitude", item.optDouble("lat", Double.NaN)).takeIf { it.isFinite() && it in -90.0..90.0 },
            longitude = item.optDouble("longitude", item.optDouble("lng", Double.NaN)).takeIf { it.isFinite() && it in -180.0..180.0 }
        )
    }.distinctBy { it.kaptCode }
}

@Composable
private fun <T> ChoiceField(
    title: String,
    value: T?,
    options: List<T>,
    titleOf: (T) -> String,
    enabled: Boolean,
    onChoose: (T) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Text("$title: ${value?.let(titleOf) ?: "선택하십시오"}")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("$title 선택") },
            text = {
                if (options.isEmpty()) {
                    Text("목록이 없습니다. 앞 단계 선택 또는 서버 연결 상태를 확인하십시오.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(options.size) { index ->
                            val option = options[index]
                            TextButton(
                                onClick = {
                                    showDialog = false
                                    onChoose(option)
                                },
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(titleOf(option))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }
}

@Composable
fun ServerSearchScreen(onSelect: (ServerApartment) -> Unit) {
    var sidos by remember { mutableStateOf(defaultSidos) }
    var sigungus by remember { mutableStateOf<List<RegionOption>>(emptyList()) }
    var dongs by remember { mutableStateOf<List<RegionOption>>(emptyList()) }
    var apartments by remember { mutableStateOf<List<ServerApartment>>(emptyList()) }

    var sido by remember { mutableStateOf<RegionOption?>(null) }
    var sigungu by remember { mutableStateOf<RegionOption?>(null) }
    var dong by remember { mutableStateOf<RegionOption?>(null) }
    var apartment by remember { mutableStateOf<ServerApartment?>(null) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var retry by remember { mutableIntStateOf(0) }

    // 하나의 조회 작업으로 관리하여 지역 변경 시 이전 요청을 취소합니다.
    LaunchedEffect(sido?.code, sigungu?.code, dong?.code, retry) {
        val selectedSido = sido
        val selectedSigungu = sigungu
        val selectedDong = dong
        loading = true
        error = null
        try {
            when {
                selectedDong != null -> apartments = getApartments(selectedDong.code)
                selectedSigungu != null -> dongs = getRegions("/api/dongs/?sigungu_code=${encoded(selectedSigungu.code)}")
                selectedSido != null -> sigungus = getRegions("/api/sigungus/?sido_code=${encoded(selectedSido.code)}")
                else -> {
                    val serverSidos = getRegions("/api/sidos/")
                    if (serverSidos.isEmpty()) throw IllegalStateException("서버의 시·도 목록이 비어 있습니다.")
                    sidos = serverSidos
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val stage = when {
                selectedDong != null -> "아파트"
                selectedSigungu != null -> "읍면동"
                selectedSido != null -> "시군구"
                else -> "시·도"
            }
            error = "$stage 조회 실패: ${e.message}"
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        KakaoApartmentMap(apartments, apartment,
            listOfNotNull(sido?.name, sigungu?.name, dong?.name).joinToString(" "), onSelect)
        Text("전국 아파트 단지검색", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("지역과 단지를 순서대로 선택하십시오.", style = MaterialTheme.typography.bodySmall)
        ChoiceField("시도", sido, sidos, { it.name }, sidos.isNotEmpty()) {
            sido = it; sigungu = null; dong = null; apartment = null
            sigungus = emptyList(); dongs = emptyList(); apartments = emptyList()
        }
        ChoiceField("시군구", sigungu, sigungus, { it.name }, !loading && sido != null && sigungus.isNotEmpty()) {
            sigungu = it; dong = null; apartment = null
            dongs = emptyList(); apartments = emptyList()
        }
        ChoiceField("읍면동", dong, dongs, { it.name }, !loading && sigungu != null && dongs.isNotEmpty()) {
            dong = it; apartment = null; apartments = emptyList()
        }
        // 별도의 리 API가 없어 도시/농촌 모두 잘못된 리 목록을 만들어 내지 않습니다.
        // 정확한 리 목록 API 추가 시 이 위치에 '리' 선택 필드를 연결합니다.
        ChoiceField("아파트", apartment, apartments, { it.name }, !loading && dong != null && apartments.isNotEmpty()) {
            apartment = it
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Text("서버: $DJANGO_BASE_URL", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { retry++ }, enabled = !loading) { Text("다시 조회") }
        }
        if (!loading && dong != null && error == null) {
            Text("선택 지역 단지 ${apartments.size}개", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { apartment?.let(onSelect) },
            enabled = !loading && apartment != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("검색") }
    }
}

@Composable
fun LegacyServerApartmentDetail(apt: ServerApartment) {
    var tab by remember(apt.kaptCode) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("실거래", "단지정보", "중개사·매물").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(apt.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(apt.dong)
            Text("단지코드: ${apt.kaptCode}")
            Text(when (tab) {
                0 -> "실거래 API 연결 대상 단지입니다. 다음 단계에서 실거래 내역과 그래프를 연결합니다."
                1 -> "법정동 코드: ${apt.bjdCode}. 다음 단계에서 상세 단지정보를 연결합니다."
                else -> "다음 단계에서 선택한 단지코드로 중개사 및 매물 API를 연결합니다."
            })
        }
    }
}

