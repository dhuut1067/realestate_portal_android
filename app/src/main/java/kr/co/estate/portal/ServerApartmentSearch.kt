package kr.co.estate.portal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            Column(
                Modifier.fillMaxWidth().height(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("서버에서 목록을 불러오는 중입니다.") }
                failure?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.heightIn(max = 64.dp).verticalScroll(rememberScrollState()))
                }
                if (!loading && options.isEmpty() && failure == null) Text("해당 지역의 등록 목록이 없습니다.")
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
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
    val context = LocalContext.current
    var apartmentRetry by remember { mutableIntStateOf(0) }
    var focusRequest by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val sidos = remember(context) { LocalRegionIndex.sidos(context) }
    val sigungus = remember(context, sido?.code) {
        LocalRegionIndex.sigungus(context, sido?.code.orEmpty())
    }
    val dongs = remember(context, sido?.code, sigungu?.code) {
        LocalRegionIndex.dongs(context, sido?.code.orEmpty(), sigungu?.code.orEmpty())
    }
    val apartments = rememberOptions(dong?.code, apartmentRetry) { getApartments(dong!!.code) }
    val region = listOfNotNull(sido?.name, sigungu?.name, dong?.name).joinToString(" ")
    // More detailed initial views for small districts, wider views for large provinces.
    val initialZoom = when {
        apartment != null -> 17
        dong != null -> 16
        sigungu != null -> 16
        sido?.name.orEmpty() in listOf("서울특별시", "부산광역시", "인천광역시", "대구광역시",
            "대전광역시", "광주광역시", "울산광역시") -> 16
        sido?.name == "세종특별자치시" -> 16
        else -> 16
    }
    // Let the search form scroll in its own space; a long error must never hide controls.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mapHeight = (maxHeight * 0.48f).coerceIn(150.dp, 330.dp)
        Column(Modifier.fillMaxSize()) {
            KakaoApartmentMap(apartment, region, if (dong != null) 3 else if (sigungu != null) 2 else 1,
                focusRequest, mapHeight, showMap, onSelect, initialZoom,
                sido?.name.orEmpty(), sigungu?.code.orEmpty(), dong?.name)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("지역을 선택하면 지도 중심과 화면 안 실거래 단지를 갱신합니다.", style = MaterialTheme.typography.bodySmall)
                ChoiceField("시도", sido, sidos, { it.name }, true, false, null, { }) {
                    sido = it; sigungu = null; dong = null; apartment = null; focusRequest++
                }
                ChoiceField("시군구", sigungu, sigungus, { it.name }, sido != null,
                    false, null, { }) {
                    sigungu = it; dong = null; apartment = null; focusRequest++
                }
                ChoiceField("읍면동", dong, dongs, { it.name }, sigungu != null,
                    false, null, { }) {
                    dong = it; apartment = null; focusRequest++
                }
                ChoiceField("아파트", apartment, apartments.rows, { it.name }, dong != null,
                    apartments.loading, apartments.failure, { apartmentRetry++ }) {
                    apartment = it; focusRequest++
                }
                Button(onClick = { focusRequest++; message = "선택한 지역으로 지도를 이동합니다.\\n$region" }, enabled = sido != null, modifier = Modifier.fillMaxWidth()) {
                    Text("선택 위치로 지도 이동")
                }
                if (apartment != null) OutlinedButton(onClick = { apartment?.let(onSelect) }, modifier = Modifier.fillMaxWidth()) {
                    Text("선택 단지 실거래 보기")
                }
                apartments.failure?.let { Text("단지 조회 실패: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        message?.let { current ->
            AppMessageDialog(title = "안내 메시지", message = current,
                onDismiss = { message = null })
        }
    }
}
