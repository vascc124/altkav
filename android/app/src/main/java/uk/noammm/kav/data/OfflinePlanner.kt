package uk.noammm.kav.data

import uk.noammm.kav.ui.ISRAEL
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sqrt

// Kav+: trip planning on the phone, from the timetable in the APK, for when Moovit won't plan.
// A connection scan (earliest arrival) over the day's trips, after Kav 1.2's offline planner, dressed up
// as the same Moovit.Itinerary the online path produces so the results, the trip detail and navigation
// keep working. What it can't know it leaves out: fares, CO2, alerts, live times.
//
// Ids handed to the UI are negative so they can never be taken for Moovit's: stop s is -(s + 1),
// route r is -(r + 1), and trip t on day offset o is -(t * 4 + o + 1).
object OfflinePlanner {
    private const val WALK_MPS = 1.2          // walking speed
    private const val DETOUR = 1.3            // streets aren't straight lines
    private const val TRANSFER_M = 350.0      // walk between stops up to this far to change
    private const val ACCESS_M = 900.0        // walk to or from the first and last stop up to this far
    private const val MIN_CHANGE = 120        // seconds to change vehicles at the same stop
    private const val INF = Int.MAX_VALUE / 2
    private const val CHANGE_PENALTY = 5 * 60 // what one more vehicle is worth, in arrival time
    // Walking to and from the stops counts double when choosing (OpenTripPlanner's "walk reluctance"):
    // a bus to the door beats a long walk that arrives a little sooner. Times shown stay real.
    private const val WALK_RELUCTANCE = 2

    fun stopId(s: Int) = -(s + 1)
    fun lineId(r: Int) = -(r + 1)
    fun netStop(id: Int) = -id - 1

    private fun walkSecs(m: Double) = (m * DETOUR / WALK_MPS).toInt()

    // ---- walking links between nearby stops, built once per timetable ----

    private class Links(val start: IntArray, val to: IntArray, val secs: IntArray)

    @Volatile private var links: Links? = null
    @Volatile private var linksFor: Net? = null

    private fun links(net: Net): Links = links?.takeIf { linksFor === net } ?: synchronized(this) {
        links?.takeIf { linksFor === net } ?: buildLinks(net).also { links = it; linksFor = net }
    }

    private fun metres(net: Net, a: Int, lat: Double, lon: Double): Double {
        val dy = (net.lat[a] - lat) * 111_195.0
        val dx = (net.lon[a] - lon) * 111_195.0 * cos(Math.toRadians(lat))
        return sqrt(dx * dx + dy * dy)
    }

    // Stops bucketed on a ~350 m grid, so neighbours are found in the 3×3 cells around a point.
    private class Grid(net: Net, val cell: Double) {
        val cells = HashMap<Long, IntArray>()
        init {
            val tmp = HashMap<Long, MutableList<Int>>()
            for (s in 0 until net.nStops) tmp.getOrPut(key(net.lat[s], net.lon[s])) { ArrayList() }.add(s)
            for ((k, v) in tmp) cells[k] = v.toIntArray()
        }
        fun key(lat: Double, lon: Double): Long {
            val y = Math.floor(lat / cell).toLong(); val x = Math.floor(lon / cell).toLong()
            return (y shl 32) xor (x and 0xffffffffL)
        }
        inline fun near(lat: Double, lon: Double, rings: Int, f: (Int) -> Unit) {
            val y0 = Math.floor(lat / cell).toLong(); val x0 = Math.floor(lon / cell).toLong()
            for (dy in -rings..rings) for (dx in -rings..rings) {
                val k = ((y0 + dy) shl 32) xor ((x0 + dx) and 0xffffffffL)
                cells[k]?.forEach(f)
            }
        }
    }

    @Volatile private var grid: Grid? = null
    @Volatile private var gridFor: Net? = null
    private fun grid(net: Net): Grid = grid?.takeIf { gridFor === net } ?: synchronized(this) {
        grid?.takeIf { gridFor === net } ?: Grid(net, 0.0035).also { grid = it; gridFor = net }
    }

    private fun buildLinks(net: Net): Links {
        val g = grid(net)
        val n = net.nStops
        val start = IntArray(n + 1)
        val to = ArrayList<Int>(n * 8); val secs = ArrayList<Int>(n * 8)
        for (s in 0 until n) {
            start[s] = to.size
            g.near(net.lat[s], net.lon[s], 1) { o ->
                if (o != s) {
                    val m = metres(net, o, net.lat[s], net.lon[s])
                    if (m <= TRANSFER_M) { to.add(o); secs.add(walkSecs(m)) }
                }
            }
        }
        start[n] = to.size
        return Links(start, to.toIntArray(), secs.toIntArray())
    }

    private fun around(net: Net, lat: Double, lon: Double, maxM: Double): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        grid(net).near(lat, lon, 3) { s ->
            val m = metres(net, s, lat, lon)
            if (m <= maxM) out.add(s to walkSecs(m))
        }
        return out
    }

    // ---- the scan ----

    private class Ride(val trip: Int, val day: Int, val boardK: Int, val alightK: Int, val dep: Int, val arr: Int)
    private class Step(val ride: Ride?, val walkFrom: Int, val walkTo: Int, val dep: Int, val arr: Int)
    private class Found(val dep: Int, val arr: Int, val steps: List<Step>, val walk: Int)

    // Day offsets: a trip running yesterday shows past midnight at its time minus a day, tomorrow's at plus.
    private val OFFSETS = intArrayOf(0, -86_400, 86_400)

    // Earliest arrival from [origin] after [t0] (seconds since today's Israel midnight).
    private fun scan(
        net: Net, links: Links, origin: List<Pair<Int, Int>>, egressReal: Map<Int, Int>,
        t0: Int, today: Int, allowed: (Int) -> Boolean, maxRides: Int = 8, walkOnly: Boolean = false,
    ): Found? {
        val nS = net.nStops; val nT = net.tripRoute.size
        val egress = egressReal.mapValues { it.value * WALK_RELUCTANCE }
        val arrT = IntArray(nS) { INF }          // earliest arrival at a stop
        val boardT = IntArray(nS) { INF }        // earliest a vehicle can be boarded there
        val viaRide = arrayOfNulls<Ride>(nS)
        val viaWalk = IntArray(nS) { -1 }        // stop walked from; -2 = from the origin
        val seenK = Array(3) { IntArray(nT) { -1 } } // per day offset: the stop_time a trip was boarded at
        val seenT = Array(3) { IntArray(nT) }
        // Vehicles taken to reach a stop, and to be on a trip. With a cap of one this is exact: only stops
        // reached on foot from the origin board. With more it is a good approximation, not exact.
        val rides = IntArray(nS)
        val tripRides = Array(3) { IntArray(nT) }
        for ((s, w) in origin) if (t0 + w < arrT[s]) { arrT[s] = t0 + w; boardT[s] = t0 + w; viaWalk[s] = -2 }

        var best = INF; var bestStop = -1
        for ((s, w) in egress) if (arrT[s] < INF && arrT[s] + w < best) { best = arrT[s] + w; bestStop = s }

        val days = intArrayOf(today, (today + 6) % 7, (today + 1) % 7)
        val cST = net.cST; val stDep = net.stDep; val stStop = net.stStop
        // One cursor per day offset over the connections, merged by time.
        val cur = IntArray(3) { o -> lowerBound(net, t0 - OFFSETS[o]) }
        while (true) {
            var o = -1; var tBest = INF
            for (k in 0..2) {
                val i = cur[k]
                if (i < cST.size) {
                    val t = stDep[cST[i]] + OFFSETS[k]
                    if (t < tBest) { tBest = t; o = k }
                }
            }
            if (o < 0 || tBest >= best) break
            val i = cur[o]++
            val j = cST[i]
            val trip = net.tripOf(j)
            if (!net.runsOn(trip, days[o]) || !allowed(net.rType[net.tripRoute[trip]])) continue
            val from = stStop[j]
            if (seenK[o][trip] < 0) {
                if (boardT[from] > tBest || rides[from] >= maxRides) continue
                seenK[o][trip] = j; seenT[o][trip] = tBest; tripRides[o][trip] = rides[from] + 1
            }
            if (seenK[o][trip] > j) continue // a stop time out of order in the data; don't ride backwards
            val to = stStop[j + 1]
            val a = stDep[j + 1] + OFFSETS[o]
            if (a < arrT[to]) {
                arrT[to] = a; boardT[to] = a + MIN_CHANGE; viaWalk[to] = -1; rides[to] = tripRides[o][trip]
                viaRide[to] = Ride(trip, o, seenK[o][trip], j + 1, seenT[o][trip], a)
                egress[to]?.let { w -> if (a + w < best) { best = a + w; bestStop = to } }
                for (x in links.start[to] until links.start[to + 1]) {
                    val nb = links.to[x]; val aw = a + links.secs[x]
                    if (aw < arrT[nb]) {
                        arrT[nb] = aw; boardT[nb] = aw; viaWalk[nb] = to; viaRide[nb] = null; rides[nb] = rides[to]
                        egress[nb]?.let { w -> if (aw + w < best) { best = aw + w; bestStop = nb } }
                    }
                }
            }
        }
        if (bestStop < 0 || best >= INF) return null

        val steps = ArrayList<Step>()
        var s = bestStop; var guard = 0
        while (guard++ < 64) {
            val w = viaWalk[s]
            val r = viaRide[s]
            when {
                w == -2 -> { steps.add(Step(null, -1, s, t0, arrT[s])); break }
                w >= 0 -> { steps.add(Step(null, w, s, arrT[w], arrT[s])); s = w }
                r != null -> { steps.add(Step(r, -1, -1, r.dep, r.arr)); s = stStop[r.boardK] }
                else -> return null
            }
        }
        steps.reverse()
        // The walk from the origin leaves just in time for the first vehicle, not at t0.
        val firstRide = steps.firstOrNull { it.ride != null }?.ride
        if (firstRide == null) {
            if (!walkOnly) return null
            val endWalk = egressReal[bestStop] ?: 0
            return Found(steps[0].dep, arrT[bestStop] + endWalk, steps, steps.filter { it.ride == null }.sumOf { it.arr - it.dep } + endWalk)
        }
        if (steps[0].ride == null && steps[0].walkFrom == -1) {
            val w = steps[0].arr - steps[0].dep
            steps[0] = Step(null, -1, steps[0].walkTo, firstRide.dep - w - 60, firstRide.dep - 60)
        }
        val endWalk = egressReal[bestStop] ?: 0
        val walked = steps.filter { it.ride == null }.sumOf { it.arr - it.dep } + endWalk
        return Found(steps[0].dep, arrT[bestStop] + endWalk, steps, walked)
    }

    private fun lowerBound(net: Net, t: Int): Int {
        var lo = 0; var hi = net.cST.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (net.stDep[net.cST[mid]] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // ---- what the UI gets ----

    private fun israelMidnightUtc(atMs: Long): Long {
        val c = Calendar.getInstance(ISRAEL).apply {
            timeInMillis = atMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis / 1000
    }

    /**
     * Up to [count] trips from [from] to [to]. [whenMs] 0 means now; with [arriveBy] the trips are the
     * latest that still arrive by then. [routeTypes] are GTFS route types; empty allows all.
     */
    fun plan(
        net: Net, from: Pair<Double, Double>, to: Pair<Double, Double>,
        whenMs: Long = 0L, arriveBy: Boolean = false, routeTypes: Collection<Int> = emptyList(), count: Int = 5,
    ): Pair<List<Moovit.Itinerary>, Moovit.Resolved> {
        val atMs = if (whenMs > 0) whenMs else System.currentTimeMillis()
        val midnight = israelMidnightUtc(atMs)
        val today = Calendar.getInstance(ISRAEL).apply { timeInMillis = atMs }.get(Calendar.DAY_OF_WEEK) - 1
        val target = (atMs / 1000 - midnight).toInt()
        val origin = around(net, from.first, from.second, ACCESS_M)
        val egress = around(net, to.first, to.second, ACCESS_M).toMap()
        if (origin.isEmpty() || egress.isEmpty()) return emptyList<Moovit.Itinerary>() to Moovit.Resolved()
        val links = links(net)
        // Route types past 7 (Israel's feed has a few, such as on-demand lines) count as buses.
        val allowed: (Int) -> Boolean = if (routeTypes.isEmpty()) { _ -> true } else { t -> t in routeTypes || (t > 7 && 3 in routeTypes) }

        val found = ArrayList<Found>()
        var t = if (arriveBy) target - 3 * 3600 else target
        var tries = 0
        // Until there are enough different routes: the same line again only adds a departure to its card.
        val seen = HashSet<List<Long>>()
        while ((if (arriveBy) found.size < 12 else seen.size < count + 1) && tries++ < 30) {
            val f = scan(net, links, origin, egress, t, today, allowed)?.let { trimShortLast(net, it, to) } ?: break
            if (arriveBy && f.arr > target) break
            if (found.none { same(it, f) }) { found.add(f); seen.add(signature(net, f)) }
            // Next search sets off a second too late for this trip's first vehicle (f.dep is its walk
            // start, a minute's margin before boarding), so it finds the one after.
            t = f.dep + 61
        }
        // A last ride of a few minutes that walking from its stop would nearly match is walked instead:
        // walk reluctance alone would hop a bus for two stops rather than walk 400 m.
        for (i in found.indices) found[i] = trimShortLast(net, found[i], to)

        // Trips with fewer vehicles: the fastest trip may change twice where one bus is barely slower.
        // Direct and one-change searches over the same window join in, then anything another trip
        // beats on every count (leaves no earlier, arrives no later, no more vehicles) is dropped.
        if (found.isNotEmpty()) {
            val horizon = found.maxOf { it.arr }
            for (cap in 1..2) {
                var tc = if (arriveBy) target - 3 * 3600 else target
                var n = 0
                while (n++ < 4) {
                    val f = scan(net, links, origin, egress, tc, today, allowed, maxRides = cap)?.let { trimShortLast(net, it, to) } ?: break
                    if (f.arr > horizon + 20 * 60 || (arriveBy && f.arr > target)) break
                    if (found.none { same(it, f) }) found.add(f)
                    tc = f.dep + 61
                }
            }
        }
        // Each change is worth 5 min of arrival, and against a trip with fewer changes leaving up to 10 min
        // earlier still counts as "no earlier": nobody wants an extra change to arrive a minute sooner, or
        // two extra changes just to set off a minute after the train. The next bus of the same kind stays.
        fun cost(f: Found) = f.arr + CHANGE_PENALTY * rideCount(f) + f.walk * (WALK_RELUCTANCE - 1)
        // A change for a ride of 3 min or less isn't worth offering while anything else is on the list.
        val hops = found.filter { f -> f.steps.count { it.ride != null } >= 2 && f.steps.any { st -> st.ride?.let { it.arr - it.dep <= 180 } == true } }
        if (hops.size < found.size) found.removeAll(hops.toSet())
        val kept = found.filter { a ->
            found.none { b ->
                b !== a && (b.dep >= a.dep || (rideCount(b) < rideCount(a) && b.dep >= a.dep - 10 * 60)) &&
                    cost(b) <= cost(a) && rideCount(b) <= rideCount(a) &&
                    (b.dep > a.dep || cost(b) < cost(a) || rideCount(b) < rideCount(a))
            }
        }
        // The same route again later (same lines, same stops) is one card with its next departures, as
        // Moovit shows "in 4, 33 min". The feed also repeats some trips under several service ids.
        val groups = LinkedHashMap<List<Long>, MutableList<Found>>()
        for (f in kept.sortedBy { it.dep }) groups.getOrPut(signature(net, f)) { ArrayList() }.add(f)
        val later = HashMap<Found, List<Found>>()
        val heads = groups.values.map { g ->
            val uniq = g.distinctBy { it.dep }
            later[uniq[0]] = uniq.drop(1).take(3)
            uniq[0]
        }
        // The same lines from another stop (a longer walk to the same bus) is the same choice: keep the best.
        val best = heads.groupBy { f -> f.steps.mapNotNull { it.ride?.let { r -> net.tripRoute[r.trip] } } }
            .values.map { g -> if (arriveBy) g.maxBy { it.dep } else g.minBy { cost(it) } }
        // Late at night the scan happily waits for the morning: a change with an hour's wait, a trip three
        // times the fastest, or a 04:44 option on a search at 00:33. Each filter applies only if it leaves
        // something.
        fun keepIf(list: List<Found>, ok: (Found) -> Boolean) = list.filter(ok).ifEmpty { list }
        var sane = keepIf(best) { f -> maxWait(f) <= 45 * 60 }
        val fastest = sane.minOf { it.arr - it.dep }
        sane = keepIf(sane) { f -> f.arr - f.dep <= maxOf(fastest * 17 / 10, fastest + 45 * 60) }
        if (!arriveBy) { val first = sane.minOf { it.dep }; sane = keepIf(sane) { f -> f.dep <= first + 2 * 3600 } }
        val chosen = if (arriveBy) sane.sortedByDescending { it.dep }.take(count)
        else sane.sortedWith(compareBy({ cost(it) }, { it.arr })).take(count)

        return build(net, chosen, later, from, to, midnight, today)

    }

    // Planner results as the itineraries and names the UI takes.
    private fun build(
        net: Net, chosen: List<Found>, later: Map<Found, List<Found>>,
        from: Pair<Double, Double>, to: Pair<Double, Double>, midnight: Long, today: Int,
    ): Pair<List<Moovit.Itinerary>, Moovit.Resolved> {
        val lines = LinkedHashMap<Int, Moovit.LineInfo>()
        val stops = LinkedHashMap<Int, Moovit.StopInfo>()
        val types = LinkedHashMap<Int, Int>()
        fun note(s: Int) {
            val id = stopId(s)
            if (id !in stops) stops[id] = Moovit.StopInfo(id, net.name[s], net.code[s].takeIf { it > 0 }?.toString().orEmpty(), net.lat[s], net.lon[s])
        }
        fun pt(s: Int) = net.lat[s] to net.lon[s]

        val out = chosen.mapIndexed { n, f ->
            val legs = ArrayList<Moovit.Leg>()
            for ((k, st) in f.steps.withIndex()) {
                val r = st.ride
                if (r == null) {
                    val a = if (st.walkFrom >= 0) pt(st.walkFrom) else from
                    val b = pt(st.walkTo)
                    if (st.walkFrom >= 0) note(st.walkFrom)
                    note(st.walkTo)
                    legs.add(Moovit.Leg(
                        Moovit.LegKind.WALK, dep = midnight + st.dep, arr = midnight + st.arr,
                        fromStop = if (st.walkFrom >= 0) stopId(st.walkFrom) else -1, toStop = stopId(st.walkTo),
                        meters = walkMetres(a, b), shape = listOf(a, b),
                    ))
                    continue
                }
                val route = net.tripRoute[r.trip]
                val lid = lineId(route)
                if (lid !in lines) {
                    lines[lid] = Moovit.LineInfo(
                        groupId = lid, number = net.rShort[route], agencyId = lid,
                        origin = net.name[net.stStop[net.tripStart[r.trip]]], destination = net.name[net.tripLast(r.trip)],
                        caption = net.rLong[route],
                    )
                    types[lid] = net.rType[route]
                }
                val path = (r.boardK..r.alightK).map { net.stStop[it] }
                path.forEach(::note)
                val tripId = -(r.trip.toLong() * 4 + r.day + 1)
                val dep = midnight + r.dep; val arr = midnight + r.arr
                legs.add(Moovit.Leg(
                    Moovit.LegKind.WAIT, lineId = lid, dep = dep, arr = dep,
                    fromStop = stopId(path.first()), toStop = stopId(path.last()),
                    nextDeps = listOf(Moovit.Departure(tripId = tripId, staticUtc = dep)) +
                        if (k == f.steps.indexOfFirst { it.ride != null }) later[f].orEmpty().mapNotNull { o ->
                            o.steps.firstOrNull { it.ride != null }?.ride?.let { lr ->
                                Moovit.Departure(tripId = -(lr.trip.toLong() * 4 + lr.day + 1), staticUtc = midnight + lr.dep)
                            }
                        } else emptyList(),
                ))
                legs.add(Moovit.Leg(
                    Moovit.LegKind.RIDE, lineId = lid, tripId = tripId, dep = dep, arr = arr,
                    stops = path.map(::stopId), fromStop = stopId(path.first()), toStop = stopId(path.last()),
                    shortName = net.rShort[route], shape = path.map(::pt),
                ))
                // The walk from the last stop to the destination.
                if (k == f.steps.lastIndex) {
                    val last = path.last()
                    val w = egressSecs(net, last, to)
                    legs.add(Moovit.Leg(
                        Moovit.LegKind.WALK, dep = arr, arr = arr + w,
                        fromStop = stopId(last), toStop = -1, meters = walkMetres(pt(last), to), shape = listOf(pt(last), to),
                    ))
                }
            }
            if (f.steps.last().ride == null) {
                val last = f.steps.last().walkTo
                val w = egressSecs(net, last, to)
                legs.add(Moovit.Leg(
                    Moovit.LegKind.WALK, dep = midnight + f.steps.last().arr, arr = midnight + f.steps.last().arr + w,
                    fromStop = stopId(last), toStop = -1, meters = walkMetres(pt(last), to), shape = listOf(pt(last), to),
                ))
            }
            // A walk to a stop and on from it, with no ride between, is one walk.
            val merged = ArrayList<Moovit.Leg>(legs.size)
            for (l in legs) {
                val p = merged.lastOrNull()
                if (p != null && p.kind == Moovit.LegKind.WALK && l.kind == Moovit.LegKind.WALK) {
                    merged[merged.lastIndex] = p.copy(arr = l.arr, toStop = l.toStop, meters = p.meters + l.meters, shape = p.shape + l.shape.drop(1))
                } else merged.add(l)
            }
            legs.clear(); legs.addAll(merged.filter { it.kind != Moovit.LegKind.WALK || it.meters > 0 || it == merged.first() })
            // No later bus of a ride's line from its stop before 04:00: say so, there's no next one to catch.
            val last = f.steps.any { st -> st.ride?.let { lastOfDay(net, it, today) } == true }
            Moovit.Itinerary(
                guid = "kavplus-offline-$n-${f.dep}", group = 2, legs = legs,
                tags = if (last) listOf(LAST_TAG()) else emptyList(),
                dep = legs.first().dep, arr = legs.last().arr,
                section = SECTION,
            )
        }
        return out to Moovit.Resolved(lines, stops, types)
    }

    // ---- missed your stop: stay on, and where to get off now ----

    /**
     * For a rider still aboard [ride] past its stop, at [here] at [nowMs]: the best way on to [to] from the
     * stops this vehicle has still to reach - get off at one and walk, or change there. The vehicle is the
     * timetable's trip: known for rides planned here, matched by line, boarding stop code and time for
     * Moovit's. The itinerary starts with the rest of this ride. Null when the trip can't be found.
     */
    fun rerouteAboard(
        net: Net, ride: Moovit.Leg, r: Moovit.Resolved, here: Pair<Double, Double>, to: Pair<Double, Double>, nowMs: Long,
    ): Pair<Moovit.Itinerary, Moovit.Resolved>? {
        val midnight = israelMidnightUtc(nowMs)
        val today = Calendar.getInstance(ISRAEL).apply { timeInMillis = nowMs }.get(Calendar.DAY_OF_WEEK) - 1
        val now = (nowMs / 1000 - midnight).toInt()
        val (trip, o) = tripOf(net, ride, r, midnight, today) ?: return null
        val off = OFFSETS[o]
        val a = net.tripStart[trip]; val z = net.tripStart[trip + 1]
        // Where on the trip the rider is: the stop nearest to them.
        var kNow = a; var bestM = Double.MAX_VALUE
        for (k in a until z) { val m = metres(net, net.stStop[k], here.first, here.second); if (m < bestM) { bestM = m; kNow = k } }
        if (kNow >= z - 1) return null
        val ahead = (kNow + 1 until z).map { k -> net.stStop[k] to (net.stDep[k] + off - now).coerceAtLeast(0) }
        // A bus carrying the rider away may leave them a longer walk back than a planned trip would.
        val egress = around(net, to.first, to.second, 1500.0).toMap()
        if (egress.isEmpty()) return null
        // Simple answers only, from a moving bus: get off and walk, or get off and take one more vehicle.
        // The scan's ride count starts at zero at these stops, so a cap of 0 is "walk" and 1 is "one change".
        fun cost(x: Found) = x.arr + CHANGE_PENALTY * x.steps.count { it.ride != null } + x.walk * (WALK_RELUCTANCE - 1)
        val f = listOfNotNull(
            scan(net, links(net), ahead, egress, now, today, { true }, maxRides = 0, walkOnly = true),
            scan(net, links(net), ahead, egress, now, today, { true }, maxRides = 1, walkOnly = true),
        ).minByOrNull(::cost) ?: return null
        // The scan starts at the stop it got off at, as if walked to; that stop is a stop of this trip.
        val off0 = f.steps.firstOrNull() ?: return null
        val getOff = if (off0.ride == null && off0.walkFrom == -1) off0.walkTo else return null
        val kOff = (kNow + 1 until z).firstOrNull { net.stStop[it] == getOff } ?: return null
        val stay = Step(Ride(trip, o, kNow, kOff, net.stDep[kNow] + off, net.stDep[kOff] + off), -1, -1, net.stDep[kNow] + off, net.stDep[kOff] + off)
        val rest = f.steps.drop(1)
        val walked = rest.filter { it.ride == null }.sumOf { it.arr - it.dep } + (egress[getOff] ?: 0)
        val found = Found(now, f.arr, listOf(stay) + rest, walked)
        val (list, res) = build(net, listOf(found), emptyMap(), here, to, midnight, today)
        return list.firstOrNull()?.let { it to res }
    }

    // The timetable trip behind a ride: its own for rides planned here; for Moovit's, the run of the same
    // line number from the stop with the same code closest to the planned departure (within 15 min).
    private fun tripOf(net: Net, ride: Moovit.Leg, r: Moovit.Resolved, midnight: Long, today: Int): Pair<Int, Int>? {
        if (ride.tripId < 0 && ride.lineId < 0 && ride.tripId > -4L * net.tripRoute.size - 4) {
            val k = (-ride.tripId - 1)
            return (k / 4).toInt() to (k % 4).toInt().coerceIn(0, 2)
        }
        val code = r.stop(ride.fromStop)?.code?.toIntOrNull() ?: return null
        val number = r.line(ride.lineId)?.number ?: return null
        val stop = (0 until net.nStops).firstOrNull { net.code[it] == code } ?: return null
        val dep = (ride.dep - midnight).toInt()
        val days = intArrayOf(today, (today + 6) % 7, (today + 1) % 7)
        var best: Pair<Int, Int>? = null; var bestGap = 15 * 60 + 1
        for (i in net.dStart[stop] until net.dStart[stop + 1]) {
            val st = net.cST[net.dConn[i]]
            val t = net.tripOf(st)
            if (net.rShort[net.tripRoute[t]] != number) continue
            for (o in 0..2) {
                if (!net.runsOn(t, days[o])) continue
                val gap = kotlin.math.abs(net.stDep[st] + OFFSETS[o] - dep)
                if (gap < bestGap) { bestGap = gap; best = t to o }
            }
        }
        return best
    }

    const val SECTION = "Planned on your phone"

    fun LAST_TAG() = uk.noammm.kav.ui.T("Last one today", "האחרון להיום")

    private fun lastOfDay(net: Net, r: Ride, today: Int): Boolean {
        val stop = net.stStop[r.boardK]
        // The same line, not the same route id: the feed gives train runs a route each.
        fun line(route: Int) = "${net.rAgency.getOrElse(route) { -1 }}|${net.rShort[route]}|${net.rLong[route]}"
        val mine = line(net.tripRoute[r.trip])
        val lastStop = net.tripLast(r.trip)
        val days = intArrayOf(today, (today + 6) % 7, (today + 1) % 7)
        // The coming 04:00, in today's seconds: tonight's if the ride is after midnight already.
        val endOfNight = if (r.dep < 4 * 3600) 4 * 3600 else 28 * 3600
        for (i in net.dStart[stop] until net.dStart[stop + 1]) {
            val st = net.cST[net.dConn[i]]
            val t = net.tripOf(st)
            if (t == r.trip || line(net.tripRoute[t]) != mine && net.tripLast(t) != lastStop) continue
            for (o in 0..2) {
                val dep = net.stDep[st] + OFFSETS[o]
                if (dep > r.dep && dep < endOfNight && net.runsOn(t, days[o])) return false
            }
        }
        return true
    }

    fun isOffline(trip: Moovit.Itinerary) = trip.guid.startsWith("kavplus-offline")

    // ---- live times for trips planned here, from the Ministry's feed via curlbus ----

    /**
     * Live arrivals at each ride's boarding stop. A bus of the ride's line heading to the same last stop
     * takes the ride's trip when its ETA is from 3 min before to 40 min after the planned departure;
     * the others of that line still show, as the buses after it.
     */
    fun refreshLive(net: Net, trips: List<Moovit.Itinerary>, prev: Moovit.Resolved): Moovit.Resolved {
        val rides = trips.filter(::isOffline).flatMap { it.rides }.filter { it.lineId < 0 && it.fromStop < 0 && it.tripId < 0 }
        if (rides.isEmpty()) return prev
        val codeOf = { id: Int -> net.code.getOrElse(netStop(id)) { 0 } }
        val boards = Curlbus.boardArrivals(rides.map { codeOf(it.fromStop) }.distinct())
        val live = HashMap<Moovit.ArrivalKey, Moovit.Arrival>()
        val patterns = HashMap<Int, List<Int>>()
        for ((n, ride) in rides.withIndex()) {
            val list = boards[codeOf(ride.fromStop)] ?: continue
            val trip = ((-ride.tripId - 1) / 4).toInt()
            val number = prev.line(ride.lineId)?.number ?: continue
            val dest = net.code.getOrElse(net.tripLast(trip)) { 0 }
            val same = list.filter { it.line == number && it.destCode == dest }.sortedBy { it.etaUtc }
            if (same.isEmpty()) continue
            val pid = -(n + 1)
            patterns[pid] = ride.stops
            val mine = same.filter { it.etaUtc - ride.dep in -180L..2400L }.minByOrNull { kotlin.math.abs(it.etaUtc - ride.dep) }
            for ((k, a) in same.withIndex()) {
                val isMine = a === mine
                val arrival = Moovit.Arrival(
                    stopId = ride.fromStop, lineId = ride.lineId,
                    tripId = if (isMine) ride.tripId else -(1_000_000_000L + n * 100L + k),
                    staticUtc = if (isMine) ride.dep else a.etaUtc, rtUtc = a.etaUtc, statisticalUtc = 0L,
                    status = 0, certainty = 0, traffic = if (isMine && a.etaUtc - ride.dep >= 180) 2 else 0,
                    frequency = false, rtDropped = false, tracked = a.tracked, lat = a.lat, lon = a.lon,
                    vehicleId = a.vehicle, patternId = pid,
                )
                live[arrival.key] = arrival
            }
        }
        return Moovit.Resolved(prev.lines, prev.stops, prev.routeTypes, live, prev.shapes, Curlbus.POLL_SECS, prev.patterns + patterns)
    }

    private fun rideCount(f: Found) = f.steps.count { it.ride != null }

    // The longest wait between getting off one vehicle and onto the next.
    private fun maxWait(f: Found): Int {
        val rides = f.steps.mapNotNull { it.ride }
        return rides.zipWithNext { a, b -> b.dep - a.arr }.maxOrNull() ?: 0
    }

    // Drops a last ride of 4 min or less, and the walk to it, when walking on from where the trip stood
    // before it is within reach and costs at most 6 min more.
    private fun trimShortLast(net: Net, f: Found, to: Pair<Double, Double>): Found {
        val last = f.steps.last().ride ?: return f
        if (f.steps.count { it.ride != null } < 2 || last.arr - last.dep > 4 * 60) return f
        var steps = f.steps.dropLast(1)
        while (steps.isNotEmpty() && steps.last().ride == null) steps = steps.dropLast(1)
        val prev = steps.lastOrNull()?.ride ?: return f
        val stop = net.stStop[prev.alightK]
        val m = metres(net, stop, to.first, to.second)
        if (m > ACCESS_M) return f
        val arr = prev.arr + walkSecs(m)
        if (arr > f.arr + 6 * 60) return f
        val walked = steps.filter { it.ride == null }.sumOf { it.arr - it.dep } + walkSecs(m)
        return Found(f.dep, arr, steps, walked)
    }

    // Lines and the stops each is boarded and left at.
    private fun signature(net: Net, f: Found): List<Long> = f.steps.mapNotNull { st ->
        st.ride?.let { r -> (net.tripRoute[r.trip].toLong() shl 40) or (net.stStop[r.boardK].toLong() shl 20) or net.stStop[r.alightK].toLong() }
    }

    private fun same(a: Found, b: Found): Boolean {
        fun key(f: Found) = f.steps.mapNotNull { it.ride?.let { r -> r.trip } }
        return key(a) == key(b)
    }

    private fun egressSecs(net: Net, s: Int, to: Pair<Double, Double>) = walkSecs(metres(net, s, to.first, to.second))

    private fun walkMetres(a: Pair<Double, Double>, b: Pair<Double, Double>): Int {
        val dy = (a.first - b.first) * 111_195.0
        val dx = (a.second - b.second) * 111_195.0 * cos(Math.toRadians(a.first))
        return (sqrt(dx * dx + dy * dy) * DETOUR).toInt()
    }
}
