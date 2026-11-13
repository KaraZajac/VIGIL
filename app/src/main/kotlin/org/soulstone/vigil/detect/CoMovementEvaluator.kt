package org.soulstone.vigil.detect

import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem

/**
 * The temporal co-movement test — VIGIL's core question: has this tracker been at
 * enough of *my* distinct places, over enough time, and come at least moderately
 * near me at least once?
 *
 * Thresholds are adapted from AirGuard's field-tuned model (≥3 sightings, N
 * distinct locations, T minutes) with one deliberate addition AirGuard omits: an
 * **RSSI proximity gate**: the raw peak RSSI must reach the floor at least once.
 * It is coarse — −85 dBm at the default is reachable from tens of metres — so it
 * only rejects tags that never came even moderately near. See paper §4.2.
 *
 * Places are [Places]: positions over 300 m apart and further apart than their
 * fixes could be wrong by, with one-off bad fixes dropped. They used to be
 * geohash-7 cells, and a phone's position wobbling across a cell edge made a
 * second "place" out of a tag that never moved: a separated tag next door, heard
 * from the sofa all evening, alerted on High for most homes in replay.
 *
 * A sighting counts at most once per 15 minutes, or sooner when the user has moved
 * on to a different place since the last one that counted. By the clock alone, a
 * tag hidden in a car gave one sighting per drive on any drive under 15 minutes,
 * so a day of short commutes never reached three. "A Tile in the car beside you at
 * a few red lights" still does not alert: the time span thresholds below need it
 * to stay with you for half an hour or more. It can show as "Watching" while it
 * does stay with you.
 *
 * This is the identity path. Rotating-ID clones are the job of [PresenceEngine],
 * which runs alongside it as an additional signal, not a replacement. Neither path
 * looks back further than [WINDOW_MS] or correlates across days.
 */
object CoMovementEvaluator {

    const val WINDOW_MS = 24 * 3_600_000L          // co-movement is judged over the last 24h
    const val ALERT_COOLDOWN_MS = 4 * 3_600_000L   // don't re-alert the same device within 4h
    private const val DEDUP_MS = 15 * 60_000L      // by the clock, at most once per 15 min

    data class Thresholds(
        val minSightings: Int,
        val minPlaces: Int,
        val minSpanMin: Int,
        val rssiFloorDbm: Int
    )

    data class Assessment(
        val riskState: RiskState,
        val sightings: Int,
        val distinctPlaces: Int,
        val spanMin: Long,
        val closeEnough: Boolean,
        val separatedSeen: Boolean
    )

    fun thresholdsFor(s: Sensitivity): Thresholds = when (s) {
        // higher sensitivity => fewer places / shorter time / farther RSSI allowed => faster alerts, more FPs
        Sensitivity.HIGH -> Thresholds(minSightings = 3, minPlaces = 2, minSpanMin = 30, rssiFloorDbm = -90)
        Sensitivity.MEDIUM -> Thresholds(minSightings = 3, minPlaces = 3, minSpanMin = 45, rssiFloorDbm = -85)
        Sensitivity.LOW -> Thresholds(minSightings = 3, minPlaces = 4, minSpanMin = 90, rssiFloorDbm = -80)
    }

    fun evaluate(
        sightings: List<SightingEntity>,
        ecosystem: TrackerEcosystem,
        t: Thresholds
    ): Assessment {
        if (sightings.isEmpty()) {
            return Assessment(RiskState.OBSERVED, 0, 0, 0, closeEnough = false, separatedSeen = false)
        }
        val sorted = sightings.sortedBy { it.timestamp }

        // Debounce: one effective sighting per DEDUP_MS so a chatty tag at 2s
        // intervals doesn't trivially clear the count. lastCounted is NULLABLE and
        // always counts the first sighting — the old `Long.MIN_VALUE` seed overflowed
        // (timestamp - Long.MIN_VALUE wraps negative), which pinned `effective` at 0
        // and made the detector unable to EVER alert. (Caught by unit test.)
        var effective = 0
        var lastCounted: SightingEntity? = null
        for (s in sorted) {
            val prev = lastCounted
            if (prev == null || s.timestamp - prev.timestamp >= DEDUP_MS || Places.movedOn(prev, s)) {
                effective++
                lastCounted = s
            }
        }

        val distinctPlaces = Places.distinct(sorted.mapNotNull(Places::fixOf))
        val spanMin = (sorted.last().timestamp - sorted.first().timestamp) / 60_000
        val maxRssi = sorted.maxOf { it.rssi }
        val closeEnough = maxRssi >= t.rssiFloorDbm
        // Tile emits no separated-state flag, so any Tile is a live candidate.
        val separatedSeen = ecosystem == TrackerEcosystem.TILE || sorted.any { it.separated }

        val riskState = when {
            !separatedSeen -> RiskState.OBSERVED
            effective >= t.minSightings && distinctPlaces >= t.minPlaces &&
                spanMin >= t.minSpanMin && closeEnough -> RiskState.ALERTING
            effective >= t.minSightings && closeEnough && distinctPlaces >= 1 -> RiskState.SUSPICIOUS
            else -> RiskState.OBSERVED
        }

        return Assessment(riskState, effective, distinctPlaces, spanMin, closeEnough, separatedSeen)
    }
}
