package org.soulstone.vigil.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.soulstone.vigil.data.db.AlertEntity
import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.data.db.VigilDatabase
import org.soulstone.vigil.detect.BaselineManager
import org.soulstone.vigil.detect.CoMovementEvaluator
import org.soulstone.vigil.detect.NearbyDeviceEvaluator
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.model.TrackerObservation
import org.soulstone.vigil.model.TrailPoint
import org.soulstone.vigil.scan.NearbyDeviceParser
import org.soulstone.vigil.util.Geohash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The temporal core. Persists sightings, maintains the learned baseline, and runs
 * the co-movement evaluation on every observation. All state is on-device.
 */
/**
 * A record of the user's "this is mine" decisions that outlives the Room database.
 * [VigilDatabase] falls back to destructive migration, so a schema bump wipes every
 * approval and the user's own AirTag starts alerting again after an app update.
 * Keeping the allowlist outside the database survives that.
 */
interface ApprovedStore {
    fun isApproved(id: String): Boolean
    fun setApproved(id: String, approved: Boolean)
}

class TrackerRepository(
    private val db: VigilDatabase,
    private val approvedStore: ApprovedStore? = null
) {

    // Serialises record() so the read-modify-write of a tracker row is atomic —
    // concurrent observations were racing and losing count / first-seen updates.
    // One lock for the whole process, not one per instance: the activity and the
    // service each build their own repository over the same database, and with a
    // lock each, a sighting being written could put back the old value of a row
    // the user had just marked "It's mine" or "Not mine".
    private val mutex get() = sharedMutex

    data class RecordResult(val tracker: TrackerEntity, val newlyAlerting: Boolean)

    fun observeTrackers(): Flow<List<TrackerEntity>> = db.trackerDao().observeAll()

    /** The main screen's list: all trackers, and the other Bluetooth devices that
     *  are marked, flagged, or were heard since [since]. */
    fun observeTrackersForUi(since: Long): Flow<List<TrackerEntity>> =
        db.trackerDao().observeForUi(since)

    // Under the same lock as record() so a concurrent observation can't clobber the
    // user's choice back to its stale value.
    suspend fun setApproved(id: String, approved: Boolean) = mutex.withLock {
        db.trackerDao().setApproved(id, approved)
        approvedStore?.setApproved(id, approved)
    }

    suspend fun clearBaseline(id: String) = mutex.withLock {
        db.trackerDao().clearBaseline(id)
    }

    suspend fun prune(retentionDays: Int = RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - retentionDays * BaselineManager.DAY_MS
        db.sightingDao().prune(cutoff)
        db.trackerDao().pruneStale(cutoff)
        val genericCutoff = System.currentTimeMillis() - GENERIC_RETENTION_MS
        db.sightingDao().pruneGeneric(genericCutoff)
        db.trackerDao().pruneStaleGeneric(genericCutoff)
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
        sensitivity: Sensitivity,
        accuracyM: Float? = null
    ): RecordResult = mutex.withLock { recordLocked(obs, lat, lon, sensitivity, accuracyM) }

    /**
     * Ingest everything one all-devices sweep heard, as a single transaction so the
     * screen re-reads the list once per sweep instead of once per device.
     */
    suspend fun recordAll(
        observations: List<TrackerObservation>,
        lat: Double?,
        lon: Double?,
        sensitivity: Sensitivity,
        accuracyM: Float? = null,
        /** Heard in the same sweep but not due a sighting: only "last heard" moves. */
        alsoHeard: List<TrackerObservation> = emptyList()
    ): List<RecordResult> = mutex.withLock {
        if (observations.isEmpty() && alsoHeard.isEmpty()) emptyList()
        else db.withTransaction {
            val results = observations.map { recordLocked(it, lat, lon, sensitivity, accuracyM) }
            if (alsoHeard.isNotEmpty()) {
                val flagged = db.trackerDao().flaggedGenericIds().toHashSet()
                for (o in alsoHeard) {
                    db.trackerDao().touch(o.stableId, o.timestampMs, o.rssi)
                    if (o.stableId in flagged) settleLocked(o.stableId, o.timestampMs, sensitivity)
                }
            }
            results
        }
    }

    /**
     * A flagged device was heard again without a sighting being written (too weak
     * to count, or nothing new to say about it). Left alone, its stored state would
     * stand for as long as it stayed in earshot, on evidence that has since aged
     * out of the window: a neighbour's speaker that once came along on a walk would
     * read "moving with you" for good. So judge its stored sightings again and let
     * the state fall if they no longer support it.
     *
     * It never rises here. A rise has to come from a recorded sighting, because
     * that is what raises the alert and writes the history.
     */
    private suspend fun settleLocked(id: String, now: Long, sensitivity: Sensitivity) {
        val row = db.trackerDao().get(id) ?: return
        val stored = RiskState.entries.firstOrNull { it.name == row.riskState } ?: return
        val recent = db.sightingDao().recentFor(id, now - CoMovementEvaluator.WINDOW_MS)
        val assessment =
            NearbyDeviceEvaluator.evaluate(recent, NearbyDeviceEvaluator.thresholdsFor(sensitivity))
        val supported =
            if (row.approved || row.baselineSafe) RiskState.OBSERVED else assessment.riskState
        val settled = NearbyDeviceEvaluator.settled(stored, supported)
        if (settled == stored) return
        db.trackerDao().upsert(
            row.copy(
                riskState = settled.name,
                distinctPlaces = assessment.distinctPlaces,
                effectiveSightings = assessment.sightings
            )
        )
    }

    private suspend fun recordLocked(
        obs: TrackerObservation,
        lat: Double?,
        lon: Double?,
        sensitivity: Sensitivity,
        accuracyM: Float?
    ): RecordResult {
        val now = obs.timestampMs
        val geohash7 = if (lat != null && lon != null) Geohash.encode(lat, lon, 7) else null
        val geohash6 = if (lat != null && lon != null) Geohash.encode(lat, lon, 6) else null

        db.sightingDao().insert(
            SightingEntity(
                trackerId = obs.stableId,
                timestamp = now,
                rssi = obs.rssi,
                separated = obs.separated == SeparatedState.SEPARATED,
                lat = lat, lon = lon, geohash7 = geohash7,
                accuracy = accuracyM.takeIf { lat != null && lon != null }
            )
        )

        val existing = db.trackerDao().get(obs.stableId)
        // Fall back to the durable allowlist for a tracker we have no row for, which
        // is the case after a destructive migration or a "Clear all".
        val approved = existing?.approved ?: (approvedStore?.isApproved(obs.stableId) == true)

        // Co-movement assessment first — it decides both display AND whether this
        // tag is safe to baseline. Always computed (UI shows the evidence numbers).
        val since = now - CoMovementEvaluator.WINDOW_MS
        val recent = db.sightingDao().recentFor(obs.stableId, since)
        val generic = obs.ecosystem.isGeneric
        val assessment = if (generic) {
            NearbyDeviceEvaluator.evaluate(recent, NearbyDeviceEvaluator.thresholdsFor(sensitivity))
        } else {
            CoMovementEvaluator.evaluate(recent, obs.ecosystem, CoMovementEvaluator.thresholdsFor(sensitivity))
        }
        val coMoving = assessment.riskState != RiskState.OBSERVED

        // --- baseline: learn tags that live where you live -------------------
        var lastAnchorDay = existing?.lastAnchorDay ?: -1L
        var anchorDayCount = existing?.anchorDayCount ?: 0
        var baselineSafe = existing?.baselineSafe ?: false
        if (geohash6 != null) {
            // Read-only: dwell accrual now happens on a timer in ScanService via
            // [notePlace], so anchors reflect time spent somewhere rather than how
            // many trackers advertised there.
            val isAnchor = db.placeDao().get(geohash6)?.anchor == true
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

        // A device's name and its maker id often arrive in different adverts, so
        // keep the best description heard so far rather than the latest one.
        val name = obs.name.ifEmpty { existing?.name ?: "" }
        val vendor = obs.vendor.ifEmpty { existing?.vendor ?: "" }
        val label = when {
            !generic -> obs.label
            name.isNotEmpty() -> name
            obs.label != NearbyDeviceParser.UNNAMED -> obs.label
            else -> existing?.label?.takeIf { it.isNotEmpty() } ?: obs.label
        }

        val updated = TrackerEntity(
            stableId = obs.stableId,
            ecosystem = obs.ecosystem.name,
            label = label,
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
            lastMac = obs.mac,
            name = name,
            vendor = vendor,
            addrKind = if (obs.addressKind.code != 0) obs.addressKind.code else (existing?.addrKind ?: 0)
        )
        db.trackerDao().upsert(updated)
        if (newlyAlerting) {
            db.alertDao().insert(
                AlertEntity(
                    trackerId = obs.stableId,
                    ecosystem = obs.ecosystem.name,
                    label = label,
                    timestamp = now,
                    distinctPlaces = assessment.distinctPlaces,
                    peakRssi = updated.peakRssi,
                    lat = lat, lon = lon
                )
            )
        }
        return RecordResult(updated, newlyAlerting)
    }

    /**
     * Note that the user is here, independent of any tracker sighting, so anchor
     * places accrue from dwell time. Called on a timer by the scan service.
     */
    suspend fun notePlace(lat: Double, lon: Double) = mutex.withLock {
        BaselineManager.noteLocation(
            db.placeDao(), Geohash.encode(lat, lon, 6), System.currentTimeMillis()
        )
    }

    /**
     * Log a rotating-clone detection. It has no stable identity by definition, so it
     * is recorded under a synthetic id with the presence-run evidence attached.
     */
    suspend fun recordCloneAlert(
        cells: Int,
        peakRssi: Int,
        lat: Double?,
        lon: Double?,
        now: Long
    ) = mutex.withLock {
        db.alertDao().insert(
            AlertEntity(
                trackerId = CLONE_TRACKER_ID,
                ecosystem = TrackerEcosystem.ROTATING_CLONE.name,
                label = "Identity-rotating tracker",
                timestamp = now,
                distinctPlaces = cells,
                peakRssi = peakRssi,
                lat = lat, lon = lon
            )
        )
    }

    /** Last persisted clone alert, so the cooldown survives a service restart. */
    suspend fun lastCloneAlertAt(): Long? = db.alertDao().lastAlertAt(CLONE_TRACKER_ID)

    fun observeAlerts(): Flow<List<AlertEntity>> = db.alertDao().observeAll()

    /** Other Bluetooth devices that are flagged right now and were heard since [since]. */
    suspend fun alertingNearby(since: Long): List<TrackerEntity> =
        db.trackerDao().alertingGeneric(since)

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
        sb.appendLine("Times are this phone's local time; locations are approximate (phone location services, often ±100 m).")
        sb.appendLine("=".repeat(52))
        for ((trackerId, group) in alerts.groupBy { it.trackerId }) {
            val head = group.first()
            sb.appendLine()
            sb.appendLine("${ecoLabel(head.ecosystem)} — ${head.label}")
            sb.appendLine("Identity: $trackerId")
            if (head.ecosystem == TrackerEcosystem.OTHER_BLE.name) {
                sb.appendLine("Not identified as a tracker: a Bluetooth device that stayed close to this phone across")
                sb.appendLine("several places. Its name is whatever the device advertised, which anyone can set.")
                sb.appendLine("A device carried by someone travelling with this phone's owner is recorded the same way.")
            }
            sb.appendLine("Flagged ${group.size} time(s):")
            for (a in group) {
                val where = if (a.lat != null && a.lon != null) "%.5f, %.5f".format(a.lat, a.lon) else "no location"
                sb.appendLine("  - ${fmt.format(Date(a.timestamp))} : ${a.distinctPlaces} places, ${signalWord(a)} ${a.peakRssi} dBm, at $where")
            }
            val geo = db.sightingDao().allFor(trackerId).filter { it.lat != null && it.lon != null }
            if (geo.isNotEmpty()) {
                sb.appendLine("  Geotagged sightings (${geo.size}):")
                for (s in geo) {
                    sb.appendLine("    ${fmt.format(Date(s.timestamp))}  ${"%.5f, %.5f".format(s.lat, s.lon)}  ${s.rssi} dBm")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("=".repeat(52))
        sb.appendLine("Recorded by VIGIL, an offline anti-tracking app — a log of Bluetooth trackers and other Bluetooth devices detected moving with this phone.")
        return sb.toString()
    }

    /**
     * GPX of the trail of the trackers that actually alerted, to open in a maps app.
     * Deliberately NOT every sighting: an unscoped export handed over a 14-day
     * movement log of the user with every ambient tracker in it.
     */
    suspend fun buildGpx(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val alerts = db.alertDao().all()
        val ids = alerts.map { it.trackerId }.distinct()
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="VIGIL" xmlns="http://www.topografix.com/GPX/1/1">""")
        for (s in if (ids.isEmpty()) emptyList() else db.sightingDao().geotaggedFor(ids)) {
            sb.appendLine("""  <wpt lat="${s.lat}" lon="${s.lon}">""")
            sb.appendLine("    <time>${fmt.format(Date(s.timestamp))}</time>")
            sb.appendLine("    <name>${trackerShortName(s.trackerId)} ${s.rssi}dBm</name>")
            sb.appendLine("  </wpt>")
        }
        // A rotating clone has no sightings under its synthetic id, so its evidence is
        // the alert location itself. Include every alert point for the same reason.
        for (a in alerts) {
            val la = a.lat; val lo = a.lon
            if (la == null || lo == null) continue
            sb.appendLine("""  <wpt lat="$la" lon="$lo">""")
            sb.appendLine("    <time>${fmt.format(Date(a.timestamp))}</time>")
            sb.appendLine("    <name>ALERT ${trackerShortName(a.trackerId)}</name>")
            sb.appendLine("  </wpt>")
        }
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    companion object {
        const val RETENTION_DAYS = 14

        /** How long a catch-all Bluetooth device and its sightings are kept unless
         *  the user marked it or it has an alert in the history. Twice the
         *  co-movement window. */
        const val GENERIC_RETENTION_MS = 2 * CoMovementEvaluator.WINDOW_MS

        /** Synthetic tracker id for presence-engine (rotating clone) detections. */
        const val CLONE_TRACKER_ID = "clone:rotating"

        private val sharedMutex = Mutex()
    }
}

/** A clone alert stores the RSSI of the advert that confirmed it, not a peak over a
 *  window, so evidence shown to anyone else must not call it one. */
fun signalWord(a: AlertEntity): String =
    if (a.trackerId == TrackerRepository.CLONE_TRACKER_ID) "signal" else "peak"

private fun ecoLabel(name: String): String =
    runCatching { TrackerEcosystem.valueOf(name).display }.getOrDefault(name)

private fun trackerShortName(stableId: String): String = when (stableId.substringBefore(':')) {
    "apple" -> "AppleFindMy"
    "fmdn" -> "GoogleFMD"
    "samsung" -> "SmartTag"
    "tile" -> "Tile"
    "dult" -> "DULT"
    "clone" -> "RotatingClone"
    "ble" -> "BluetoothDevice"
    else -> "Tracker"
}
