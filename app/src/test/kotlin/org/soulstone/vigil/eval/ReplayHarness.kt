package org.soulstone.vigil.eval

import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.detect.CoMovementEvaluator
import org.soulstone.vigil.detect.PresenceEngine
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.util.Geohash

/**
 * Offline record/replay harness (design doc §5 step 3, paper §7): replays a trace
 * of sightings through **both** detection paths exactly as the live app wires them,
 * and reports what alerted and how long it took.
 *
 * Faithfulness to the running app matters more than convenience here, so this
 * mirrors [org.soulstone.vigil.service.ScanService] and
 * [org.soulstone.vigil.data.TrackerRepository]:
 *  - the presence engine sees EVERY advert, in time order;
 *  - the identity path is throttled to one persisted sighting per device per 15 s,
 *    then re-evaluated over that device's trailing 24 h, with the 4 h re-alert
 *    cooldown; the clone path uses its own 2 h cooldown and only CONFIRMED alerts.
 *
 * The learned baseline is deliberately not replayed: [BaselineManager] needs ~3 h of
 * cumulative dwell in one geohash-6 cell to mint an anchor, which no scenario here
 * reaches, so it cannot change any verdict below.
 */
object ReplayHarness {

    private const val MIN_RECORD_INTERVAL_MS = 15_000L      // ScanService DB throttle
    private const val CLONE_COOLDOWN_MS = 2 * 3_600_000L    // ScanService clone cooldown

    data class Outcome(
        /** ms into the trace when the identity path first raised ALERTING, else null. */
        val identityAlertMs: Long?,
        val identityAlertId: String?,
        /** ms into the trace when the presence engine first hit CONFIRMED, else null. */
        val cloneAlertMs: Long?,
        val identityAlerts: Int,
        val cloneAlerts: Int,
        val maxTier: PresenceEngine.Tier,
        val maxScore: Int,
        val maxSeamlessNovel: Int,
        val distinctIds: Int,
        val durationMs: Long
    ) {
        val detected: Boolean get() = identityAlertMs != null || cloneAlertMs != null
        val firstAlertMs: Long? get() = listOfNotNull(identityAlertMs, cloneAlertMs).minOrNull()
        val totalAlerts: Int get() = identityAlerts + cloneAlerts

        /** Alerts scaled to a full carry-day — the paper's FP budget unit (§7). */
        val alertsPerCarryDay: Double
            get() = if (durationMs <= 0) 0.0 else totalAlerts * 86_400_000.0 / durationMs

        fun latencyMin(): Double? = firstAlertMs?.let { it / 60_000.0 }
    }

    fun run(
        trace: List<SimTraces.SimSighting>,
        sensitivity: Sensitivity = Sensitivity.MEDIUM,
        ecosystem: TrackerEcosystem = TrackerEcosystem.APPLE_FIND_MY
    ): Outcome {
        val thresholds = CoMovementEvaluator.thresholdsFor(sensitivity)
        val presence = PresenceEngine().apply { reset() }

        val store = HashMap<String, MutableList<SightingEntity>>()
        val lastRecordedAt = HashMap<String, Long>()
        val lastAlertMs = HashMap<String, Long>()
        val alerting = HashSet<String>()

        var identityAlertMs: Long? = null
        var identityAlertId: String? = null
        var identityAlerts = 0
        var cloneAlertMs: Long? = null
        var cloneAlerts = 0
        var lastCloneAlert = Long.MIN_VALUE / 4
        var maxTier = PresenceEngine.Tier.CLEAR
        var maxScore = 0
        var maxSeamless = 0

        for (s in trace.sortedBy { it.ts }) {
            // --- clone path: every advert, ordered ---
            val v = presence.onSighting(s.id, s.rssi, s.ts, s.lat, s.lon)
            if (v.tier.ordinal > maxTier.ordinal) maxTier = v.tier
            if (v.score > maxScore) maxScore = v.score
            if (v.seamlessNovelHandovers > maxSeamless) maxSeamless = v.seamlessNovelHandovers
            if (v.tier == PresenceEngine.Tier.CONFIRMED && s.ts - lastCloneAlert > CLONE_COOLDOWN_MS) {
                lastCloneAlert = s.ts
                cloneAlerts++
                if (cloneAlertMs == null) cloneAlertMs = s.ts
            }

            // --- identity path: throttled, persisted, re-evaluated ---
            val last = lastRecordedAt[s.id]
            if (last != null && s.ts - last < MIN_RECORD_INTERVAL_MS) continue
            lastRecordedAt[s.id] = s.ts

            val bucket = store.getOrPut(s.id) { ArrayList() }
            bucket += SightingEntity(
                trackerId = s.id, timestamp = s.ts, rssi = s.rssi, separated = true,
                lat = s.lat, lon = s.lon, geohash7 = Geohash.encode(s.lat, s.lon, 7)
            )
            val since = s.ts - CoMovementEvaluator.WINDOW_MS
            val recent = bucket.filter { it.timestamp >= since }
            val state = CoMovementEvaluator.evaluate(recent, ecosystem, thresholds).riskState

            val wasAlerting = s.id in alerting
            val cooldownOk = s.ts - (lastAlertMs[s.id] ?: Long.MIN_VALUE / 4) >
                CoMovementEvaluator.ALERT_COOLDOWN_MS
            if (state == RiskState.ALERTING && !wasAlerting && cooldownOk) {
                lastAlertMs[s.id] = s.ts
                identityAlerts++
                if (identityAlertMs == null) {
                    identityAlertMs = s.ts
                    identityAlertId = s.id
                }
            }
            if (state == RiskState.ALERTING) alerting += s.id else alerting -= s.id
        }

        val span = if (trace.isEmpty()) 0L else trace.maxOf { it.ts } - trace.minOf { it.ts }
        return Outcome(
            identityAlertMs = identityAlertMs,
            identityAlertId = identityAlertId,
            cloneAlertMs = cloneAlertMs,
            identityAlerts = identityAlerts,
            cloneAlerts = cloneAlerts,
            maxTier = maxTier,
            maxScore = maxScore,
            maxSeamlessNovel = maxSeamless,
            distinctIds = trace.mapTo(HashSet()) { it.id }.size,
            durationMs = span
        )
    }
}
