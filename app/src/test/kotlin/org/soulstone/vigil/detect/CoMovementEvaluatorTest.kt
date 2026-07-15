package org.soulstone.vigil.detect

import org.junit.Assert.assertEquals
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

    private fun s(tsMin: Long, rssi: Int, cell: String?, separated: Boolean) =
        SightingEntity(trackerId = "x", timestamp = tsMin * 60_000L, rssi = rssi, separated = separated, geohash7 = cell)

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
        val list = listOf(
            s(0, -70, "u000001", true),
            s(30, -70, "u000001", true),
            s(60, -70, "u000002", true)
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
}
