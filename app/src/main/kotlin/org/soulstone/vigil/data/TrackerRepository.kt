package org.soulstone.vigil.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.data.db.VigilDatabase
import org.soulstone.vigil.detect.BaselineManager
import org.soulstone.vigil.detect.CoMovementEvaluator
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerObservation
import org.soulstone.vigil.util.Geohash

/**
 * The temporal core. Persists sightings, maintains the learned baseline, and runs
 * the co-movement evaluation on every observation. All state is on-device.
 */
class TrackerRepository(private val db: VigilDatabase) {

    // Serialises record() so the read-modify-write of a tracker row is atomic —
    // concurrent observations were racing and losing count / first-seen updates.
    private val mutex = Mutex()

    data class RecordResult(val tracker: TrackerEntity, val newlyAlerting: Boolean)

    fun observeTrackers(): Flow<List<TrackerEntity>> = db.trackerDao().observeAll()

    // Under the same lock as record() so a concurrent observation can't clobber the
    // user's choice back to its stale value.
    suspend fun setApproved(id: String, approved: Boolean) = mutex.withLock {
        db.trackerDao().setApproved(id, approved)
    }

    suspend fun clearBaseline(id: String) = mutex.withLock {
        db.trackerDao().clearBaseline(id)
    }

    suspend fun prune(retentionDays: Int = RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - retentionDays * BaselineManager.DAY_MS
        db.sightingDao().prune(cutoff)
        db.trackerDao().pruneStale(cutoff)
    }

    /**
     * Ingest one sighting. Persists it, updates the baseline, and re-evaluates the
     * tracker's risk. Returns the updated row and whether this crossed into ALERTING.
     */
    suspend fun record(
        obs: TrackerObservation,
        lat: Double?,
        lon: Double?,
        sensitivity: Sensitivity
    ): RecordResult = mutex.withLock {
        val now = obs.timestampMs
        val geohash7 = if (lat != null && lon != null) Geohash.encode(lat, lon, 7) else null
        val geohash6 = if (lat != null && lon != null) Geohash.encode(lat, lon, 6) else null

        db.sightingDao().insert(
            SightingEntity(
                trackerId = obs.stableId,
                timestamp = now,
                rssi = obs.rssi,
                separated = obs.separated == SeparatedState.SEPARATED,
                lat = lat, lon = lon, geohash7 = geohash7
            )
        )

        val existing = db.trackerDao().get(obs.stableId)
        val approved = existing?.approved ?: false

        // Co-movement assessment first — it decides both display AND whether this
        // tag is safe to baseline. Always computed (UI shows the evidence numbers).
        val since = now - CoMovementEvaluator.WINDOW_MS
        val recent = db.sightingDao().recentFor(obs.stableId, since)
        val t = CoMovementEvaluator.thresholdsFor(sensitivity)
        val assessment = CoMovementEvaluator.evaluate(recent, obs.ecosystem, t)
        val coMoving = assessment.riskState != RiskState.OBSERVED

        // --- baseline: learn tags that live where you live -------------------
        var lastAnchorDay = existing?.lastAnchorDay ?: -1L
        var anchorDayCount = existing?.anchorDayCount ?: 0
        var baselineSafe = existing?.baselineSafe ?: false
        if (geohash6 != null) {
            val isAnchor = BaselineManager.noteLocation(db.placeDao(), geohash6, now)
            // ONLY baseline-trust a tag that is NOT co-moving with you. A planted
            // stalker tag co-moves (that's the whole detection), so it stays
            // SUSPICIOUS/ALERTING and must never be auto-trusted into silence.
            if (isAnchor && !coMoving) {
                val day = now / BaselineManager.DAY_MS
                if (day != lastAnchorDay) {
                    lastAnchorDay = day
                    anchorDayCount += 1
                }
                if (anchorDayCount >= BaselineManager.BASELINE_MIN_DAYS) baselineSafe = true
            }
        }
        // Safety revoke: if a previously-trusted tag ever starts co-moving, drop trust.
        if (baselineSafe && coMoving) baselineSafe = false

        val riskState = if (approved || baselineSafe) RiskState.OBSERVED else assessment.riskState

        val wasAlerting = existing?.riskState == RiskState.ALERTING.name
        val cooldownOk = now - (existing?.lastAlertMs ?: 0) > CoMovementEvaluator.ALERT_COOLDOWN_MS
        val newlyAlerting = riskState == RiskState.ALERTING && !wasAlerting && cooldownOk

        val updated = TrackerEntity(
            stableId = obs.stableId,
            ecosystem = obs.ecosystem.name,
            label = obs.label,
            firstSeen = existing?.firstSeen ?: now,
            lastSeen = now,
            sightingCount = (existing?.sightingCount ?: 0) + 1,
            riskState = riskState.name,
            approved = approved,
            baselineSafe = baselineSafe,
            lastAlertMs = if (newlyAlerting) now else (existing?.lastAlertMs ?: 0),
            lastAnchorDay = lastAnchorDay,
            anchorDayCount = anchorDayCount,
            lastRssi = obs.rssi,
            peakRssi = maxOf(existing?.peakRssi ?: -127, obs.rssi),
            distinctPlaces = assessment.distinctPlaces,
            effectiveSightings = assessment.sightings
        )
        db.trackerDao().upsert(updated)
        RecordResult(updated, newlyAlerting)
    }

    companion object {
        const val RETENTION_DAYS = 14
    }
}
