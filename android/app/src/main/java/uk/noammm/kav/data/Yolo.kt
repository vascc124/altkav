package uk.noammm.kav.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.ln
import kotlin.math.round
import uk.noammm.kav.ui.ISRAEL

// AltKav+: YOLO modes. Places worth a trip (OpenStreetMap, tools/yolo_places.py) ranked by how interesting they
// are and how soon public transport gets you there, with the weather for the day in view.
object Yolo {
    enum class Mode { SHABBAT, NATURE, LINE, SURPRISE }

    class Place(val he: String, val en: String, val kind: String, val lat: Double, val lon: Double, val size: Int) {
        val name: String get() = if (uk.noammm.kav.ui.T.rtl) he.ifBlank { en } else en.ifBlank { he }
    }

    @Volatile private var places: List<Place>? = null

    fun places(ctx: Context): List<Place> = places ?: synchronized(this) {
        places ?: runCatching {
            val a = JSONArray(ctx.assets.open("yolo_places.json").use { it.readBytes().toString(Charsets.UTF_8) })
            (0 until a.length()).map { i ->
                val p = a.getJSONArray(i)
                Place(p.getString(0), p.getString(1), p.getString(2), p.getDouble(3), p.getDouble(4), p.optInt(5))
            }
        }.getOrDefault(emptyList()).also { places = it }
    }

    val NATURE = setOf("reserve", "park", "beach", "water", "forest", "view")

    // How much of a trip a kind of place is; big parks and reserves more so.
    private fun interest(p: Place): Double {
        val base = when (p.kind) {
            "reserve" -> 3.0; "water" -> 3.0; "beach" -> 2.5; "view" -> 2.0; "forest" -> 2.0
            "museum" -> 2.0; "zoo" -> 2.0; "park" -> 1.4; else -> 1.4
        }
        return base + if (p.size > 0) ln(1.0 + p.size / 20.0) * 0.5 else 0.0
    }

    class Day(val dateUtc: Long, val code: Int, val tMax: Int, val tMin: Int, val rain: Int) {
        val rainy get() = rain >= 50 || code in 51..99
        val hot get() = tMax >= 32
        val icon get() = when {
            code >= 95 -> "⛈"; code in 51..86 -> "🌧"; code in 45..48 -> "🌫"; code in 2..3 -> "⛅"; code == 1 -> "🌤"; else -> "☀"
        }
    }

    // Rain sends people indoors, heat to the water, a mild sunny day outdoors.
    private fun weather(p: Place, d: Day?): Double = when {
        d == null -> 0.0
        d.rainy -> if (p.kind in setOf("museum", "zoo", "attraction")) 2.0 else if (p.kind in NATURE) -1.5 else 0.0
        d.hot -> if (p.kind in setOf("beach", "water")) 2.0 else if (p.kind in setOf("view", "reserve")) -0.5 else 0.0
        else -> if (p.kind in NATURE) 0.8 else 0.0
    }

    /** The places of [mode] reachable from [from] at [atMs], best first. */
    fun suggest(
        ctx: Context, net: Net, mode: Mode, from: Pair<Double, Double>, atMs: Long,
        walkM: Double, nearFirst: Boolean, day: Day?,
    ): List<OfflinePlanner.Reached> {
        val all = places(ctx)
        val pool = when (mode) {
            Mode.NATURE -> all.filter { it.kind in NATURE && (it.kind != "park" || it.size >= 5) }
            else -> all
        }
        val r = OfflinePlanner.reach(net, from, atMs)
        val reached = OfflinePlanner.reachPlaces(net, r, from, pool, walkM).filter { it.minutes in 3..180 }
        // Names repeat (a reserve and its beach, a park in pieces): keep the quickest of each.
        val unique = reached.groupBy { it.place.he.ifBlank { it.place.en } }.values.map { g -> g.minBy { it.minutes } }
        // An outing is a trip: a garden down the street is no outing, an hour on the bus to a national park is.
        fun outing(r: OfflinePlanner.Reached) = when {
            r.line.isBlank() && r.place.kind != "beach" -> -1.0
            r.place.kind == "park" && r.place.size < 20 -> -0.8
            else -> 0.0
        }
        val ranked = if (nearFirst) unique.sortedBy { it.minutes }
        else unique.sortedByDescending { interest(it.place) + weather(it.place, day) + outing(it) - it.minutes / 40.0 }
        return ranked.take(40)
    }

    /** A random good pick for "surprise me": one of the top few, so it is never a dud. */
    fun surprise(list: List<OfflinePlanner.Reached>): OfflinePlanner.Reached? {
        // In Hebrew, a place known only by an English name ("TV lookout") makes a poor surprise.
        val named = if (uk.noammm.kav.ui.T.rtl) list.filter { it.place.he.any { c -> c in 'א'..'ת' } } else list
        return named.ifEmpty { list }.take(12).randomOrNull()
    }

    // ---- forecast (Open-Meteo, no key; told only a rounded location) ----

    @Volatile private var forecast: Pair<Long, List<Day>>? = null

    fun forecast(from: Pair<Double, Double>): List<Day> {
        forecast?.takeIf { System.currentTimeMillis() - it.first < 3600_000 }?.let { return it.second }
        val lat = round(from.first * 10) / 10; val lon = round(from.second * 10) / 10
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FJerusalem&forecast_days=7"
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        if (c.responseCode != 200) return emptyList()
        val d = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = ISRAEL }
        val days = (0 until dates.length()).map { i ->
            Day(fmt.parse(dates.getString(i))!!.time / 1000, d.getJSONArray("weather_code").optInt(i),
                d.getJSONArray("temperature_2m_max").optDouble(i).toInt(), d.getJSONArray("temperature_2m_min").optDouble(i).toInt(),
                d.getJSONArray("precipitation_probability_max").optInt(i))
        }
        forecast = System.currentTimeMillis() to days
        return days
    }

    fun dayOf(days: List<Day>, atMs: Long): Day? {
        val c = Calendar.getInstance(ISRAEL).apply { timeInMillis = atMs; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return days.firstOrNull { kotlin.math.abs(it.dateUtc - c.timeInMillis / 1000) < 6 * 3600 }
    }

    /** Now, or 08:00 when it is the small hours: nobody sets out for a park at 3 AM. */
    fun dayStart(nowMs: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance(ISRAEL).apply { timeInMillis = nowMs }
        if (c.get(Calendar.HOUR_OF_DAY) >= 6) return nowMs
        c.set(Calendar.HOUR_OF_DAY, 8); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0)
        return c.timeInMillis
    }

    /** The coming Saturday at 10:00 (today's if it is Saturday before 15:00, else now on a Saturday). */
    fun nextShabbat(nowMs: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance(ISRAEL).apply { timeInMillis = nowMs }
        if (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY && c.get(Calendar.HOUR_OF_DAY) in 9..14) return nowMs
        do { c.add(Calendar.DAY_OF_MONTH, 1) } while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY)
        c.set(Calendar.HOUR_OF_DAY, 10); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0)
        return c.timeInMillis
    }
}
