package org.soulstone.vigil.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the rotation-clone [PresenceEngine], driven by synthetic
 * traces standing in for the record/replay harness (design doc §5). Trackers
 * advertise every ~2 s; a rotating clone changes its advertised id every 30 s.
 */
class PresenceEngineTest {

    private val stepMs = 2_000L
    private val steps = 900               // 30 min at 2 s
    private val cloneRotationMs = 30_000L // Find You default

    private fun maxTier(
        idAt: (Int) -> String,
        rssiAt: (Int) -> Int,
        latAt: (Int) -> Double?,
        lonAt: (Int) -> Double?
    ): PresenceEngine.Tier {
        val engine = PresenceEngine()
        engine.reset()
        var max = PresenceEngine.Tier.CLEAR
        for (i in 0 until steps) {
            val v = engine.onSighting(idAt(i), rssiAt(i), i * stepMs, latAt(i), lonAt(i))
            if (v.tier.ordinal > max.ordinal) max = v.tier
        }
        return max
    }

    // A rotating clone that moves with the victim must be CONFIRMED.
    @Test
    fun cloneMovingIsConfirmed() {
        val tier = maxTier(
            idAt = { i -> "clone-${(i * stepMs) / cloneRotationMs}" },   // new id every 30 s
            rssiAt = { i -> -55 + (i % 3 - 1) },                          // close, coherent
            latAt = { 40.0 },
            lonAt = { i -> -74.0 + i * 0.00003 }                         // marches ~2.4 km
        )
        assertEquals(PresenceEngine.Tier.CONFIRMED, tier)
    }

    // Same rotating churn but stationary: co-movement gate must hold it below CONFIRMED.
    @Test
    fun cloneStationaryIsNotConfirmed() {
        val tier = maxTier(
            idAt = { i -> "clone-${(i * stepMs) / cloneRotationMs}" },
            rssiAt = { i -> -55 + (i % 3 - 1) },
            latAt = { 40.0 },
            lonAt = { -74.0 }                                            // fixed => no co-movement
        )
        assertTrue("stationary clone should not confirm, was $tier", tier.ordinal < PresenceEngine.Tier.CONFIRMED.ordinal)
    }

    // A single non-rotating tracker moving with you is NOT the presence engine's job
    // (the identity detector owns it); the churn gate must keep this CLEAR.
    @Test
    fun singleRealTrackerIsClear() {
        val tier = maxTier(
            idAt = { "realtag" },                                        // one id, never rotates
            rssiAt = { i -> -55 + (i % 3 - 1) },
            latAt = { 40.0 },
            lonAt = { i -> -74.0 + i * 0.00003 }
        )
        assertEquals(PresenceEngine.Tier.CLEAR, tier)
    }

    // Lots of novel ids but always far (out of the close band): no presence, CLEAR.
    @Test
    fun ambientFarIsClear() {
        val tier = maxTier(
            idAt = { i -> "ambient-$i" },                               // novel every time
            rssiAt = { -85 },                                            // below the close band
            latAt = { i -> 40.0 + i * 0.00003 },
            lonAt = { -74.0 }
        )
        assertEquals(PresenceEngine.Tier.CLEAR, tier)
    }
}
