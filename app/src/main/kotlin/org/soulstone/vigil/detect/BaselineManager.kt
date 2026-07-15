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
    const val DWELL_BUCKET_MS = 10 * 60_000L   // count at most one "visit" per 10-min dwell window
    const val ANCHOR_MIN_VISITS = 18           // ~3h cumulative dwell before a cell counts as home/work
    const val BASELINE_MIN_DAYS = 3

    /**
     * Note presence in a cell; returns whether it's an anchor. Crucially a cell
     * accrues at most ONE visit per [DWELL_BUCKET_MS], so the anchor signal tracks
     * time actually spent there — not how many trackers or adverts were seen.
     *
     * The previous per-sighting count was a real safety flaw: a busy street or a
     * single chatty tracker could mint a fake "home" in seconds, which would then
     * baseline-trust (silence alerts for) any tracker seen there — including a real
     * stalking device.
     */
    suspend fun noteLocation(placeDao: PlaceDao, geohash6: String, now: Long): Boolean {
        val existing = placeDao.get(geohash6) ?: run {
            placeDao.upsert(PlaceEntity(geohash6 = geohash6, visitCount = 1, lastSeen = now, anchor = false))
            return false
        }
        if (now - existing.lastSeen < DWELL_BUCKET_MS) return existing.anchor
        val visits = existing.visitCount + 1
        val anchor = visits >= ANCHOR_MIN_VISITS
        placeDao.upsert(existing.copy(visitCount = visits, lastSeen = now, anchor = anchor))
        return anchor
    }
}
