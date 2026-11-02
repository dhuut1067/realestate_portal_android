package kr.co.estate.portal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline administrative region choices from the Django portal's region_index.json.
 * The index is a snapshot of regions with registered complexes, not every legal dong.
 * Live apartment/transaction data still comes from Django.
 */
internal object LocalRegionIndex {
    private var root: JSONObject? = null

    private fun document(context: Context): JSONObject {
        root?.let { return it }
        return synchronized(this) {
            root ?: JSONObject(context.assets.open("region_index.json").bufferedReader(Charsets.UTF_8)
                .use { it.readText() }).also { root = it }
        }
    }

    private fun JSONArray.options(): List<RegionOption> = (0 until length()).mapNotNull { i ->
        val item = optJSONObject(i) ?: return@mapNotNull null
        val code = item.optString("code")
        val name = item.optString("name")
        if (code.isBlank() || name.isBlank()) null else RegionOption(code, name)
    }

    fun sidos(context: Context): List<RegionOption> =
        document(context).optJSONArray("provinces")?.options().orEmpty()

    fun sigungus(context: Context, sidoCode: String): List<RegionOption> {
        val provinces = document(context).optJSONArray("provinces") ?: return emptyList()
        val province = (0 until provinces.length()).mapNotNull { provinces.optJSONObject(it) }
            .firstOrNull { it.optString("code") == sidoCode } ?: return emptyList()
        return province.optJSONArray("districts")?.options().orEmpty()
    }

    fun dongs(context: Context, sidoCode: String, sigunguCode: String): List<RegionOption> {
        val provinces = document(context).optJSONArray("provinces") ?: return emptyList()
        val province = (0 until provinces.length()).mapNotNull { provinces.optJSONObject(it) }
            .firstOrNull { it.optString("code") == sidoCode } ?: return emptyList()
        val districts = province.optJSONArray("districts") ?: return emptyList()
        val district = (0 until districts.length()).mapNotNull { districts.optJSONObject(it) }
            .firstOrNull { it.optString("code") == sigunguCode } ?: return emptyList()
        return district.optJSONArray("localities")?.options().orEmpty()
    }
}
