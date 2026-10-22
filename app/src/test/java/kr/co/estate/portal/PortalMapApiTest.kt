package kr.co.estate.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortalMapApiTest {
    // Actual Django viewport schema: amount is 만원 and area is m².
    private fun complex(code: String, price: Any = 252000) = JSONObject().apply {
        put("kind", "complex"); put("kapt_code", code); put("apt_name", "경희궁의아침4단지")
        put("sgg_cd", "11110"); put("dong_name", "내수동")
        put("latitude", 37.5726145); put("longitude", 126.972273)
        put("latestPrice", price); put("representativeArea", 150.77)
        put("latestTradeDate", "2025-08-18"); put("tradeCount", 69)
    }
    @Test fun webViewportPriceAndAreaAreNotRescaledIncorrectly() {
        val apt = parseApartment(complex("A10021295"))!!
        assertEquals("경희궁의아침4단지", apt.name)
        assertEquals("11110", apt.bjdCode)
        assertEquals("25억 2,000만", mapPrice(apt.latestPrice))
        assertEquals("전용 45.6평 · 150.8m²", mapArea(apt.representativeArea))
        assertEquals("3,500만", mapPrice(3500.0))
        assertEquals("10억", mapPrice(100000.0))
    }
    @Test fun kaptDropdownAndObjectEnvelopeAreSupported() {
        val row = JSONObject("""{"kapt_code":"A11087101","kapt_name":"경희궁의아침2단지","bjd_code":"1111011800","dong_name":"내수동"}""")
        assertEquals("경희궁의아침2단지", parseApartment(row)!!.name)
        assertNull(parseApartment(row)!!.latitude)
        assertEquals(1, jsonRows(JSONObject().put("items", JSONArray().put(row))).length())
        assertNull(parseApartment(JSONObject().put("kind", "region").put("name", "종로구")))
    }
    @Test fun saturatedViewportIsSplitAndSharedBoundaryIsDeduplicated() = runBlocking {
        var calls = 0
        val result = loadViewportApartments(MapBounds(126.9, 37.5, 127.1, 37.6)) {
            calls++
            val rows = JSONArray()
            when (calls) {
                1 -> repeat(500) { rows.put(complex("saturated-$it")) }
                2 -> { rows.put(complex("west")); rows.put(complex("boundary")) }
                3 -> { rows.put(complex("east")); rows.put(complex("boundary")) }
            }
            JSONObject().put("items", rows)
        }
        assertEquals(3, calls)
        assertEquals(setOf("west", "east", "boundary"), result.map { it.kaptCode }.toSet())
    }
    @Test fun noTradePriceIsNotInventedAndCancellationPropagates() = runBlocking {
        val result = loadViewportApartments(MapBounds(126.9, 37.5, 127.1, 37.6)) {
            JSONArray().put(complex("valid")).put(complex("missing", JSONObject.NULL))
        }
        assertEquals(listOf("valid"), result.map { it.kaptCode })
        try {
            loadViewportApartments(MapBounds(126.9, 37.5, 127.1, 37.6)) { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
