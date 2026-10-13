package kr.co.estate.portal

data class Apartment(val id: String, val name: String, val address: String,
    val households: Int, val year: Int, val area: Double, val sale: String,
    val rent: String, val monthly: String, val prices: List<Float>, val counts: List<Int>)
data class Listing(val id: String, val apartmentId: String, val type: String,
    val price: String, val area: Double, val floor: String, val note: String)

// UI 확인용 예시입니다. 실거래/매물 데이터로 게시하면 안 됩니다.
interface PortalRepository {
    fun apartments(): List<Apartment>
    fun listings(): List<Listing>
}
class ExamplePortalRepository : PortalRepository {
    override fun apartments() = listOf(
        Apartment("A60809003", "오륙도 SK VIEW", "부산 남구 용호동", 3000, 2008, 84.9,
            "6억 8,000", "3억 5,000", "5,000 / 110", listOf(6.1f,6.3f,6.2f,6.7f,6.5f,6.8f), listOf(4,8,5,12,7,10)),
        Apartment("example-beach", "남천 삼익비치", "부산 수영구 남천동", 3060, 1979, 99.0,
            "11억 9,000", "2억 8,000", "3,000 / 90", listOf(10.5f,10.8f,11.2f,11f,11.6f,11.9f), listOf(6,4,8,5,9,7)),
        Apartment("example-haeundae", "해운대 예시단지", "부산 해운대구 우동", 800, 2020, 59.0,
            "5억 2,000", "3억", "3,000 / 100", listOf(4.8f,4.9f,5f,5.1f,5f,5.2f), listOf(3,5,2,6,4,7)),
        Apartment("example-seoul", "서울 예시단지", "서울 강남구 대치동", 1200, 2015, 114.0,
            "24억", "12억", "1억 / 350", listOf(21f,22f,21.5f,23f,23.5f,24f), listOf(2,4,3,6,4,5))
    )
    override fun listings() = listOf(
        Listing("demo1", "A60809003", "매매", "6억 8,000", 84.9, "중층 / 30층", "남향 · 바다 조망 · 입주 협의"),
        Listing("demo2", "A60809003", "전세", "3억 5,000", 84.9, "고층 / 30층", "수리 완료 · 입주 협의"),
        Listing("demo3", "example-beach", "매매", "11억 9,000", 99.0, "중층 / 12층", "입주 협의"),
        Listing("demo4", "example-haeundae", "월세", "3,000 / 100", 59.0, "고층 / 25층", "입주 협의")
    )
}
