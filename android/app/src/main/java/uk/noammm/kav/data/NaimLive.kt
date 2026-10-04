package uk.noammm.kav.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.zip.ZipInputStream
import uk.noammm.kav.ui.ISRAEL

// Kav+: live Na'im BaSofash buses. The MOT SIRI feed (and so curlbus) doesn't carry these municipal lines;
// the Tel Aviv municipality publishes them as open data instead (opendatasource.tel-aviv.gov.il, item 21):
// a static GTFS and a GTFS-RT vehicle-positions feed, refreshed every 10 s during service, whose trip ids
// are the static feed's. A vehicle's position on its trip gives its delay; the delay moves the trip's
// timetable at the stops still ahead of it. Asked only during the weekend service hours.
object NaimLive {
    private const val RT = "https://api.goryde.com/api/v1/core/c/101/gtfs-rt/vehicle-positions"
    private const val GTFS = "https://opendatasource.tel-aviv.gov.il/OpenData_Ducaments/gtfs.zip"
    private const val ASSET = "naim-gtfs.zip"
    private const val WEEK_MS = 7 * 24 * 3600_000L

    // ---- the static feed: just what turning positions into times needs ----

    private class StopTime(val code: Int, val lat: Double, val lon: Double, val sec: Int, val name: String = "")
    private class Trip(val short: String, val times: List<StopTime>)
    private class Static(val trips: Map<String, Trip>)

    @Volatile private var static: Static? = null

    // Set once at start, so the live data paths that have no Context of their own can read the timetable.
    @Volatile var app: Context? = null

    private fun loadStatic(ctx: Context): Static? = static ?: synchronized(this) {
        static ?: runCatching {
            val cached = File(ctx.filesDir, "naim/gtfs.zip")
            val bytes = if (cached.exists()) cached.readBytes() else ctx.assets.open(ASSET).use { it.readBytes() }
            parse(bytes)
        }.getOrNull()?.also { static = it }
    }

    /** Fetches the municipality's static feed when the copy in hand is a week old. Its trip ids change. */
    fun refreshStatic(ctx: Context) {
        val f = File(ctx.filesDir, "naim/gtfs.zip")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < WEEK_MS) return
        val bytes = get(GTFS)
        val parsed = parse(bytes)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        static = parsed
    }

    private fun csv(text: String): List<Map<String, String>> {
        val lines = text.removePrefix("﻿").split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val head = row(lines[0])
        return lines.drop(1).map { l -> val v = row(l); head.indices.associate { head[it] to v.getOrElse(it) { "" } } }
    }

    private fun row(l: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < l.length) {
            val c = l[i]
            when {
                q && c == '"' && i + 1 < l.length && l[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun hms(s: String): Int {
        val p = s.trim().split(':'); return p[0].toInt() * 3600 + p[1].toInt() * 60 + p.getOrElse(2) { "0" }.toInt()
    }

    private fun parse(zip: ByteArray): Static {
        val files = HashMap<String, String>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name in setOf("routes.txt", "trips.txt", "stops.txt", "stop_times.txt")) files[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        val routes = csv(files["routes.txt"].orEmpty()).associate { it["route_id"]!! to it["route_short_name"].orEmpty().trim() }
        val stops = csv(files["stops.txt"].orEmpty()).associate {
            it["stop_id"]!! to (Triple(it["stop_code"]?.toIntOrNull() ?: 0, it["stop_lat"]?.toDoubleOrNull() ?: 0.0, it["stop_lon"]?.toDoubleOrNull() ?: 0.0) to it["stop_name"].orEmpty())
        }
        val tripRoute = csv(files["trips.txt"].orEmpty()).associate { it["trip_id"]!! to (routes[it["route_id"]] ?: "") }
        val seqs = HashMap<String, MutableList<Pair<Int, StopTime>>>()
        for (r in csv(files["stop_times.txt"].orEmpty())) {
            val tid = r["trip_id"] ?: continue
            val (st, name) = stops[r["stop_id"]] ?: continue
            val sec = runCatching { hms(r["arrival_time"].orEmpty().ifBlank { r["departure_time"].orEmpty() }) }.getOrNull() ?: continue
            seqs.getOrPut(tid) { ArrayList() }.add((r["stop_sequence"]?.toIntOrNull() ?: 0) to StopTime(st.first, st.second, st.third, sec, name))
        }
        return Static(seqs.mapValues { (tid, v) -> Trip(tripRoute[tid] ?: "", v.sortedBy { it.first }.map { it.second }) })
    }

    // ---- the realtime feed ----

    private class Vehicle(val tripId: String, val lat: Double, val lon: Double, val stopSeq: Int, val stopId: String, val ts: Long, val startDate: String)

    // Hand-rolled protobuf for the few GTFS-RT fields used (FeedMessage.entity.vehicle.{trip,position,...}).
    private class Pb(private val b: ByteArray, private var p: Int = 0, private val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[p++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 } }
        fun tag(): Pair<Int, Int> { val t = varint().toInt(); return (t ushr 3) to (t and 7) }
        fun bytes(): Pb { val n = varint().toInt(); val sub = Pb(b, p, p + n); p += n; return sub }
        fun str(): String { val n = varint().toInt(); val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
        fun f32(): Float { val v = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24); p += 4; return Float.fromBits(v) }
        fun skip(wire: Int) { when (wire) { 0 -> varint(); 1 -> p += 8; 2 -> { val n = varint().toInt(); p += n }; 5 -> p += 4; else -> p = end } }
    }

    private fun vehicles(feed: ByteArray): List<Vehicle> {
        val out = ArrayList<Vehicle>()
        val m = Pb(feed)
        while (m.more()) {
            val (f, w) = m.tag()
            if (f != 2 || w != 2) { m.skip(w); continue }
            val e = m.bytes()
            while (e.more()) {
                val (ef, ew) = e.tag()
                if (ef != 4 || ew != 2) { e.skip(ew); continue }
                val v = e.bytes()
                var trip = ""; var start = ""; var lat = 0.0; var lon = 0.0; var seq = -1; var stop = ""; var ts = 0L
                while (v.more()) {
                    val (vf, vw) = v.tag()
                    when {
                        vf == 1 && vw == 2 -> { val t = v.bytes(); while (t.more()) { val (tf, tw) = t.tag(); when { tf == 1 && tw == 2 -> trip = t.str(); tf == 3 && tw == 2 -> start = t.str(); else -> t.skip(tw) } } }
                        vf == 2 && vw == 2 -> { val q = v.bytes(); while (q.more()) { val (qf, qw) = q.tag(); when { qf == 1 && qw == 5 -> lat = q.f32().toDouble(); qf == 2 && qw == 5 -> lon = q.f32().toDouble(); else -> q.skip(qw) } } }
                        vf == 3 && vw == 0 -> seq = v.varint().toInt()
                        vf == 5 && vw == 0 -> ts = v.varint()
                        vf == 7 && vw == 2 -> stop = v.str()
                        else -> v.skip(vw)
                    }
                }
                if (trip.isNotEmpty() && lat != 0.0) out.add(Vehicle(trip, lat, lon, seq, stop, ts, start))
            }
        }
        return out
    }

    /** True while the weekend service runs: Friday 15:30 to 03:00, Saturday 08:00 to 19:00 (Israel time). */
    fun inService(nowMs: Long = System.currentTimeMillis()): Boolean {
        val c = Calendar.getInstance(ISRAEL).apply { timeInMillis = nowMs }
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.FRIDAY -> m >= 15 * 60 + 30
            Calendar.SATURDAY -> m < 3 * 60 || m in 8 * 60..19 * 60
            else -> false
        }
    }

    // One live arrival: the line, its last stop's code, and the expected time at a stop (Unix seconds).
    class Arrival(val line: String, val destCode: Int, val stopCode: Int, val etaUtc: Long, val schedUtc: Long,
                  val tripKey: Long, val lat: Double, val lon: Double)

    @Volatile private var cache: Pair<Long, List<Vehicle>>? = null
    private val tripOfKey = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** The stops of a live Na'im bus's trip (code, position, name), for drawing its route on the Live map. */
    fun tripStops(ctx: Context, tripKey: Long): List<Moovit.Stop> {
        val id = tripOfKey[tripKey] ?: return emptyList()
        val trip = loadStatic(ctx)?.trips?.get(id) ?: return emptyList()
        return trip.times.map { Moovit.Stop(it.code, it.lat, it.lon, it.name) }
    }

    private fun live(): List<Vehicle> {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first < 15_000 }?.let { return it.second }
        val v = runCatching { vehicles(get(RT)) }.getOrDefault(emptyList())
        cache = now to v
        return v
    }

    /** Live arrivals at the given MOT stop codes; empty outside service hours or without data. */
    fun arrivals(ctx: Context, codes: Collection<Int>): List<Arrival> {
        if (codes.isEmpty() || !inService()) return emptyList()
        val st = loadStatic(ctx) ?: return emptyList()
        return arrivalsFor(st, live(), codes)
    }

    // The same from given feeds, for tests: the static GTFS zip and a GTFS-RT vehicle-positions message.
    internal fun arrivalsFrom(gtfsZip: ByteArray, feed: ByteArray, codes: Collection<Int>): List<Arrival> =
        arrivalsFor(parse(gtfsZip), vehicles(feed), codes)

    private fun arrivalsFor(st: Static, vs: List<Vehicle>, codes: Collection<Int>): List<Arrival> {
        val want = codes.toHashSet()
        val out = ArrayList<Arrival>()
        for (v in vs) {
            val trip = st.trips[v.tripId] ?: continue
            if (trip.times.size < 2) continue
            val midnight = serviceMidnight(v.startDate, v.ts)
            val at = position(trip, v)
            val delay = ((v.ts - (midnight + trip.times[at].sec))).coerceIn(-600L, 2700L)
            val dest = trip.times.last().code
            for (k in at until trip.times.size) {
                val s = trip.times[k]
                if (s.code !in want) continue
                val sched = midnight + s.sec
                val key = v.tripId.hashCode().toLong() and 0xffffffffL
                tripOfKey[key] = v.tripId
                out.add(Arrival(trip.short, dest, s.code, sched + delay, sched, key, v.lat, v.lon))
            }
        }
        return out
    }

    // Where on its trip a vehicle is: the reported stop sequence or stop when there is one, else its nearest stop.
    private fun position(trip: Trip, v: Vehicle): Int {
        if (v.stopSeq in 1..trip.times.size) return (v.stopSeq - 1).coerceIn(0, trip.times.lastIndex)
        var best = 0; var bestD = Double.MAX_VALUE
        for ((i, s) in trip.times.withIndex()) {
            val dy = s.lat - v.lat; val dx = (s.lon - v.lon) * 0.85
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // Midnight (Unix s) of the trip's service day: its start date when the feed gives one, else of the report.
    private fun serviceMidnight(startDate: String, ts: Long): Long {
        val c = Calendar.getInstance(ISRAEL)
        if (startDate.length == 8) c.set(startDate.substring(0, 4).toInt(), startDate.substring(4, 6).toInt() - 1, startDate.substring(6, 8).toInt())
        else c.timeInMillis = (if (ts > 0) ts else System.currentTimeMillis() / 1000) * 1000
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val m = c.timeInMillis / 1000
        // A Friday-night trip reported after midnight without a start date belongs to the evening before.
        return if (startDate.length != 8 && ts - m < 4 * 3600) m - 86_400 else m
    }

    private fun get(url: String): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "AltKavPlus (github.com/vascc124/altkav)")
        }
        if (c.responseCode != 200) throw java.io.IOException("Na'im HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    }
}
