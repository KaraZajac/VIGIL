package org.soulstone.vigil.detect

import org.soulstone.vigil.model.TrackerObservation

/**
 * Decides which of the devices one all-devices sweep heard are worth writing down.
 *
 * A sweep in a busy place hears hundreds of addresses, most of them the same ones
 * as two minutes ago. Recording all of them every time would bury the database and
 * add nothing: co-movement needs to know a device was still close after the user
 * MOVED, not that it was still there while they sat still. So a device is recorded
 * when it is new, when the user has moved more than [moveMetres] since it was last
 * recorded, or when [minIntervalMs] has passed (which keeps "last seen" honest
 * while stationary).
 *
 * Only the strongest [maxPerSweep] devices at or above [floorDbm] are considered.
 * Weaker ones cannot count towards an alert anyway (every evaluator floor is above
 * it), and taking the strongest first means a crowd of weaker devices cannot push
 * a device that is actually close out of the record. More than [maxPerSweep]
 * addresses that are all at least as strong as it still can. Something built to
 * do that could more simply change its own address, which nothing here follows.
 */
class NearbyThrottle(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val moveMetres: Double = MOVE_METRES,
    private val maxPerSweep: Int = MAX_PER_SWEEP,
    private val floorDbm: Int = FLOOR_DBM,
    private val maxEntries: Int = MAX_ENTRIES
) {
    private class Last(val at: Long, val lat: Double?, val lon: Double?)

    // Access-ordered, so trimming drops the devices not heard for longest.
    private val last = LinkedHashMap<String, Last>(256, 0.75f, true)

    fun select(
        heard: List<TrackerObservation>,
        lat: Double?,
        lon: Double?,
        now: Long
    ): List<TrackerObservation> {
        val out = ArrayList<TrackerObservation>()
        for (obs in heard.sortedByDescending { it.rssi }) {
            if (obs.rssi < floorDbm || out.size >= maxPerSweep) break
            val prev = last[obs.stableId]
            val due = prev == null ||
                now - prev.at >= minIntervalMs ||
                moved(prev, lat, lon)
            if (!due) continue
            last[obs.stableId] = Last(now, lat, lon)
            out.add(obs)
        }
        if (last.size > maxEntries) {
            val it = last.entries.iterator()
            while (last.size > maxEntries && it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        return out
    }

    /**
     * The devices [select] just returned were not written after all (the database
     * refused). Forget them, so the next sweep offers them again instead of
     * waiting out the interval with nothing on record.
     */
    fun forget(ids: Collection<String>) {
        for (id in ids) last.remove(id)
    }

    fun clear() = last.clear()

    private fun moved(prev: Last, lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        val pLat = prev.lat
        val pLon = prev.lon
        // First fix after recording without one: worth a geotagged sighting now.
        if (pLat == null || pLon == null) return true
        return Places.metres(pLat, pLon, lat, lon) > moveMetres
    }

    companion object {
        const val MIN_INTERVAL_MS = 5 * 60_000L
        const val MOVE_METRES = 100.0
        const val MAX_PER_SWEEP = 96
        const val FLOOR_DBM = -90
        const val MAX_ENTRIES = 4096
    }
}
