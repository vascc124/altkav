package uk.noammm.kav.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Place and address search from OpenStreetMap through Komoot's Photon (photon.komoot.io), which is made
// for search-as-you-type. Kav+ asks it while Moovit refuses new sessions. Private search holds here too:
// Photon is told the stand-in (a town's centre or a chosen place), not where the rider is.
object Photon {
    private const val BASE = "https://photon.komoot.io/api/"
    private const val ISRAEL_BBOX = "34.2,29.4,35.95,33.35"

    fun searchPlaces(query: String, at: Pair<Double, Double>?): List<Moovit.Place> {
        if (query.isBlank()) return emptyList()
        val bias = if (Moovit.shareLocation) at else Moovit.standIn()
        val url = buildString {
            append(BASE).append("?q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
            append("&limit=6&bbox=").append(ISRAEL_BBOX)
            bias?.let { (la, lo) -> append("&lat=").append(la).append("&lon=").append(lo) }
        }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "AltKavPlus (github.com/vascc124/altkav)")
        }
        if (c.responseCode != 200) throw java.io.IOException("Photon HTTP ${c.responseCode}")
        val root = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        val features = root.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Moovit.Place>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            val xy = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val name = p.optString("name").ifBlank { street }
            if (name.isBlank()) continue
            val city = p.optString("city").ifBlank { p.optString("county") }
            out.add(Moovit.Place(
                name = name,
                detail = listOf(street.takeIf { it != name }, city).filter { !it.isNullOrBlank() }.joinToString(", "),
                lat = xy.optDouble(1), lon = xy.optDouble(0),
            ))
        }
        // The same place often comes back as several OSM objects (a building, its entrance, the address).
        return out.distinctBy { "${it.name}|${it.detail}" }.take(5)
    }
}
