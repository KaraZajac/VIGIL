package org.soulstone.vigil.eval

import org.junit.Assert.assertEquals
import org.junit.Test
import org.soulstone.vigil.detect.PresenceEngine
import java.util.Random

/**
 * Regression test for the bug that disabled clone detection in the real app: a
 * presence run normally BEGINS before the first GPS fix, because the scanner
 * delivers adverts immediately while the fused location provider needs seconds.
 * The engine anchored its co-movement origin only in startRun(), so such a run
 * could never accumulate net displacement, never passed the co-movement gate, and
 * sat silently at WATCHING forever — a clone went undetected for the whole session.
 */
class NoFixStartTest {

    private fun maxTierWithBlindStart(blindMs: Long): Pair<PresenceEngine.Tier, Double> {
        val trace = SimTraces.clone(
            SimTraces.CloneConfig(rotationMs = 30_000L),
            SimTraces.walk(), durationMs = 30 * 60_000L, rng = Random(1)
        )
        val engine = PresenceEngine().apply { reset() }
        var max = PresenceEngine.Tier.CLEAR
        var net = 0.0
        for (s in trace) {
            val haveFix = s.ts >= blindMs
            val v = engine.onSighting(
                s.id, s.rssi, s.ts,
                if (haveFix) s.lat else null,
                if (haveFix) s.lon else null
            )
            if (v.tier.ordinal > max.ordinal) max = v.tier
            net = v.netMeters
        }
        return max to net
    }

    @Test
    fun cloneIsConfirmedEvenWhenTheRunStartsBeforeTheFirstFix() {
        // 0 ms is the only case the old code handled. Every realistic startup delay
        // used to cap the engine at WATCHING with netMeters stuck at 0.
        for (blindMs in listOf(0L, 2L, 2_000L, 10_000L, 30_000L, 120_000L)) {
            val (tier, net) = maxTierWithBlindStart(blindMs)
            println("[nofix] blind=%7d ms -> maxTier=%-9s netMeters=%.1f".format(blindMs, tier, net))
            assertEquals(
                "a clone must still confirm when the first fix arrives ${blindMs}ms late",
                PresenceEngine.Tier.CONFIRMED, tier
            )
        }
    }
}
