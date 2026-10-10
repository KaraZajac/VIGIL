package org.soulstone.vigil.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.vigil.detect.PresenceEngine
import org.soulstone.vigil.model.Sensitivity
import java.util.Random

/**
 * The evaluation the paper (§7) specifies and design doc §5 step 3 makes possible
 * offline: synthetic attack and ambient traces replayed through both detection
 * paths, scoring detection, latency, and false positives per carry-day.
 *
 * These are simulations, not field captures — the path-loss/mobility models are
 * stated in [SimTraces] and the numbers are only as good as those models. They
 * cannot validate the wire-format offsets or real RF behaviour; they DO validate
 * the detection logic end-to-end and are deterministic (fixed seeds), so a
 * threshold change shows up here immediately.
 */
class DetectionEvalTest {

    private val minute = 60_000L
    private fun rng() = Random(20260828L)

    private fun report(name: String, o: ReplayHarness.Outcome) {
        val lat = o.latencyMin()?.let { "%.1f min".format(it) } ?: "never"
        println(
            "[eval] %-34s detected=%-5s first-alert=%-9s identity=%-9s clone=%-9s tier=%-9s score=%3d seamless=%3d ids=%5d fp/day=%.2f"
                .format(
                    name, o.detected, lat,
                    o.identityAlertMs?.let { "%.1fm".format(it / 60_000.0) } ?: "-",
                    o.cloneAlertMs?.let { "%.1fm".format(it / 60_000.0) } ?: "-",
                    o.maxTier, o.maxScore, o.maxSeamlessNovel, o.distinctIds, o.alertsPerCarryDay
                )
        )
    }

    // ---- ground-truth positives ----------------------------------------------

    /**
     * The published Find You configuration — 30 s rotation, 100% duty, fixed power,
     * carried on the victim while walking. This is the attack that raised ZERO
     * alerts on iOS, Tracker Detect and AirGuard over five days. Paper §7 targets
     * detection within 10–15 min.
     */
    @Test
    fun findYouCloneWalkingIsDetectedWithinFifteenMinutes() {
        val trace = SimTraces.clone(
            SimTraces.CloneConfig(rotationMs = 30_000L),
            SimTraces.walk(), durationMs = 30 * minute, rng = rng()
        )
        val o = ReplayHarness.run(trace)
        report("find-you clone, walking", o)

        assertEquals(PresenceEngine.Tier.CONFIRMED, o.maxTier)
        assertNotNull("clone path must alert on the Find You config", o.cloneAlertMs)
        assertTrue(
            "latency ${o.latencyMin()} min exceeds the paper's 15 min target",
            o.latencyMin()!! <= 15.0
        )
        // The whole point: the identity path is blind to this attack.
        assertNull("identity path is expected to be blind to a 30 s rotation", o.identityAlertMs)
    }

    /** A spec-compliant separated tag (one identity for 24 h) is the identity path's job. */
    @Test
    fun specCompliantTagIsCaughtByIdentityPath() {
        val trace = SimTraces.clone(
            SimTraces.CloneConfig(rotationMs = 24 * 3_600_000L),
            SimTraces.walk(), durationMs = 3 * 60 * minute, rng = rng()
        )
        val o = ReplayHarness.run(trace)
        report("spec-compliant tag (24 h id)", o)

        assertNotNull("identity path must catch a static-identity follower", o.identityAlertMs)
        // And the churn gate must keep the clone path quiet — no impossible rotation here.
        assertEquals(PresenceEngine.Tier.CLEAR, o.maxTier)
    }

    /** Attack-in-context: the clone carried through dense-urban ambient traffic. */
    @Test
    fun cloneInDenseUrbanAmbientIsStillDetected() {
        val trace = SimTraces.merge(
            SimTraces.clone(
                SimTraces.CloneConfig(rotationMs = 30_000L),
                SimTraces.walk(), durationMs = 30 * minute, rng = rng()
            ),
            SimTraces.urbanAmbient(SimTraces.walk(), 30 * minute, rng())
        )
        val o = ReplayHarness.run(trace)
        report("clone + dense-urban ambient", o)
        assertEquals(PresenceEngine.Tier.CONFIRMED, o.maxTier)
    }

    // ---- ground-truth negatives (false-positive budget) ----------------------

    @Test
    fun denseUrbanAmbientRaisesNoAlert() {
        val trace = SimTraces.urbanAmbient(SimTraces.walk(), 3 * 60 * minute, rng())
        val o = ReplayHarness.run(trace)
        report("dense-urban walk, no emitter", o)
        assertEquals("dense-urban ambient must not alert", 0, o.totalAlerts)
    }

    @Test
    fun atHomeAmbientRaisesNoAlert() {
        val trace = SimTraces.homeAmbient(3 * 60 * minute, rng())
        val o = ReplayHarness.run(trace)
        report("at home, static household tags", o)
        assertEquals("stationary household tags must not alert", 0, o.totalAlerts)
    }

    /** The hard case of design doc §3.3 / paper §5.5: rush-hour transit. */
    @Test
    fun rushHourTransitRaisesNoAlert() {
        val trace = SimTraces.transitAmbient(60 * minute, rng(), riders = 12, churn = true)
        val o = ReplayHarness.run(trace)
        report("rush-hour transit (boarding churn)", o)
        assertEquals("rush-hour transit must not raise a hard alert", 0, o.totalAlerts)
    }

    // ---- robustness curves (paper §7 "robustness versus rotation / duty cycle") --

    /**
     * The **rotation-rate squeeze** (paper §5.4): VIGIL runs the identity path and
     * the churn path together. The design claim was that no rotation period evades
     * both; this sweep measures what the shipped thresholds actually catch, at every
     * sensitivity the app offers, and prints the band that evades both paths.
     */
    @Test
    fun rotationRateSqueezeSweep() {
        val periods = listOf(
            30_000L, 60_000L, 90_000L, 2 * minute, 5 * minute, 15 * minute,
            30 * minute, 45 * minute, 60 * minute, 90 * minute, 120 * minute, 24 * 3_600_000L
        )
        // Minutes throughout, so 90 min can't round to a misleading "2 h".
        fun label(p: Long) = if (p == 24 * 3_600_000L) "24 h" else "%.1f min".format(p / 60_000.0)

        for (sensitivity in Sensitivity.entries) {
            println("[eval] --- rotation-rate squeeze sweep (3 h carry, walking, $sensitivity) ---")
            val gaps = ArrayList<String>()
            for (p in periods) {
                val trace = SimTraces.clone(
                    SimTraces.CloneConfig(rotationMs = p),
                    SimTraces.walk(), durationMs = 3 * 60 * minute, rng = rng()
                )
                val o = ReplayHarness.run(trace, sensitivity)
                val by = when {
                    o.identityAlertMs != null && o.cloneAlertMs != null -> "BOTH"
                    o.identityAlertMs != null -> "identity"
                    o.cloneAlertMs != null -> "churn"
                    else -> "*** NEITHER ***"
                }
                if (!o.detected) gaps += label(p)
                println(
                    "[eval]   rotation %-8s -> caught by %-15s first-alert=%-9s tier=%-9s seamless=%3d"
                        .format(
                            label(p), by,
                            o.latencyMin()?.let { "%.1f min".format(it) } ?: "never",
                            o.maxTier, o.maxSeamlessNovel
                        )
                )
            }
            println(
                if (gaps.isEmpty()) "[eval]   $sensitivity: squeeze holds - every period caught by a path"
                else "[eval]   $sensitivity: SQUEEZE GAP - evaded both paths at: ${gaps.joinToString(", ")}"
            )
            // Endpoints are the two paths' core competencies and must always hold.
            assertTrue("$sensitivity: 30 s rotation must be caught by the churn path", "0.5 min" !in gaps)
            assertTrue("$sensitivity: a 24 h identity must be caught by the identity path", "24 h" !in gaps)
        }
    }

    /** Duty-cycling is the adaptation design doc §4.2 calls the biting one. */
    @Test
    fun dutyCycleRobustnessSweep() {
        println("[eval] --- duty-cycle robustness (30 s rotation, 1 h carry, walking) ---")
        val cycles = listOf(
            Triple("100%", 0L, 0L),
            Triple("50% (30s/30s)", 30_000L, 30_000L),
            Triple("20% (30s/120s)", 30_000L, 120_000L),
            Triple("10% (30s/270s)", 30_000L, 270_000L)
        )
        for ((label, on, off) in cycles) {
            val trace = SimTraces.clone(
                SimTraces.CloneConfig(rotationMs = 30_000L, onMs = on, offMs = off),
                SimTraces.walk(), durationMs = 60 * minute, rng = rng()
            )
            val o = ReplayHarness.run(trace)
            println(
                "[eval]   duty %-16s detected=%-5s first-alert=%-9s tier=%-9s seamless=%3d"
                    .format(
                        label, o.detected,
                        o.latencyMin()?.let { "%.1f min".format(it) } ?: "never",
                        o.maxTier, o.maxSeamlessNovel
                    )
            )
        }
    }

    /** Variable TX power is the other published adaptation; it attacks RSSI coherence. */
    @Test
    fun txPowerJitterRobustnessSweep() {
        println("[eval] --- TX-power jitter robustness (30 s rotation, 1 h carry, walking) ---")
        for (sigma in listOf(2.5, 5.0, 8.0, 12.0)) {
            val trace = SimTraces.clone(
                SimTraces.CloneConfig(rotationMs = 30_000L, sigmaDb = sigma),
                SimTraces.walk(), durationMs = 60 * minute, rng = rng()
            )
            val o = ReplayHarness.run(trace)
            println(
                "[eval]   sigma %4.1f dB  detected=%-5s first-alert=%-9s tier=%-9s score=%3d seamless=%3d"
                    .format(
                        sigma, o.detected,
                        o.latencyMin()?.let { "%.1f min".format(it) } ?: "never",
                        o.maxTier, o.maxScore, o.maxSeamlessNovel
                    )
            )
        }
    }
}
