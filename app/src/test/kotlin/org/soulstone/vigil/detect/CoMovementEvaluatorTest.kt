package org.soulstone.vigil.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem

/** Locks the co-movement alert logic: dedup, the RSSI proximity gate, and the
 *  distinct-places / separated-state thresholds. MEDIUM = 3 sightings / 3 places /
 *  45 min / -85 dBm. */
class CoMovementEvaluatorTest {

    private val t = CoMovementEvaluator.thresholdsFor(Sensitivity.MEDIUM)

    /** A sighting at stop [cell]: stops are 1.1 km apart (0.01 degrees of latitude). */
    private fun s(tsMin: Long, rssi: Int, cell: String?, separated: Boolean, acc: Float? = 15f) =
        SightingEntity(
            trackerId = "x", timestamp = tsMin * 60_000L, rssi = rssi, separated = separated,
            lat = cell?.let { 40.75 + it.takeLast(2).toInt() * 0.01 }, lon = cell?.let { -73.98 },
            accuracy = cell?.let { acc }
        )

    @Test fun emptyIsObserved() {
        val a = CoMovementEvaluator.evaluate(emptyList(), TrackerEcosystem.APPLE_FIND_MY, t)
        assertEquals(RiskState.OBSERVED, a.riskState)
        assertEquals(0, a.distinctPlaces)
    }

    @Test fun separatedCloseAcrossPlacesAlerts() {
        val list = listOf(
            s(0, -70, "u000001", true),
            s(30, -68, "u000002", true),
            s(60, -72, "u000003", true)
        )
        val a = CoMovementEvaluator.evaluate(list, TrackerEcosystem.APPLE_FIND_MY, t)
        assertEquals(RiskState.ALERTING, a.riskState)
        assertEquals(3, a.distinctPlaces)
        assertEquals(3, a.sightings)
    }

    @Test fun farSignalIsGatedOut() {
        val list = listOf(
            s(0, -95, "u000001", true),
            s(30, -96, "u000002", true),
            s(60, -97, "u000003", true)
        )
        // Seen across 3 places over an hour, but never close -> the RSSI gate holds it back.
        assertEquals(RiskState.OBSERVED, CoMovementEvaluator.evaluate(list, TrackerEcosystem.APPLE_FIND_MY, t).riskState)
    }

    @Test fun tooFewPlacesIsSuspiciousNotAlerting() {
        // Two places. A new place needs a second position there (or to be passed
        // through) before it counts, so the last stop is heard twice.
        val list = listOf(
            s(0, -70, "u000001", true),
            s(30, -70, "u000001", true),
            s(60, -70, "u000002", true),
            s(75, -70, "u000002", true)
        )
        val a = CoMovementEvaluator.evaluate(list, TrackerEcosystem.APPLE_FIND_MY, t)
        assertEquals(RiskState.SUSPICIOUS, a.riskState)
        assertEquals(2, a.distinctPlaces)
    }

    @Test fun nearOwnerNeverEscalates() {
        val list = listOf(
            s(0, -70, "u000001", false),
            s(30, -70, "u000002", false),
            s(60, -70, "u000003", false)
        )
        assertEquals(RiskState.OBSERVED, CoMovementEvaluator.evaluate(list, TrackerEcosystem.APPLE_FIND_MY, t).riskState)
    }

    @Test fun tileHasNoSeparatedFlagButStillCounts() {
        val list = listOf(
            s(0, -70, "u000001", false),
            s(30, -70, "u000002", false),
            s(60, -70, "u000003", false)
        )
        // Tile emits no separated flag, so any Tile is a live candidate.
        assertEquals(RiskState.ALERTING, CoMovementEvaluator.evaluate(list, TrackerEcosystem.TILE, t).riskState)
    }

    // --- places are distances, and moving on counts ------------------------------

    private val high = CoMovementEvaluator.thresholdsFor(Sensitivity.HIGH)

    private fun at(tsMin: Long, lat: Double, rssi: Int = -60, separated: Boolean = true, acc: Float = 15f) =
        SightingEntity(
            trackerId = "x", timestamp = tsMin * 60_000L, rssi = rssi, separated = separated,
            lat = lat, lon = -73.98, accuracy = acc
        )

    @Test fun aTagThatNeverMovesIsOnePlaceHoweverThePositionWobbles() {
        // A separated tag next door, heard from the sofa for five hours while the
        // phone's position wanders over ~220 m by ~135 m. As geohash-7 cells this
        // was several "places" and alerted on High; it is one place.
        val list = (0..20).map { i ->
            SightingEntity(
                trackerId = "x", timestamp = i * 15 * 60_000L, rssi = -78, separated = true,
                lat = 40.75 + (i % 5 - 2) * 0.0005, lon = -73.98 + (i % 3 - 1) * 0.0008, accuracy = 30f
            )
        }
        for (sens in Sensitivity.entries) {
            val a = CoMovementEvaluator.evaluate(list, TrackerEcosystem.APPLE_FIND_MY, CoMovementEvaluator.thresholdsFor(sens))
            assertEquals("$sens", 1, a.distinctPlaces)
            // Watched, as any separated tag heard this long is, but never flagged.
            assertEquals("$sens", RiskState.SUSPICIOUS, a.riskState)
        }
    }

    @Test fun aTagInTheCarOnShortDrivesIsFlaggedOnTheWayHome() {
        // Ten-minute drives at 08:00 and 17:00, heard only in the car, a sighting a
        // minute, 670 m apart. By the clock each drive was one sighting, so the
        // second drive left it at two; moving on, each drive is several.
        fun drive(startMin: Long, from: Double, to: Double) =
            (0..10).map { m -> at(startMin + m, 40.75 + (from + (to - from) * m / 10.0) * 0.01) }
        val morning = drive(8 * 60L, 0.0, 6.0)
        val homeward = drive(17 * 60L, 6.0, 0.0)
        val a = CoMovementEvaluator.evaluate(morning, TrackerEcosystem.APPLE_FIND_MY, high)
        assertEquals(11, a.sightings)
        // Ten minutes is not half an hour: only watched after the first drive.
        assertEquals(RiskState.SUSPICIOUS, a.riskState)
        for (sens in Sensitivity.entries) {
            val b = CoMovementEvaluator.evaluate(
                morning + homeward.take(1), TrackerEcosystem.APPLE_FIND_MY, CoMovementEvaluator.thresholdsFor(sens)
            )
            assertEquals("$sens", RiskState.ALERTING, b.riskState)
        }
    }

    @Test fun aTileBesideYouAtAFewRedLightsIsWatchedNotFlagged() {
        // A Tile (no separated flag) in the next car through five junctions, eight
        // minutes, close enough at every setting. It did move with you, so it is
        // watched; it was not with you for long enough to be flagged.
        val list = (0..8 step 2).map { m -> at(m.toLong(), 40.75 + m * 0.004, rssi = -75, separated = false) }
        for (sens in Sensitivity.entries) {
            val a = CoMovementEvaluator.evaluate(list, TrackerEcosystem.TILE, CoMovementEvaluator.thresholdsFor(sens))
            assertEquals("$sens", RiskState.SUSPICIOUS, a.riskState)
        }
    }

    @Test fun oneWildFixAtHomeIsNotASecondPlace() {
        // An evening at home with one position a kilometre off.
        val list = listOf(at(0, 40.75), at(15, 40.75), at(30, 40.76), at(45, 40.75), at(60, 40.75))
        val a = CoMovementEvaluator.evaluate(list, TrackerEcosystem.APPLE_FIND_MY, high)
        assertEquals(1, a.distinctPlaces)
        assertTrue(a.riskState != RiskState.ALERTING)
    }
}
