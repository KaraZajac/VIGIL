package org.soulstone.vigil.scan

import android.bluetooth.le.ScanFilter
import android.content.Context
import org.soulstone.vigil.model.TrackerObservation

/**
 * The all-devices sweep: a short, periodic listen for EVERY Bluetooth LE advert in
 * range, so devices that are not a known tracker format (headphones, watches, car
 * kits, beacons, trackers VIGIL has no parser for) can be listed and checked for
 * moving with the user.
 *
 * It runs in bursts, not continuously. Hearing everything means the radio wakes the
 * phone for every advert in range instead of only for tracker matches, which is
 * the expensive thing the tracker scan's hardware filters exist to avoid. A device
 * that is travelling with you is still there at the next burst, so sampling is
 * enough for co-movement; it is NOT enough for the rotation-clone detector, which
 * is why sweep results never reach [org.soulstone.vigil.detect.PresenceEngine].
 *
 * The filter list is [match-everything, Find My]. The second entry does nothing for
 * matching. It is there because Android treats a scan whose filter list is empty
 * as "unfiltered" and suspends it whenever the screen is off; a non-empty list is
 * exempt (ScanManager.requiresScreenOn, read in the Android 13 to 16 sources).
 * Some phone makers add their own rules on top, which VIGIL cannot see or test.
 *
 * Results are collected per burst and handed back by [end], one entry per address
 * carrying the STRONGEST signal heard in the burst.
 */
class NearbyDeviceScanner(
    context: Context,
    private val onActivity: () -> Unit = {},
    onFailure: (Int) -> Unit = {}
) {
    private val filters: List<ScanFilter> = allDeviceFilters()

    private val heard = HashMap<String, TrackerObservation>()

    private val client = LeScanClient(
        context, "NearbyDeviceScanner", filters,
        onResult = { r ->
            // Results already queued when a burst is stopped still arrive; they
            // belong to no burst.
            if (isSweeping) {
                NearbyDeviceParser.parse(r)?.let(::note)
                onActivity()
            }
        },
        onFailure = { code ->
            heard.clear()
            onFailure(code)
        }
    )

    val isSweeping: Boolean get() = client.isScanning

    /** elapsedRealtime the current burst started at; 0 when idle. */
    val startedAtElapsed: Long get() = client.startedAtElapsed

    fun unavailableReason(): String? = client.unavailableReason()

    /** Start a burst. False if the scan could not be registered. */
    fun begin(): Boolean {
        if (client.isScanning) return true
        heard.clear()
        return client.start()
    }

    /** Stop the burst and return what it heard, strongest first. */
    fun end(): List<TrackerObservation> {
        client.stop()
        val out = heard.values.sortedByDescending { it.rssi }
        heard.clear()
        return out
    }

    /** Stop without keeping anything (Bluetooth went away, service ending). */
    fun cancel() {
        client.stop()
        heard.clear()
    }

    private fun note(obs: TrackerObservation) {
        val prev = heard[obs.stableId]
        if (prev != null) {
            heard[obs.stableId] = merge(prev, obs)
            return
        }
        if (heard.size >= MAX_PER_BURST) {
            // Full: keep the strongest. Whatever is close to the user must not be
            // pushed out by a crowd (or a deliberate flood) of distant addresses.
            val weakest = heard.values.minByOrNull { it.rssi } ?: return
            if (obs.rssi <= weakest.rssi) return
            heard.remove(weakest.stableId)
        }
        heard[obs.stableId] = obs
    }

    companion object {
        /** Upper bound on distinct addresses kept from one burst. */
        const val MAX_PER_BURST = 256

        /** A filter list that matches every advert yet is not "unfiltered" (see above). */
        fun allDeviceFilters(): List<ScanFilter> = listOf(
            ScanFilter.Builder().build(),
            ScanFilter.Builder().setManufacturerData(
                TrackerSignatures.APPLE_COMPANY_ID,
                byteArrayOf(TrackerSignatures.APPLE_TYPE_FINDMY.toByte()),
                byteArrayOf(0xFF.toByte())
            ).build()
        )

        /**
         * Two adverts from one address in one burst: keep the strongest signal, and
         * the best description either carried. A device's name often arrives only
         * in its scan response, i.e. in a different result from its maker id.
         */
        fun merge(a: TrackerObservation, b: TrackerObservation): TrackerObservation {
            val name = a.name.ifEmpty { b.name }
            val vendor = a.vendor.ifEmpty { b.vendor }
            val strongest = if (b.rssi > a.rssi) b else a
            val described = when {
                a.name.isNotEmpty() -> a
                b.name.isNotEmpty() -> b
                a.label != NearbyDeviceParser.UNNAMED -> a
                else -> b
            }
            return strongest.copy(
                name = name,
                vendor = vendor,
                label = described.label,
                addressKind = if (a.addressKind.code != 0) a.addressKind else b.addressKind,
                timestampMs = maxOf(a.timestampMs, b.timestampMs)
            )
        }
    }
}
