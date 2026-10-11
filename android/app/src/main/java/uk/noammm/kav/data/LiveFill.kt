package uk.noammm.kav.data

// AltKav+: bus positions Moovit doesn't have. Moovit tracks most lines, but for some it sends times
// without a vehicle position, and the live-location screen then says the line has none - while the
// Ministry of Transport's SIRI feed (what the operators' own apps show) does follow those buses. For
// every ride of a Moovit itinerary whose arrival has no position, this asks curlbus about its boarding
// stop and takes the position of the same line's bus due nearest that arrival's time, within 20 min.
// Moovit's own times, status and route stay as they are; only the position is added.
object LiveFill {
    private const val WINDOW_SECS = 20 * 60L

    fun fill(trips: List<Moovit.Itinerary>, r: Moovit.Resolved): Moovit.Resolved {
        val rides = trips.flatMap { it.rides }.flatMap { it.options }
            .filter { it.lineId > 0 && it.fromStop > 0 && r.arrival(it)?.hasLocation != true }
            .distinctBy { it.fromStop to it.tripId }
        if (rides.isEmpty()) return r
        val codeOf = { ride: Moovit.Leg -> r.stop(ride.fromStop)?.code?.trim()?.toIntOrNull() ?: 0 }
        val codes = rides.map(codeOf).filter { it > 0 }.distinct().take(Curlbus.MAX_STOPS)
        if (codes.isEmpty()) return r
        val boards = runCatching { Curlbus.boardArrivals(codes) }.getOrNull() ?: return r
        val added = HashMap<Moovit.ArrivalKey, Moovit.Arrival>()
        val now = System.currentTimeMillis() / 1000
        for (ride in rides) {
            val number = r.line(ride.lineId)?.number?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val list = boards[codeOf(ride)] ?: continue
            val old = r.arrival(ride)
            val due = old?.rtUtc?.takeIf { it > 0 } ?: old?.staticUtc?.takeIf { it > 0 } ?: ride.dep
            val bus = list.filter { it.line == number && it.tracked && it.lat != 0.0 && it.lon != 0.0 }
                .filter { kotlin.math.abs(it.etaUtc - due) <= WINDOW_SECS }
                .minByOrNull { kotlin.math.abs(it.etaUtc - due) } ?: continue
            val a = Moovit.Arrival(
                stopId = ride.fromStop, lineId = ride.lineId, tripId = ride.tripId,
                staticUtc = old?.staticUtc ?: ride.dep, rtUtc = old?.rtUtc ?: bus.etaUtc,
                statisticalUtc = old?.statisticalUtc ?: 0L, status = old?.status ?: 0,
                certainty = old?.certainty ?: 0, traffic = old?.traffic ?: 0,
                frequency = old?.frequency ?: false, rtDropped = old?.rtDropped ?: false,
                tracked = true, lat = bus.lat, lon = bus.lon,
                vehicleId = bus.vehicle.ifBlank { old?.vehicleId.orEmpty() },
                sampleUtc = bus.sampleUtc.takeIf { it > 0 } ?: now,
                vehicleStatus = old?.vehicleStatus ?: 0,
                nextStopIndex = old?.nextStopIndex ?: -1, stopIndex = old?.stopIndex ?: -1,
                patternStops = old?.patternStops ?: -1, tripShapeId = old?.tripShapeId ?: -1,
                patternId = old?.patternId ?: -1, platform = old?.platform.orEmpty(),
            )
            added[a.key] = a
        }
        if (added.isEmpty()) return r
        return Moovit.Resolved(r.lines, r.stops, r.routeTypes, r.live + added, r.shapes, r.pollSecs, r.patterns)
    }
}
