package uk.noammm.kav.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

// Live arrivals from curlbus.app (github.com/elad661/curlbus), a community relay of the Ministry of
// Transport's SIRI-SM feed. Kav+ falls back to it while Moovit refuses new sessions. Stops are keyed by
// their MOT stop code and lines by GTFS route_id, so every id here matches the on-phone timetable,
// not Moovit's numbering. It is one volunteer's server: ask for few stops, and not too often.
object Curlbus {
    private const val BASE = "https://curlbus.app/"
    const val MAX_STOPS = 12
    const val POLL_SECS = 30

    private val iso = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ssXXX", Locale.US) }

    class Result(
        val arrivals: Map<Moovit.ArrivalKey, Moovit.Arrival>,
        val lines: Map<Int, Moovit.LineInfo>,
        val modes: Map<Int, Int>,
    )

    // Stops to leave out, until a time (ms). One bad stop fails the whole batch: curlbus answers 404 for a
    // code it doesn't know and 500 for some stops it does, every time. Unknown codes stay out for a day,
    // failing ones for half an hour.
    private val skip = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun stopArrivals(codes: List<Int>, hebrew: Boolean): Result {
        val now = System.currentTimeMillis()
        val want = codes.filter { it > 0 && (skip[it] ?: 0L) < now }.distinct().take(MAX_STOPS)
        val found = if (want.isEmpty()) JSONObject() else visits(want)
        val arrivals = HashMap<Moovit.ArrivalKey, Moovit.Arrival>()
        val lines = HashMap<Int, Moovit.LineInfo>()
        val modes = HashMap<Int, Int>()
        for (stop in found.keys()) {
            val list = found.optJSONArray(stop) ?: continue
            val stopCode = stop.toIntOrNull() ?: continue
            for (i in 0 until list.length()) {
                val v = list.optJSONObject(i) ?: continue
                val eta = time(v.optString("eta")) ?: continue
                val routeId = v.optString("route_id").toIntOrNull() ?: continue
                val tripId = tripKey(v.optString("trip_id"))
                val loc = v.optJSONObject("location")
                val lat = loc?.optString("lat")?.toDoubleOrNull() ?: 0.0
                val lon = loc?.optString("lon")?.toDoubleOrNull() ?: 0.0
                val agency = v.optString("operator_id").toIntOrNull() ?: -1
                val a = Moovit.Arrival(
                    stopId = stopCode, lineId = routeId, tripId = tripId,
                    staticUtc = eta, rtUtc = eta, statisticalUtc = 0L,
                    status = 0, certainty = 0, traffic = 0, frequency = false, rtDropped = false,
                    tracked = lat != 0.0 && lon != 0.0, lat = lat, lon = lon,
                    vehicleId = v.optString("vehicle_ref"),
                    sampleUtc = time(v.optString("timestamp")) ?: 0L,
                )
                arrivals[a.key] = a
                if (routeId !in lines) {
                    val route = v.optJSONObject("static_info")?.optJSONObject("route")
                    val dest = route?.optJSONObject("destination")?.optJSONObject("name")
                    val head = route?.optJSONObject("headsign")
                    fun pick(o: JSONObject?) = o?.optString(if (hebrew) "HE" else "EN")?.takeIf { it.isNotBlank() }
                        ?: o?.optString("HE").orEmpty()
                    lines[routeId] = Moovit.LineInfo(
                        groupId = routeId, number = v.optString("line_name"), agencyId = agency,
                        origin = "", destination = pick(dest), caption = pick(head).replace('_', ' '),
                    )
                }
                if (agency >= 0) modes[agency] = modeOf(agency)
            }
        }
        return Result(arrivals, lines, modes)
    }

    // One stop's live arrivals for the departure board: line number, destination stop code and ETA (Unix s).
    class BoardArrival(
        val line: String, val destCode: Int, val etaUtc: Long, val tracked: Boolean,
        val lat: Double = 0.0, val lon: Double = 0.0, val vehicle: String = "",
    )

    fun boardArrivals(code: Int): List<BoardArrival>? {
        if (code <= 0 || (skip[code] ?: 0L) >= System.currentTimeMillis()) return null
        return boardArrivals(listOf(code))[code]
    }

    // Several stops in one request, keyed by stop code. Stops curlbus can't answer for are absent.
    fun boardArrivals(codes: List<Int>): Map<Int, List<BoardArrival>> {
        val now = System.currentTimeMillis()
        val want = codes.filter { it > 0 && (skip[it] ?: 0L) < now }.distinct().take(MAX_STOPS)
        if (want.isEmpty()) return emptyMap()
        val found = visits(want)
        return found.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to parseBoard(found.optJSONArray(k)) } }.toMap()
    }

    private fun parseBoard(list: org.json.JSONArray?): List<BoardArrival> {
        if (list == null) return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val v = list.optJSONObject(i) ?: return@mapNotNull null
            BoardArrival(
                line = v.optString("line_name"),
                destCode = v.optString("destination_id").toIntOrNull() ?: return@mapNotNull null,
                etaUtc = time(v.optString("eta")) ?: return@mapNotNull null,
                tracked = v.optJSONObject("location") != null,
                lat = v.optJSONObject("location")?.optString("lat")?.toDoubleOrNull() ?: 0.0,
                lon = v.optJSONObject("location")?.optString("lon")?.toDoubleOrNull() ?: 0.0,
                vehicle = v.optString("vehicle_ref"),
            )
        }
    }

    // Halves a batch that fails until the stops behind it are found, so the rest still come back.
    private fun visits(codes: List<Int>): JSONObject {
        val (code, body) = fetch(codes)
        val o = runCatching { JSONObject(body) }.getOrNull()
        o?.optJSONObject("visits")?.let { return it }
        val errors = o?.optJSONArray("errors")
        val unknown = (0 until (errors?.length() ?: 0)).mapNotNull { i ->
            Regex("Invalid stop code (\\d+)").find(errors!!.optString(i))?.groupValues?.get(1)?.toIntOrNull()
        }
        if (unknown.isNotEmpty()) {
            unknown.forEach { skip[it] = System.currentTimeMillis() + 24 * 3600_000L }
            val rest = codes - unknown.toSet()
            return if (rest.isEmpty()) JSONObject() else visits(rest)
        }
        if (code < 500) throw java.io.IOException("curlbus HTTP $code")
        if (codes.size == 1) {
            skip[codes[0]] = System.currentTimeMillis() + 30 * 60_000L
            return JSONObject()
        }
        val merged = JSONObject()
        for (half in codes.chunked((codes.size + 1) / 2)) {
            val part = visits(half)
            for (k in part.keys()) merged.put(k, part.get(k))
        }
        return merged
    }

    private fun fetch(codes: List<Int>): Pair<Int, String> {
        val c = (URL(BASE + codes.joinToString("+")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "KavPlus (github.com/vascc124/kav)")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: throw java.io.IOException("curlbus HTTP $code")
        return code to body
    }

    // Unix seconds, as Moovit.Arrival carries them.
    private fun time(s: String?): Long? =
        s?.takeIf { it.isNotBlank() }?.let { runCatching { iso.get()!!.parse(it)?.time?.div(1000) }.getOrNull() }

    // GTFS trip ids look like "43202200_041026": the number before the date is unique within a day.
    private fun tripKey(id: String): Long =
        id.substringBefore('_').toLongOrNull() ?: (id.hashCode().toLong() and 0xffffffffL)

    // MOT operator ids: 2 is Israel Railways, 21 and 37 run the light rail lines; the rest are buses.
    private fun modeOf(agency: Int) = when (agency) {
        2 -> 2
        21, 37 -> 0
        else -> 3
    }
}
