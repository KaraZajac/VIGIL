package org.soulstone.vigil.scan

import android.bluetooth.le.ScanFilter
import android.content.Context
import org.soulstone.vigil.model.TrackerObservation

/**
 * BLE scanner for tracker advertisements. Unlike OVERWATCH's unfiltered scan, this
 * uses hardware [ScanFilter]s for the tracker signatures — the controller wakes us
 * only on a match, which is what makes screen-off/background scanning deliverable
 * and battery-tolerable (research brief §5).
 *
 * What the filters do not buy is permanence. From Android 14 on, a filtered scan
 * that has run for a few minutes (five on the Android 14 emulator image, ten on
 * the Android 16 one) is moved to low power, where the radio listens a tenth of
 * the time; Android 16's source halves that again once the screen is off. Nothing
 * reports the demotion to the app. So the service calls [restart] every four
 * minutes for as long as it is watching, which keeps the scan at full rate. The
 * demotion was seen in the emulators' logs; how much it loses, and what avoiding
 * it costs in battery, can only be measured on a phone and has not been.
 *
 * Each match is parsed by [TrackerParser]; anything that parses is handed to
 * [onObservation]. All correlation/temporal logic lives downstream.
 */
class BleTrackerScanner(
    context: Context,
    private val onObservation: (TrackerObservation) -> Unit,
    /**
     * Called when the controller kills the scan (see ScanCallback.onScanFailed).
     * This MUST reach the service: a dead scan used to be recorded only in this
     * object's private running flag, so the service kept reporting that it was
     * watching and the UI kept telling the user they were clear while nothing was
     * being listened for.
     */
    onFailure: (Int) -> Unit = {}
) {
    private val filters: List<ScanFilter> = buildList {
        // Apple Find My — match ONLY the offline-finding message type (0x12), not
        // every Apple device (iPhones/Watches/AirPods all advertise company 0x004C).
        // Data+mask apply from the first manufacturer-data byte, which is the type.
        add(
            ScanFilter.Builder().setManufacturerData(
                TrackerSignatures.APPLE_COMPANY_ID,
                byteArrayOf(TrackerSignatures.APPLE_TYPE_FINDMY.toByte()),
                byteArrayOf(0xFF.toByte())
            ).build()
        )
        // FMDN / Samsung / Tile / DULT — match by SERVICE DATA presence, not the
        // service-UUID list. These carry their payload in service data and may not
        // advertise the plain UUID-list entry, so setServiceUuid can miss them
        // entirely (AirGuard uses service-data presence filters for exactly this).
        for (uuid in TrackerSignatures.trackerServiceUuids) {
            add(ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build())
        }
    }

    private val client = LeScanClient(
        context, "BleTrackerScanner", filters,
        onResult = { r -> TrackerParser.parse(r)?.let(onObservation) },
        onFailure = onFailure
    )

    /** True only while the controller is actually delivering results to us. */
    val isScanning: Boolean get() = client.isScanning

    /** elapsedRealtime of the last (re)start, 0 when idle — drives the periodic restart. */
    val startedAtElapsed: Long get() = client.startedAtElapsed

    /** Why [start] would fail right now, or null if it should succeed. */
    fun unavailableReason(): String? = client.unavailableReason()

    fun start(): Boolean = client.start()

    fun stop() = client.stop()

    /** Re-register the scan so Android's long-running-scan timeout never demotes it. */
    fun restart(): Boolean = client.restart()
}
