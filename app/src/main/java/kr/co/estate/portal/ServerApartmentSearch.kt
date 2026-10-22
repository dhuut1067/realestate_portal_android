package kr.co.estate.portal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

// Buttons depend on their parent selection, never on a successful network result.
// An empty/error response remains actionable and can be retried inside the dialog.
@Composable
private fun <T> ChoiceField(
    title: String, value: T?, options: List<T>, titleOf: (T) -> String,
    enabled: Boolean, loading: Boolean, failure: String?, retry: () -> Unit,
    onChoose: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$title: ${value?.let(titleOf) ?: if (loading) "조회 중…" else "선택하십시오"}")
    }
    if (open && enabled) AlertDialog(
        onDismissRequest = { open = false }, title = { Text("$title 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("서버에서 목록을 불러오는 중입니다.") }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loading && options.isEmpty() && failure == null) Text("해당 지역의 등록 목록이 없습니다.")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(options.size) { i ->
                        val option = options[i]
                        TextButton(onClick = { open = false; onChoose(option) }, modifier = Modifier.fillMaxWidth()) {
                            Text(titleOf(option))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("닫기") } },
        dismissButton = { TextButton(onClick = retry, enabled = !loading) { Text("다시 조회") } }
    )
}

private data class Options<T>(val rows: List<T> = emptyList(), val loading: Boolean = true, val failure: String? = null)

@Composable
private fun <T> rememberOptions(key: String?, retry: Int, load: suspend () -> List<T>): Options<T> {
    var state by remember(key) { mutableStateOf(Options<T>(loading = key != null)) }
    LaunchedEffect(key, retry) {
        if (key == null) return@LaunchedEffect
        state = Options(loading = true)
        try { state = Options(rows = load(), loading = false) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { state = Options(loading = false, failure = e.message ?: "조회 실패") }
    }
    return state
}

@Composable
fun ServerSearchScreen(showMap: Boolean = true, onSelect: (ServerApartment) -> Unit) {
    var sido by remember { mutableStateOf<RegionOption?>(RegionOption("11", "서울특별시")) }
    var sigungu by remember { mutableStateOf<RegionOption?>(null) }
    var dong by remember { mutableStateOf<RegionOption?>(null) }
    var apartment by remember { mutableStateOf<ServerApartment?>(null) }
    var sidoRetry by remember { mutableIntStateOf(0) }
    var sigunguRetry by remember { mutableIntStateOf(0) }
    var dongRetry by remember { mutableIntStateOf(0) }
    var apartmentRetry by remember { mutableIntStateOf(0) }
    var focusRequest by remember { mutableIntStateOf(0) }
    val sidos = rememberOptions("sidos", sidoRetry) { getRegions("/api/sidos/") }
    val sigungus = rememberOptions(sido?.code, sigunguRetry) {
        getRegions("/api/sigungus/?sido_code=${encoded(sido!!.code)}&sido=${encoded(sido!!.name)}")
    }
    val dongs = rememberOptions(sigungu?.code, dongRetry) {
        getRegions("/api/dongs/?sigungu_code=${encoded(sigungu!!.code)}&sgg_cd=${encoded(sigungu!!.code)}")
    }
    val apartments = rememberOptions(dong?.code, apartmentRetry) { getApartments(dong!!.code) }
    val region = listOfNotNull(sido?.name, sigungu?.name, dong?.name).joinToString(" ")
    // Let the search form scroll in its own space; a long error must never hide controls.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mapHeight = (maxHeight * 0.48f).coerceIn(150.dp, 330.dp)
        Column(Modifier.fillMaxSize()) {
            KakaoApartmentMap(apartment, region, if (dong != null) 3 else if (sigungu != null) 2 else 1,
                focusRequest, mapHeight, showMap, onSelect)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("지역을 선택하면 지도 중심과 화면 안 실거래 단지를 갱신합니다.", style = MaterialTheme.typography.bodySmall)
                ChoiceField("시도", sido, sidos.rows, { it.name }, true, sidos.loading, sidos.failure, { sidoRetry++ }) {
                    sido = it; sigungu = null; dong = null; apartment = null; focusRequest++
                }
                ChoiceField("시군구", sigungu, sigungus.rows, { it.name }, sido != null,
                    sigungus.loading, sigungus.failure, { sigunguRetry++ }) {
                    sigungu = it; dong = null; apartment = null; focusRequest++
                }
                ChoiceField("읍면동", dong, dongs.rows, { it.name }, sigungu != null,
                    dongs.loading, dongs.failure, { dongRetry++ }) {
                    dong = it; apartment = null; focusRequest++
                }
                ChoiceField("아파트", apartment, apartments.rows, { it.name }, dong != null,
                    apartments.loading, apartments.failure, { apartmentRetry++ }) {
                    apartment = it; focusRequest++
                }
                Button(onClick = { focusRequest++ }, enabled = sido != null, modifier = Modifier.fillMaxWidth()) {
                    Text("선택 위치로 지도 이동")
                }
                if (apartment != null) OutlinedButton(onClick = { apartment?.let(onSelect) }, modifier = Modifier.fillMaxWidth()) {
                    Text("선택 단지 실거래 보기")
                }
                listOfNotNull(sidos.failure, sigungus.failure, dongs.failure, apartments.failure).firstOrNull()?.let {
                    Text("지역 조회 실패: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
