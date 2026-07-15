package org.soulstone.vigil.detect

import org.soulstone.vigil.data.db.PlaceDao
import org.soulstone.vigil.data.db.PlaceEntity

/**
 * Learns the user's "safe" RF world so it doesn't cry wolf about the trackers that
 * live where they live — their own AirTag, a partner's Tile, a housemate's tag.
 *
 * v1 heuristic (offline, on-device): count location-tagged sightings per geohash-6
 * cell; once a cell crosses [ANCHOR_MIN_VISITS] it's an "anchor" (home/work). A
 * tracker seen at an anchor on ≥ [BASELINE_MIN_DAYS] distinct days is marked
 * baseline-safe by the repository and excluded from alerting.
 *
 * This visit-count proxy is intentionally simple; the roadmap replaces it with
 * proper stay-point / dwell detection (200 m / 20 min clusters).
 */
object BaselineManager {

    const val DAY_MS = 86_400_000L
    const val ANCHOR_MIN_VISITS = 60
    const val BASELINE_MIN_DAYS = 3

    /** Record a location fix in its cell; returns whether that cell is now an anchor. */
    suspend fun noteLocation(placeDao: PlaceDao, geohash6: String, now: Long): Boolean {
        val existing = placeDao.get(geohash6)
        val visits = (existing?.visitCount ?: 0) + 1
        val anchor = visits >= ANCHOR_MIN_VISITS
        placeDao.upsert(
            PlaceEntity(
                geohash6 = geohash6,
                label = existing?.label ?: "",
                visitCount = visits,
                lastSeen = now,
                anchor = anchor
            )
        )
        return anchor
    }
}
