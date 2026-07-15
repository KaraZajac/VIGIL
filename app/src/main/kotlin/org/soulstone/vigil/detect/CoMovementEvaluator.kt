package org.soulstone.vigil.detect

import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem

/**
 * The temporal co-movement test — VIGIL's core question: has this tracker been at
 * enough of *my* distinct places, over enough time, while actually close to me?
 *
 * Thresholds are adapted from AirGuard's field-tuned model (≥3 sightings, N
 * distinct locations, T minutes) with one deliberate addition AirGuard omits: an
 * **RSSI proximity gate**. A tracker must have been genuinely close at least once
 * (max RSSI ≥ floor) before it can alert — this rejects "a Tile in a car beside
 * you at two red lights." See research brief §3.5 / §4.
 *
 * This is the honest, shippable v1. The rotation-robust "detect the attack, not
 * the device" layer (problem #1) is being designed separately and slots in as an
 * additional signal, not a replacement.
 */
object CoMovementEvaluator {

    const val WINDOW_MS = 24 * 3_600_000L          // co-movement is judged over the last 24h
    const val ALERT_COOLDOWN_MS = 4 * 3_600_000L   // don't re-alert the same device within 4h
    private const val DEDUP_MS = 15 * 60_000L      // count a device at most once per 15 min

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
        // intervals doesn't trivially clear the count.
        var effective = 0
        var lastCounted = Long.MIN_VALUE
        for (s in sorted) {
            if (s.timestamp - lastCounted >= DEDUP_MS) {
                effective++
                lastCounted = s.timestamp
            }
        }

        val distinctPlaces = sorted.mapNotNull { it.geohash7 }.toSet().size
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
