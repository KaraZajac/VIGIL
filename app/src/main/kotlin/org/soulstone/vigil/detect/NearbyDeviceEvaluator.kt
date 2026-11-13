package org.soulstone.vigil.detect

import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.detect.CoMovementEvaluator.Assessment
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.Sensitivity

/**
 * Co-movement test for [org.soulstone.vigil.model.TrackerEcosystem.OTHER_BLE]: any
 * Bluetooth device that is not a known tracker format.
 *
 * It is deliberately harder to satisfy than [CoMovementEvaluator]. A tracker advert
 * already says "I am a tracker, away from my owner"; an arbitrary device says
 * nothing, and almost every one heard is innocent: a neighbour's television, a
 * fellow passenger's earbuds, the user's own watch. So this rule asks for more
 * places, more time, and a signal that was close EVERY time it counted:
 *
 *  - only sightings at or above the RSSI floor are used at all. A device that was
 *    merely in range at several of the user's places (a car in the same traffic)
 *    does not count; one that was close each time does;
 *  - places are [Places]: positions further apart than 300 m AND than their
 *    fixes could be wrong by, with a place seen once and immediately left for the
 *    place before it (A, B, A) taken for one bad position fix and dropped;
 *  - sightings are counted at most once per [DEDUP_MS], or sooner when the user has
 *    moved on to a different place since the last one counted. Sitting next to a
 *    device for ten minutes is one piece of evidence; a device that stays close
 *    through a ten-minute drive has given several. Counting by the clock alone
 *    made a tracker hidden in a car invisible on any commute under about twenty
 *    minutes, because each drive was over before it could count twice.
 *
 * There is no separated-state flag to wait for, so every device is a candidate.
 *
 * What this cannot do, however it is tuned: tell a device planted on the user from
 * one carried by someone travelling with them. A fellow passenger's or a
 * companion's device on a long enough shared journey satisfies the rule exactly as
 * a planted one does. The alert says so, and "It's mine" exists for that.
 */
object NearbyDeviceEvaluator {

    private const val DEDUP_MS = 15 * 60_000L

    // "Watching" needs real movement but not much of it.
    private const val WATCH_PLACES = 2
    private const val WATCH_SIGHTINGS = 2
    private const val WATCH_SPAN_MIN = 15

    data class Thresholds(
        val minPlaces: Int,
        val minSpanMin: Int,
        val minSightings: Int,
        val rssiFloorDbm: Int
    )

    fun thresholdsFor(s: Sensitivity): Thresholds = when (s) {
        Sensitivity.HIGH -> Thresholds(minPlaces = 3, minSpanMin = 60, minSightings = 4, rssiFloorDbm = -85)
        Sensitivity.MEDIUM -> Thresholds(minPlaces = 4, minSpanMin = 120, minSightings = 5, rssiFloorDbm = -80)
        Sensitivity.LOW -> Thresholds(minPlaces = 5, minSpanMin = 180, minSightings = 6, rssiFloorDbm = -75)
    }

    fun evaluate(sightings: List<SightingEntity>, t: Thresholds): Assessment {
        val close = sightings.filter { it.rssi >= t.rssiFloorDbm }.sortedBy { it.timestamp }
        if (close.isEmpty()) {
            return Assessment(RiskState.OBSERVED, 0, 0, 0, closeEnough = false, separatedSeen = true)
        }

        var effective = 0
        var lastCounted: SightingEntity? = null
        for (s in close) {
            val prev = lastCounted
            if (prev == null || s.timestamp - prev.timestamp >= DEDUP_MS || Places.movedOn(prev, s)) {
                effective++
                lastCounted = s
            }
        }
        val spanMin = (close.last().timestamp - close.first().timestamp) / 60_000
        val places = Places.distinct(close.mapNotNull(Places::fixOf))

        val riskState = when {
            places >= t.minPlaces && spanMin >= t.minSpanMin && effective >= t.minSightings ->
                RiskState.ALERTING
            places >= WATCH_PLACES && effective >= WATCH_SIGHTINGS && spanMin >= WATCH_SPAN_MIN ->
                RiskState.SUSPICIOUS
            else -> RiskState.OBSERVED
        }
        return Assessment(riskState, effective, places, spanMin, closeEnough = true, separatedSeen = true)
    }

    /**
     * The state to store for a flagged device that was heard again without a new
     * sighting being written: what was [stored], unless the sightings still in the
     * window now support less. Never more: a rise has to come with a sighting.
     */
    fun settled(stored: RiskState, supported: RiskState): RiskState =
        if (supported.ordinal < stored.ordinal) supported else stored

}
