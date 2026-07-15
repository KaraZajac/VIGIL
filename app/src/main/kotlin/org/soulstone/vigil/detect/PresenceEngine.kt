package org.soulstone.vigil.detect

import org.soulstone.vigil.util.Geohash
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rotation-clone detector (problem #1) — the thing AirGuard and the platform
 * detectors structurally cannot do. It ignores device identity and instead tracks
 * whether ONE physical radio maintains an unbroken, close-range, co-moving RF
 * *presence* even as its advertised identity churns thousands of times faster than
 * any DULT-compliant tracker is allowed to (24h separated rotation).
 *
 * Streaming and pure-ish: feed separated-state sightings in time order via
 * [onSighting]; it returns a [Verdict]. Session-scoped — call [reset] on start.
 * Full design + rationale: docs/detection-rotation-clone.md.
 *
 * Key discriminator: the **seamless novel-handover chain** — a single radio whose
 * id changes yields handovers into *novel* ids with no RSSI discontinuity and no
 * time gap; a crowd of many radios yields gappy, RSSI-incoherent handovers. Novel
 * churn is a *gate*, not just a score term: without it this is not a rotating
 * clone and the (separate) identity detector owns the case.
 */
class PresenceEngine {

    enum class Tier { CLEAR, WATCHING, PROBABLE, CONFIRMED }

    data class Verdict(
        val score: Int,
        val tier: Tier,
        val seamlessNovelHandovers: Int,
        val distinctCells: Int,
        val netMeters: Double,
        val novelIds: Int
    )

    companion object {
        const val R_CLOSE = -65               // dBm; the on-body/in-bag close band
        const val G_BREAK_MS = 20_000L        // gap that ends a presence run
        const val G_SEAM_MS = 6_000L          // handover must be near-immediate to be "seamless"
        const val EPS_RSSI = 8.0              // dB; one radio's step-to-step RSSI
        const val ALPHA = 0.3                 // RSSI EWMA factor
        const val NOVELTY_HORIZON_MS = 30 * 60_000L
        const val D_NET_M = 500.0             // co-movement: net displacement
        const val K_CELLS = 3                 // co-movement: distinct geohash-7 cells
        const val T_MIN_MIN = 10.0            // dwell for full duration credit
        const val N_HANDOVER = 12             // seamless novel handovers for full credit / gate
        const val RHO_NOVEL_PER_MIN = 0.7     // min sustained novel-seamless rate
    }

    private val seen = HashSet<String>()
    private val seenOrder = ArrayDeque<Pair<String, Long>>()
    private var novelCount = 0

    private var active = false
    private var startTs = 0L
    private var lastTs = 0L
    private var lastId: String? = null
    private var ewmaRssi = 0.0
    private var ewmaAbsResid = 0.0
    private var seamlessNovel = 0
    private val cells = HashSet<String>()
    private var startLat = 0.0
    private var startLon = 0.0
    private var haveStart = false
    private var netMeters = 0.0

    @Synchronized
    fun reset() {
        seen.clear(); seenOrder.clear(); novelCount = 0; endRun()
    }

    @Synchronized
    fun onSighting(id: String, rssi: Int, ts: Long, lat: Double?, lon: Double?): Verdict {
        // Novelty bookkeeping over the horizon.
        while (seenOrder.isNotEmpty() && ts - seenOrder.first().second > NOVELTY_HORIZON_MS) {
            seen.remove(seenOrder.removeFirst().first)
        }
        val novel = id !in seen
        if (novel) { seen.add(id); seenOrder.addLast(id to ts); novelCount++ }

        // Only the close band builds a presence run.
        if (rssi < R_CLOSE) return verdict()
        if (!active || ts - lastTs > G_BREAK_MS) { startRun(id, rssi, ts, lat, lon); return verdict() }

        val resid = rssi - ewmaRssi
        if (id != lastId) {
            if (abs(resid) <= EPS_RSSI && (ts - lastTs) <= G_SEAM_MS && novel) seamlessNovel++
        }
        ewmaRssi = ALPHA * rssi + (1 - ALPHA) * ewmaRssi
        ewmaAbsResid = ALPHA * abs(resid) + (1 - ALPHA) * ewmaAbsResid
        lastTs = ts; lastId = id
        if (lat != null && lon != null) {
            cells.add(Geohash.encode(lat, lon, 7))
            if (haveStart) netMeters = haversineMeters(startLat, startLon, lat, lon)
        }
        return verdict()
    }

    private fun startRun(id: String, rssi: Int, ts: Long, lat: Double?, lon: Double?) {
        active = true; startTs = ts; lastTs = ts; lastId = id
        ewmaRssi = rssi.toDouble(); ewmaAbsResid = 0.0; seamlessNovel = 0
        cells.clear(); netMeters = 0.0
        if (lat != null && lon != null) {
            startLat = lat; startLon = lon; haveStart = true; cells.add(Geohash.encode(lat, lon, 7))
        } else haveStart = false
    }

    private fun endRun() {
        active = false; lastId = null; seamlessNovel = 0; cells.clear(); netMeters = 0.0; haveStart = false
    }

    private fun verdict(): Verdict {
        if (!active) return Verdict(0, Tier.CLEAR, 0, 0, 0.0, novelCount)
        val durMin = (lastTs - startTs) / 60_000.0

        // Novel-churn is a GATE: no impossible rotation rate => not a clone, the
        // identity detector owns it.
        val churnAnom = durMin > 0 && seamlessNovel >= N_HANDOVER &&
            (seamlessNovel / durMin) >= RHO_NOVEL_PER_MIN
        if (!churnAnom) return Verdict(0, Tier.CLEAR, seamlessNovel, cells.size, netMeters, novelCount)

        val comoves = netMeters >= D_NET_M && cells.size >= K_CELLS
        if (!comoves) {
            // Impossible churn but co-movement not yet established (e.g. dense area) — soft only.
            return Verdict(50, Tier.WATCHING, seamlessNovel, cells.size, netMeters, novelCount)
        }

        val coherent = ewmaAbsResid <= EPS_RSSI
        var score = 0.0
        score += (durMin / T_MIN_MIN).coerceIn(0.0, 1.0) * 30
        score += (seamlessNovel.toDouble() / N_HANDOVER).coerceIn(0.0, 1.0) * 30
        score += (cells.size.toDouble() / (2.0 * K_CELLS)).coerceIn(0.0, 1.0) * 20
        score += (if (coherent) 1.0 else 0.4) * 20
        val s = score.roundToInt()
        val tier = when {
            s >= 85 -> Tier.CONFIRMED
            s >= 70 -> Tier.PROBABLE
            s >= 40 -> Tier.WATCHING
            else -> Tier.CLEAR
        }
        return Verdict(s, tier, seamlessNovel, cells.size, netMeters, novelCount)
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
