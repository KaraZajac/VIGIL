package org.soulstone.vigil.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.soulstone.vigil.data.db.AlertEntity
import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.data.db.VigilDatabase
import org.soulstone.vigil.detect.BaselineManager
import org.soulstone.vigil.detect.CoMovementEvaluator
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.model.TrackerObservation
import org.soulstone.vigil.model.TrailPoint
import org.soulstone.vigil.util.Geohash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    /** Wipe the whole tracker list + sighting history (they re-populate as devices
     *  are re-detected). Keeps learned baseline places. */
    suspend fun clearAll() = mutex.withLock {
        db.trackerDao().clearAll()
        db.sightingDao().clearAll()
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
            effectiveSightings = assessment.sightings,
            lastMac = obs.mac
        )
        db.trackerDao().upsert(updated)
        if (newlyAlerting) {
            db.alertDao().insert(
                AlertEntity(
                    trackerId = obs.stableId,
                    ecosystem = obs.ecosystem.name,
                    label = obs.label,
                    timestamp = now,
                    distinctPlaces = assessment.distinctPlaces,
                    peakRssi = updated.peakRssi,
                    lat = lat, lon = lon
                )
            )
        }
        RecordResult(updated, newlyAlerting)
    }

    fun observeAlerts(): Flow<List<AlertEntity>> = db.alertDao().observeAll()

    suspend fun clearAlerts() = db.alertDao().clear()

    /** Geotagged sightings of one tracker, in time order, for the in-app trail. */
    suspend fun trailFor(id: String): List<TrailPoint> =
        db.sightingDao().allFor(id).mapNotNull { s ->
            val la = s.lat; val lo = s.lon
            if (la != null && lo != null) TrailPoint(la, lo) else null
        }

    /** Human-readable evidence report; null if there are no alerts yet. */
    suspend fun buildTextReport(): String? {
        val alerts = db.alertDao().all()
        if (alerts.isEmpty()) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("VIGIL — tracker evidence report")
        sb.appendLine("Generated: ${fmt.format(Date())}")
        sb.appendLine("Times are this phone's local time; locations are approximate (phone GPS).")
        sb.appendLine("=".repeat(52))
        for ((trackerId, group) in alerts.groupBy { it.trackerId }) {
            val head = group.first()
            sb.appendLine()
            sb.appendLine("${ecoLabel(head.ecosystem)} — ${head.label}")
            sb.appendLine("Identity: $trackerId")
            sb.appendLine("Flagged ${group.size} time(s):")
            for (a in group) {
                val where = if (a.lat != null && a.lon != null) "%.5f, %.5f".format(a.lat, a.lon) else "no location"
                sb.appendLine("  - ${fmt.format(Date(a.timestamp))} : ${a.distinctPlaces} places, peak ${a.peakRssi} dBm, at $where")
            }
            val geo = db.sightingDao().allFor(trackerId).filter { it.lat != null && it.lon != null }
            if (geo.isNotEmpty()) {
                sb.appendLine("  Seen with you at ${geo.size} location(s):")
                for (s in geo) {
                    sb.appendLine("    ${fmt.format(Date(s.timestamp))}  ${"%.5f, %.5f".format(s.lat, s.lon)}  ${s.rssi} dBm")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("=".repeat(52))
        sb.appendLine("Recorded by VIGIL, an offline anti-tracking app — a log of Bluetooth trackers detected moving with this phone.")
        return sb.toString()
    }

    /** GPX of every geotagged sighting, to open in a maps app. */
    suspend fun buildGpx(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="VIGIL" xmlns="http://www.topografix.com/GPX/1/1">""")
        for (s in db.sightingDao().allGeotagged()) {
            sb.appendLine("""  <wpt lat="${s.lat}" lon="${s.lon}">""")
            sb.appendLine("    <time>${fmt.format(Date(s.timestamp))}</time>")
            sb.appendLine("    <name>${trackerShortName(s.trackerId)} ${s.rssi}dBm</name>")
            sb.appendLine("  </wpt>")
        }
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    companion object {
        const val RETENTION_DAYS = 14
    }
}

private fun ecoLabel(name: String): String =
    runCatching { TrackerEcosystem.valueOf(name).display }.getOrDefault(name)

private fun trackerShortName(stableId: String): String = when (stableId.substringBefore(':')) {
    "apple" -> "AppleFindMy"
    "fmdn" -> "GoogleFMD"
    "samsung" -> "SmartTag"
    "tile" -> "Tile"
    "dult" -> "DULT"
    else -> "Tracker"
}
