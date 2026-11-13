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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * One BLE scan registration: the start/stop/permission plumbing shared by the
 * tracker scan and the all-devices sweep.
 *
 * Every [start] registers a FRESH [ScanCallback]. Android counts how long a scan
 * registration has been running and, on Android 14+, quietly demotes a
 * long-running one from LOW_LATENCY to LOW_POWER (10% duty) once it passes a
 * timeout (see [ScanService] for the measurements). A registration that is stopped
 * and started again before that timeout is never demoted, which is what
 * [restart] is for.
 */
internal class LeScanClient(
    private val context: Context,
    private val tag: String,
    private val filters: List<ScanFilter>,
    private val onResult: (ScanResult) -> Unit,
    private val onFailure: (Int) -> Unit
) {
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var leScanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    private val settings: ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    /** True only while a registration of ours is live. */
    val isScanning: Boolean get() = callback != null

    /** [SystemClock.elapsedRealtime] of the last successful [start]; 0 when idle. */
    var startedAtElapsed: Long = 0L
        private set

    /** Why [start] would fail right now, or null if it should succeed. */
    fun unavailableReason(): String? {
        if (!hasScanPermission()) return "Bluetooth scan permission is not granted"
        val a = adapter ?: return "This device has no Bluetooth adapter"
        if (!a.isEnabled) return "Bluetooth is turned off"
        return null
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
        if (callback != null) return true
        if (!hasScanPermission()) {
            Log.w(tag, "BLE scan permission missing")
            return false
        }
        val a = adapter ?: return false
        if (!a.isEnabled) {
            Log.w(tag, "Bluetooth disabled")
            return false
        }
        val scanner = a.bluetoothLeScanner ?: return false
        val cb = newCallback()
        return try {
            scanner.startScan(filters, settings, cb)
            leScanner = scanner
            callback = cb
            startedAtElapsed = SystemClock.elapsedRealtime()
            noteStart(startedAtElapsed)
            Log.i(tag, "scan started (${filters.size} filters)")
            true
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException starting scan", e)
            false
        } catch (e: IllegalStateException) {
            // The adapter can switch off between the isEnabled check and startScan.
            Log.w(tag, "startScan failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        callback = null
        startedAtElapsed = 0L
        try {
            leScanner?.stopScan(cb)
        } catch (e: Exception) {
            // Broadened deliberately: this is also called from the Bluetooth-state
            // receiver, and the adapter is typically already off by then, which makes
            // stopScan throw IllegalStateException. Teardown must not crash the app.
            Log.w(tag, "stopScan failed (adapter likely already off): ${e.message}")
        }
    }

    /** Stop and immediately start again, so the registration's age resets. */
    fun restart(): Boolean {
        stop()
        return start()
    }

    companion object {
        /**
         * Android refuses a scan start once an app has made 5 within 30 s, and on
         * older versions it refuses silently: the call returns and nothing is ever
         * heard. Both scans in this app count against the one allowance, so they
         * share this ledger and keep their own total under it.
         */
        const val QUOTA_WINDOW_MS = 30_000L
        private val recentStarts = ArrayDeque<Long>()

        @Synchronized
        private fun noteStart(nowElapsed: Long) {
            recentStarts.addLast(nowElapsed)
            while (recentStarts.size > 8) recentStarts.removeFirst()
        }

        /** How many scan starts this app made in the last [QUOTA_WINDOW_MS]. */
        @Synchronized
        fun startsInWindow(nowElapsed: Long = SystemClock.elapsedRealtime()): Int =
            recentStarts.count { nowElapsed - it < QUOTA_WINDOW_MS }
    }

    private fun newCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(onResult)

        @SuppressLint("MissingPermission")
        override fun onScanFailed(errorCode: Int) {
            // A late failure from a registration we already replaced says nothing
            // about the live one.
            if (callback !== this) return
            Log.e(tag, "BLE scan failed: $errorCode")
            callback = null
            startedAtElapsed = 0L
            // A failure reported after the registration succeeded leaves it
            // registered. Every start here uses a new callback, so without this
            // each retry would leave one more behind.
            try {
                leScanner?.stopScan(this)
            } catch (e: Exception) {
                Log.w(tag, "stopScan after failure: ${e.message}")
            }
            onFailure(errorCode)
        }
    }
}
