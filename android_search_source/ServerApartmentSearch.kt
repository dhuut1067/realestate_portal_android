package kr.co.estate.portal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 개발용 설정. 10.0.2.2는 Pixel 에뮬레이터에서 PC를 가리킵니다.
// Django가 다른 PC에서 실행 중이면 접근 가능한 Tailscale IP 또는 공인 도메인으로 바꾸십시오.
private const val DJANGO_BASE_URL = "http://10.0.2.2:8000"

data class ServerApartment(
    val kaptCode: String,
    val name: String,
    val dong: String,
    val bjdCode: String
)

private suspend fun searchApartments(q: String): List<ServerApartment> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(q, "UTF-8")
    val url = URL("$DJANGO_BASE_URL/api/apartments/suggest/?q=$encoded&limit=50")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 6000
        connection.readTimeout = 10000
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        if (status !in 200..299) error("서버 응답 오류: HTTP $status")
        val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val result = JSONArray(json)
        (0 until result.length()).mapNotNull { idx ->
            val obj = result.optJSONObject(idx) ?: return@mapNotNull null
            val code = obj.optString("kapt_code")
            if (code.isBlank()) return@mapNotNull null
            ServerApartment(
                kaptCode = code,
                name = obj.optString("kapt_name"),
                dong = obj.optString("dong_name"),
                bjdCode = obj.optString("bjd_code")
            )
        }
    } finally {
        connection.disconnect()
    }
}

@Composable
fun ServerSearchScreen(onSelect: (ServerApartment) -> Unit) {
    var query by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<String?>(null) }
    var searchSerial by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ServerApartment>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(request, searchSerial) {
        val keyword = request ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            results = searchApartments(keyword)
        } catch (e: Exception) {
            results = emptyList()
            error = e.message ?: "서버 연결 실패"
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("전국 아파트 단지검색", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("아파트명 입력") }, singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
        Button(onClick = { request = query.trim(); searchSerial++ },
            enabled = query.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "검색 중..." else "단지검색")
        }
        error?.let { Text("검색 실패: $it", color = MaterialTheme.colorScheme.error) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && request != null && error == null) Text("검색 결과 ${results.size}건")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.kaptCode }) { apt ->
                Card(onClick = { onSelect(apt) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(apt.name, fontWeight = FontWeight.Bold)
                        Text(apt.dong, style = MaterialTheme.typography.bodyMedium)
                        Text("단지코드 ${apt.kaptCode}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ServerApartmentDetail(apt: ServerApartment) {
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
