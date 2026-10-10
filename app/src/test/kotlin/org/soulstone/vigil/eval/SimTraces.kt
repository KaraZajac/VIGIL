package org.soulstone.vigil.eval

import java.util.Random
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Synthetic sighting traces for the offline record/replay harness (design doc §5
 * step 3, paper §7). Everything generated here is a SEPARATED-state advert — the
 * only population either detection path consumes. Distance becomes RSSI through a
 * log-distance path-loss model with Gaussian shadowing, and every generator takes
 * a seeded [Random], so a scenario replays bit-identically.
 *
 * These stand in for field captures until the real record/replay corpus exists:
 * the emitter model mirrors the published Find You configuration (2 s adverts,
 * ~30 s key rotation, carried on-body), and the ambient classes mirror the
 * ground-truth-negative environments of design doc §5.2.
 */
object SimTraces {

    const val ADV_INTERVAL_MS = 2_000L      // separated trackers advertise every ~2 s
    const val P_1M_DBM = -52.0              // received power at 1 m
    const val PATH_LOSS_EXP = 2.2           // urban/indoor-ish path-loss exponent
    private const val LAT0 = 40.0
    private const val LON0 = -74.0
    private const val M_PER_DEG_LAT = 111_320.0

    data class SimSighting(val id: String, val rssi: Int, val ts: Long, val lat: Double, val lon: Double)

    data class XY(val x: Double, val y: Double)
    data class LL(val lat: Double, val lon: Double)

    /** Victim position at time t, in metres from the origin. */
    fun interface Motion { fun at(ts: Long): XY }

    fun stationary() = Motion { XY(0.0, 0.0) }

    fun walk(speedMps: Double = 1.4) = Motion { ts -> XY(ts / 1000.0 * speedMps, 0.0) }

    /** Stop-and-go transit: cruise between stops, dwelling [dwellMs] every [legMs]. */
    fun transitMotion(cruiseMps: Double = 8.0, legMs: Long = 90_000L, dwellMs: Long = 20_000L) =
        Motion { ts ->
            val cycle = legMs + dwellMs
            val moved = (ts / cycle) * cruiseMps * (legMs / 1000.0) +
                cruiseMps * (minOf(ts % cycle, legMs) / 1000.0)
            XY(moved, 0.0)
        }

    fun toLatLon(p: XY): LL =
        LL(LAT0 + p.y / M_PER_DEG_LAT, LON0 + p.x / (M_PER_DEG_LAT * cos(Math.toRadians(LAT0))))

    fun rssiAt(distM: Double, rng: Random, sigmaDb: Double): Int =
        (P_1M_DBM - 10 * PATH_LOSS_EXP * log10(max(distM, 0.1)) + rng.nextGaussian() * sigmaDb).roundToInt()

    // ---- the attack -----------------------------------------------------------

    /**
     * Rotating-clone emitter carried by the victim. The published Find You config is
     * rotation 30 s at 100% duty with fixed power; [sigmaDb] models per-advert RSSI
     * noise (raise it for the variable-TX-power adaptation), [onMs]/[offMs] model the
     * duty-cycling adaptation (design doc §4.2). A rotation longer than the trace
     * makes this a spec-compliant 24 h-class tag.
     */
    data class CloneConfig(
        val rotationMs: Long,
        val sigmaDb: Double = 2.5,
        val onMs: Long = 0L,
        val offMs: Long = 0L,
        val distM: Double = 0.5
    )

    fun clone(cfg: CloneConfig, motion: Motion, durationMs: Long, rng: Random): List<SimSighting> {
        val out = ArrayList<SimSighting>()
        var ts = 0L
        while (ts < durationMs) {
            val emitting = cfg.offMs <= 0L || (ts % (cfg.onMs + cfg.offMs)) < cfg.onMs
            if (emitting) {
                val ll = toLatLon(motion.at(ts))
                out += SimSighting(
                    id = "clone-${ts / cfg.rotationMs}",
                    rssi = rssiAt(cfg.distM, rng, cfg.sigmaDb),
                    ts = ts, lat = ll.lat, lon = ll.lon
                )
            }
            ts += ADV_INTERVAL_MS
        }
        return out
    }

    // ---- ambient (ground-truth-negative) classes ------------------------------

    /**
     * Dense-urban ambient: a stream of tag-carrying passers (walkers to cars), each
     * with the bounded close-band dwell of design doc §1.4(c) — approach, closest
     * pass at [1..13] m, recede. High novel churn, but gappy and RSSI-incoherent.
     */
    fun urbanAmbient(
        motion: Motion,
        durationMs: Long,
        rng: Random,
        meanGapMs: Long = 15_000L
    ): List<SimSighting> {
        val out = ArrayList<SimSighting>()
        var pass = (rng.nextDouble() * meanGapMs).toLong()
        var k = 0
        while (pass < durationMs) {
            val vRel = 0.8 + rng.nextDouble() * 12.0
            val dMin = 1.0 + rng.nextDouble() * 12.0
            val id = "amb-${k++}"
            val halfMs = (60.0 / vRel * 1000).toLong()   // audible within ~60 m
            var t = pass - halfMs
            while (t <= pass + halfMs) {
                if (t in 0 until durationMs) {
                    val dt = (t - pass) / 1000.0
                    val d = sqrt(dMin * dMin + vRel * dt * vRel * dt)
                    val ll = toLatLon(motion.at(t))
                    out += SimSighting(id, rssiAt(d, rng, 3.0), t, ll.lat, ll.lon)
                }
                t += ADV_INTERVAL_MS
            }
            pass += (meanGapMs * (0.3 + 1.4 * rng.nextDouble())).toLong()
        }
        return out.sortedBy { it.ts }
    }

    /** At home: a handful of static household tags around a stationary victim. */
    fun homeAmbient(durationMs: Long, rng: Random, tags: Int = 5): List<SimSighting> {
        val out = ArrayList<SimSighting>()
        val dists = DoubleArray(tags) { 1.0 + rng.nextDouble() * 8.0 }
        val ll = toLatLon(XY(0.0, 0.0))
        var ts = 0L
        while (ts < durationMs) {
            for (i in 0 until tags) {
                out += SimSighting("home-$i", rssiAt(dists[i], rng, 2.5), ts + i * 137L, ll.lat, ll.lon)
            }
            ts += ADV_INTERVAL_MS
        }
        return out
    }

    /**
     * Transit: stable co-riding separated tags at fixed seat distances and — when
     * [churn] — bursty boarding/alighting at every stop, boarders walking the aisle
     * past the victim. The rush-hour case of design doc §3.3: novel churn, vehicular
     * co-movement, and continuous close-band occupancy all align.
     */
    fun transitAmbient(
        durationMs: Long,
        rng: Random,
        riders: Int = 10,
        churn: Boolean = true,
        legMs: Long = 90_000L,
        dwellMs: Long = 20_000L
    ): List<SimSighting> {
        val motion = transitMotion(legMs = legMs, dwellMs = dwellMs)

        class Rider(val id: String, val seatDistM: Double, val boardTs: Long) {
            var alightTs: Long = Long.MAX_VALUE
        }

        val all = ArrayList<Rider>()
        var n = 0
        repeat(riders) { all += Rider("rider-${n++}", 0.8 + rng.nextDouble() * 9.0, 0L) }
        if (churn) {
            var stopTs = legMs
            while (stopTs < durationMs) {
                for (r in all) {
                    if (r.alightTs == Long.MAX_VALUE && r.boardTs < stopTs && rng.nextDouble() < 0.15) {
                        r.alightTs = stopTs
                    }
                }
                repeat(rng.nextInt(4)) { all += Rider("rider-${n++}", 0.8 + rng.nextDouble() * 9.0, stopTs) }
                stopTs += legMs + dwellMs
            }
        }
        val out = ArrayList<SimSighting>()
        for (r in all) {
            var ts = r.boardTs + (r.id.hashCode().toLong() and 0x7FF)
            while (ts < minOf(r.alightTs, durationMs)) {
                val sinceBoard = ts - r.boardTs
                val d = if (sinceBoard < 10_000L) {
                    8.0 - (8.0 - r.seatDistM) * (sinceBoard / 10_000.0)   // aisle walk, door to seat
                } else {
                    r.seatDistM + rng.nextGaussian() * 0.2
                }
                val ll = toLatLon(motion.at(ts))
                out += SimSighting(r.id, rssiAt(max(d, 0.3), rng, 2.5), ts, ll.lat, ll.lon)
                ts += ADV_INTERVAL_MS
            }
        }
        return out.sortedBy { it.ts }
    }

    /** Merge component traces into one time-ordered stream, as the scanner would see it. */
    fun merge(vararg traces: List<SimSighting>): List<SimSighting> =
        traces.flatMap { it }.sortedBy { it.ts }
}
