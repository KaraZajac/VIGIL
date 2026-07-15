package org.soulstone.vigil.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.soulstone.vigil.model.TrackerObservation

/**
 * BLE scanner for tracker advertisements. Unlike OVERWATCH's unfiltered scan, this
 * uses hardware [ScanFilter]s for the tracker signatures — the controller wakes us
 * only on a match, which is what makes screen-off/background scanning deliverable
 * and battery-tolerable (research brief §5).
 *
 * Each match is parsed by [TrackerParser]; anything that parses is handed to
 * [onObservation]. All correlation/temporal logic lives downstream.
 */
class BleTrackerScanner(
    private val context: Context,
    private val onObservation: (TrackerObservation) -> Unit
) {
    companion object {
        private const val TAG = "BleTrackerScanner"
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var leScanner: BluetoothLeScanner? = null
    private var running = false

    private val settings: ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

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
        // FMDN / Samsung / Tile / DULT — match their service UUIDs.
        for (uuid in TrackerSignatures.trackerServiceUuids) {
            add(ScanFilter.Builder().setServiceUuid(uuid).build())
        }
    }

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasScanPermission()) {
            Log.w(TAG, "BLE scan permission missing")
            return false
        }
        val a = adapter ?: return false
        if (!a.isEnabled) {
            Log.w(TAG, "Bluetooth disabled")
            return false
        }
        leScanner = a.bluetoothLeScanner ?: return false
        return try {
            leScanner?.startScan(filters, settings, callback)
            running = true
            Log.i(TAG, "Tracker scan started (${filters.size} filters)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting scan", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        try {
            leScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping scan", e)
        }
        running = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            TrackerParser.parse(result)?.let(onObservation)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { r -> TrackerParser.parse(r)?.let(onObservation) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            running = false
        }
    }
}
