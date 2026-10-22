package kr.co.estate.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal const val DJANGO_BASE_URL = "http://cncvision.ipdisk.co.kr:8001"
internal fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")

data class ServerApartment(
    val kaptCode: String,
    val name: String,
    val dong: String,
    val bjdCode: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val latestPrice: Double? = null, // Django returns 만원, NOT 원.
    val representativeArea: Double? = null, // 전용면적 m², NOT 공급면적.
    val latestTradeDate: String = "",
    val tradeCount: Int = 0
) {
    val identity: String get() = kaptCode.ifBlank { "$bjdCode|$dong|$name" }
}

internal data class RegionOption(val code: String, val name: String)
internal data class MapBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun split(): List<MapBounds> = if (east - west >= north - south) {
        val middle = (west + east) / 2
        listOf(copy(east = middle), copy(west = middle))
    } else {
        val middle = (south + north) / 2
        listOf(copy(north = middle), copy(south = middle))
    }
}

internal suspend fun portalJson(path: String): Any = withContext(Dispatchers.IO) {
    val connection = URL("$DJANGO_BASE_URL$path").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        currentCoroutineContext().ensureActive()
        if (code !in 200..299) error("HTTP $code · $path · ${body.take(100)}")
        val result = JSONTokener(body).nextValue()
        if (result is JSONObject && result.has("ok") && !result.optBoolean("ok")) {
            error(result.optString("error", "서버 조회 실패"))
        }
        result
    } finally { connection.disconnect() }
}

internal fun jsonRows(payload: Any): JSONArray = when (payload) {
    is JSONArray -> payload
    is JSONObject -> payload.optJSONArray("results") ?: payload.optJSONArray("items")
        ?: error("서버 목록 형식이 다릅니다.")
    else -> error("JSON 목록을 받지 못했습니다.")
}

internal fun JSONObject.text(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
}.orEmpty()
internal fun JSONObject.number(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
    optDouble(key, Double.NaN).takeIf { it.isFinite() }
}
internal fun parseApartment(item: JSONObject): ServerApartment? {
    val name = item.text("apt_name", "kapt_name", "name", "canonical_name")
    if (name.isBlank() || item.text("kind") == "region") return null
    return ServerApartment(item.text("kapt_code", "kaptCode", "code"), name,
        item.text("dong_name", "dong", "umdNm"), item.text("bjd_code", "bjdCode", "sgg_cd"),
        item.number("latitude", "lat")?.takeIf { it in -90.0..90.0 },
        item.number("longitude", "lng", "lon")?.takeIf { it in -180.0..180.0 },
        item.number("latestPrice", "latest_price")?.takeIf { it > 0 },
        item.number("representativeArea", "representative_area")?.takeIf { it > 0 },
        item.text("latestTradeDate", "latest_trade_date"), item.optInt("tradeCount", 0))
}
internal suspend fun getRegions(path: String): List<RegionOption> {
    val rows = jsonRows(portalJson(path))
    return (0 until rows.length()).mapNotNull { i ->
        val row = rows.optJSONObject(i) ?: return@mapNotNull null
        val code = row.text("code", "bjd_code")
        val name = row.text("name", "sigungu", "dong_name")
        if (code.isBlank() || name.isBlank()) null else RegionOption(code, name)
    }.distinctBy { it.code }
}
internal suspend fun getApartments(dongCode: String): List<ServerApartment> {
    val rows = jsonRows(portalJson("/api/apartment-options/?dong_code=${encoded(dongCode)}"))
    return (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::parseApartment) }
        .distinctBy { it.identity }
}

// Use exactly the Django web map's apartment_v2 viewport API. Force individual
// complexes (web level=3); otherwise a zoomed-out response contains region clusters.
// The API caps at 500 and has no offset. Subdivide saturated bounds so complexes
// after the first 500 are not silently lost. Deduplicate shared tile boundaries.
internal suspend fun loadViewportApartments(
    bounds: MapBounds, fetch: suspend (String) -> Any = ::portalJson
): List<ServerApartment> {
    val apartments = linkedMapOf<String, ServerApartment>()
    suspend fun load(tile: MapBounds, depth: Int) {
        currentCoroutineContext().ensureActive()
        val rows = jsonRows(fetch("/api/apartments-in-view/?west=${tile.west}&south=${tile.south}" +
            "&east=${tile.east}&north=${tile.north}&level=3&limit=500&years=20"))
        if (rows.length() >= 500) {
            check(depth < 14) { "밀집 지역의 전체 단지를 받지 못했습니다. 지도를 확대해 다시 조회하십시오." }
            for (child in tile.split()) load(child, depth + 1)
        } else {
            for (i in 0 until rows.length()) {
                val apartment = rows.optJSONObject(i)?.let(::parseApartment) ?: continue
                // Show the same real trade summary as the web. Never invent a price.
                if (apartment.latitude != null && apartment.longitude != null && apartment.latestPrice != null) {
                    apartments[apartment.identity] = apartment
                }
            }
        }
    }
    load(bounds, 0)
    return apartments.values.toList()
}

internal suspend fun resolveApartment(apartment: ServerApartment): ServerApartment {
    if (apartment.latitude != null && apartment.longitude != null) return apartment
    val rows = jsonRows(portalJson("/api/app/apartments/search/?q=${encoded(apartment.name)}&limit=200"))
    val candidates = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::parseApartment) }
    val match = candidates.firstOrNull { apartment.kaptCode.isNotBlank() && it.kaptCode == apartment.kaptCode }
        ?: candidates.firstOrNull { it.name == apartment.name && it.bjdCode.take(5) == apartment.bjdCode.take(5) && it.dong == apartment.dong }
    return match ?: apartment
}
internal fun mapPrice(amount: Double?): String {
    if (amount == null) return "실거래 미상"
    val value = amount.toLong()
    val eok = value / 10000
    val remainder = value % 10000
    return if (eok > 0) "${eok}억" + if (remainder > 0) " ${String.format(Locale.KOREA, "%,d", remainder)}만" else ""
    else "${String.format(Locale.KOREA, "%,d", value)}만"
}
internal fun mapArea(area: Double?): String = area?.let {
    String.format(Locale.KOREA, "전용 %.1f평 · %.1fm²", it / 3.305785, it)
} ?: "전용면적 미상"
