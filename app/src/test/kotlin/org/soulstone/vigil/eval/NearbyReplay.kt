package org.soulstone.vigil.eval

import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.detect.CoMovementEvaluator
import org.soulstone.vigil.detect.NearbyDeviceEvaluator
import org.soulstone.vigil.detect.NearbyThrottle
import org.soulstone.vigil.detect.Places
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.model.TrackerObservation
import org.soulstone.vigil.service.ScanService

/**
 * Replays a day of "any other Bluetooth device" sightings the way the app handles
 * them: one sweep every [ScanService.SWEEP_PERIOD_MS], the devices heard go through
 * the real [NearbyThrottle], what it lets through is stored and re-judged by the
 * real [NearbyDeviceEvaluator] over that device's trailing 24 h, with the same
 * re-alert cooldown [org.soulstone.vigil.data.TrackerRepository] applies.
 *
 * Not replayed: the learned baseline and the "It's mine" list (both only ever
 * silence a device), the re-judging of a flagged device that was heard but not
 * logged (it only ever lowers the stored state, a few minutes before the next
 * logged sighting would), and the radio. A device is "heard" here whenever the scenario
 * says so, at the signal it says. Real sweeps miss adverts, and real signal
 * strength swings by 10 dB and more with a hand or a bag in the way.
 */
object NearbyReplay {

    const val MINUTE = 60_000L
    val SWEEP_EVERY_MIN: Long = ScanService.SWEEP_PERIOD_MS / MINUTE

    /** Where the phone thinks it is; null when it has no fix. */
    data class Pos(val lat: Double, val lon: Double, val accuracyM: Float = 15f)

    fun interface Route { fun at(tMin: Long): Pos? }

    /** One radio. Returns the address it is using and its signal, or null if unheard. */
    fun interface Source { fun heard(tMin: Long, at: Pos?): Pair<String, Int>? }

    data class Outcome(
        /** Minute of the first alert per device id. */
        val firstAlertMin: Map<String, Long>,
        val alerts: Int,
        /** Highest state each device id ever reached. */
        val peak: Map<String, RiskState>,
        val idsRecorded: Int
    ) {
        val alerted: Boolean get() = alerts > 0
        fun firstAlert(id: String): Long? = firstAlertMin[id]
    }

    /**
     * @param gps what the phone reports for a true position; the identity by
     *   default. Sources are always heard from the TRUE position.
     */
    fun run(
        route: Route,
        sources: List<Source>,
        minutes: Long,
        sensitivity: Sensitivity,
        offsetMin: Long = 0,
        gps: (Long, Pos?) -> Pos? = { _, p -> p }
    ): Outcome {
        val thresholds = NearbyDeviceEvaluator.thresholdsFor(sensitivity)
        val throttle = NearbyThrottle()
        val store = HashMap<String, ArrayList<SightingEntity>>()
        val alerting = HashSet<String>()
        val lastAlertMs = HashMap<String, Long>()
        val firstAlert = LinkedHashMap<String, Long>()
        val peak = HashMap<String, RiskState>()
        var alerts = 0

        var t = offsetMin
        while (t <= minutes) {
            val now = t * MINUTE
            val truth = route.at(t)
            val pos = gps(t, truth)
            val heard = sources.mapNotNull { it.heard(t, truth) }.map { (id, rssi) ->
                TrackerObservation(
                    stableId = TrackerEcosystem.GENERIC_ID_PREFIX + id,
                    ecosystem = TrackerEcosystem.OTHER_BLE,
                    mac = id, rssi = rssi, separated = SeparatedState.UNKNOWN,
                    label = id, timestampMs = now
                )
            }
            for (obs in throttle.select(heard, pos?.lat, pos?.lon, now)) {
                val bucket = store.getOrPut(obs.stableId) { ArrayList() }
                bucket += SightingEntity(
                    trackerId = obs.stableId, timestamp = now, rssi = obs.rssi, separated = false,
                    lat = pos?.lat, lon = pos?.lon, accuracy = pos?.accuracyM
                )
                val since = now - CoMovementEvaluator.WINDOW_MS
                val state = NearbyDeviceEvaluator
                    .evaluate(bucket.filter { it.timestamp >= since }, thresholds).riskState
                val before = peak[obs.stableId]
                if (before == null || state.ordinal > before.ordinal) peak[obs.stableId] = state

                val cooldownOk = now - (lastAlertMs[obs.stableId] ?: 0L) > CoMovementEvaluator.ALERT_COOLDOWN_MS
                if (state == RiskState.ALERTING && obs.stableId !in alerting && cooldownOk) {
                    lastAlertMs[obs.stableId] = now
                    alerts++
                    firstAlert.putIfAbsent(obs.stableId, t)
                }
                if (state == RiskState.ALERTING) alerting += obs.stableId else alerting -= obs.stableId
            }
            t += SWEEP_EVERY_MIN
        }
        return Outcome(firstAlert, alerts, peak, store.size)
    }

    // --- scenario building blocks -----------------------------------------

    /** A leg of travel in a straight line from [from] to [to] between two minutes. */
    data class Leg(val startMin: Long, val endMin: Long, val from: Pos, val to: Pos)

    /** Stays put between legs, at wherever the last one ended. */
    fun route(start: Pos, legs: List<Leg>) = Route { t ->
        var here = start
        for (l in legs) {
            if (t < l.startMin) return@Route here
            if (t <= l.endMin) {
                val f = (t - l.startMin).toDouble() / (l.endMin - l.startMin).coerceAtLeast(1)
                return@Route Pos(
                    l.from.lat + (l.to.lat - l.from.lat) * f,
                    l.from.lon + (l.to.lon - l.from.lon) * f
                )
            }
            here = l.to
        }
        here
    }

    /** The same legs again on each of [days] days. */
    fun daily(legs: List<Leg>, days: Int): List<Leg> = (0 until days).flatMap { d ->
        legs.map { it.copy(startMin = it.startMin + d * 1440L, endMin = it.endMin + d * 1440L) }
    }

    /** A point [northKm] north and [eastKm] east of the scenario origin. */
    fun at(northKm: Double, eastKm: Double = 0.0) = Pos(40.70 + northKm / 111.32, -74.00 + eastKm / 84.4)

    /** A device that is with the user, at one address, whenever [present] says. */
    fun carried(id: String, rssi: Int, present: (Long) -> Boolean) =
        Source { t, _ -> if (present(t)) id to rssi else null }

    /** A device that stays at [where] and is heard within [rangeM] of it. */
    fun fixed(id: String, where: Pos, rssi: Int, rangeM: Double = 30.0) = Source { _, at ->
        if (at != null && Places.metres(where.lat, where.lon, at.lat, at.lon) <= rangeM) id to rssi
        else null
    }

    /** A device that is always with the user but takes a new address every [everyMin]. */
    fun rotating(prefix: String, rssi: Int, everyMin: Long = 15) =
        Source { t, _ -> "$prefix-${t / everyMin}" to rssi }
}
