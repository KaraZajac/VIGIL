package org.soulstone.vigil.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.soulstone.vigil.MainActivity
import org.soulstone.vigil.R
import org.soulstone.vigil.data.TrackerRepository
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.data.db.VigilDatabase
import org.soulstone.vigil.data.location.LocationProvider
import org.soulstone.vigil.data.settings.Settings
import org.soulstone.vigil.detect.PresenceEngine
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.TrackerObservation
import org.soulstone.vigil.scan.BleTrackerScanner

/**
 * Foreground service that owns the BLE scanner, the location provider, and the
 * temporal repository. Every parsed observation is geotagged and handed to the
 * repository, which persists it and re-evaluates co-movement; a fresh ALERTING
 * verdict raises a high-priority notification and vibrates.
 *
 * Everything is on-device. START_NOT_STICKY — the user explicitly starts/stops.
 */
class ScanService : LifecycleService() {

    companion object {
        private const val TAG = "ScanService"
        private const val CHANNEL_ID = "vigil_watch"
        private const val NOTIFICATION_ID = 0x5161  // "VIGIL"
        private const val PRUNE_INTERVAL_MS = 6 * 3_600_000L
        private const val CLONE_COOLDOWN_MS = 2 * 3_600_000L
        private const val MIN_RECORD_INTERVAL_MS = 15_000L  // per-device DB throttle
        private const val MAX_THROTTLE_ENTRIES = 4096
        private const val PLACE_TICK_MS = 5 * 60_000L       // dwell accrual for the baseline
        private const val LOCATION_GRACE_MS = 30_000L       // ride out brief "no fix" blips
        private const val ALERT_CHANNEL_ID = "vigil_alerts"
        private const val ALERT_NOTIF_ID = 0x5162
        private const val CLONE_NOTIF_ID = 0x5163

        const val ACTION_START = "org.soulstone.vigil.action.START"
        const val ACTION_STOP = "org.soulstone.vigil.action.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /**
         * Non-null while a watching session is live but NOT actually listening —
         * Bluetooth switched off, permission revoked, or the controller killed the
         * scan. The UI must show this instead of "you're clear", because otherwise
         * the app reassures someone it has stopped protecting.
         */
        private val _scanFault = MutableStateFlow<String?>(null)
        val scanFault: StateFlow<String?> = _scanFault.asStateFlow()

        /**
         * Non-null while listening works but no location fix is available. Every
         * alert needs places or movement, so without a fix nothing can alert, and the
         * UI must not read "all quiet" in that state either.
         */
        private val _locationFault = MutableStateFlow<String?>(null)
        val locationFault: StateFlow<String?> = _locationFault.asStateFlow()

        // Live RSSI stream for the "find it" hot/cold screen. The UI sets a target
        // stableId; every matching advert (not the throttled DB path) updates this.
        @Volatile private var finderTarget: String? = null
        private val _finderRssi = MutableStateFlow<Int?>(null)
        val finderRssi: StateFlow<Int?> = _finderRssi.asStateFlow()
        // When the target was last heard (elapsedRealtime). A StateFlow of RSSI alone
        // can't show silence: the finder kept "Very close" up indefinitely after the
        // tag went quiet, rotated its ID, or scanning stopped.
        private val _finderHeardAt = MutableStateFlow(0L)
        val finderHeardAt: StateFlow<Long> = _finderHeardAt.asStateFlow()
        fun setFinderTarget(id: String?) {
            finderTarget = id
            _finderRssi.value = null
            _finderHeardAt.value = 0L
        }

        fun start(context: Context) {
            val intent = Intent(context, ScanService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScanService::class.java).apply { action = ACTION_STOP })
        }
    }

    private lateinit var settings: Settings
    private lateinit var repo: TrackerRepository
    private lateinit var location: LocationProvider
    private lateinit var scanner: BleTrackerScanner
    private val presence = PresenceEngine()
    private val lastRecordedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var lastCloneAlertMs = 0L
    private var pruneJob: Job? = null
    private var placeJob: Job? = null
    private var locationJob: Job? = null
    private var btReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings.get(this)
        // The durable allowlist has to be wired here too: this is the repository that
        // records sightings, so without it approvals were never restored after a
        // "Clear all" or a destructive migration.
        repo = TrackerRepository(VigilDatabase.get(this), approvedStore = settings)
        location = LocationProvider(this)
        scanner = BleTrackerScanner(this, onObservation = ::onObservation, onFailure = ::onScanFailure)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_STOP -> { end(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (_running.value) return
        presence.reset()
        startInForeground()
        registerBluetoothReceiver()
        location.start()  // best-effort; scanning still runs without a fix (just no co-movement)
        if (!scanner.start()) {
            Log.w(TAG, "scanner failed to start (permission/adapter) — stopping")
            end(); stopSelf(); return
        }
        _running.value = true
        _scanFault.value = null
        locationJob?.cancel()
        locationJob = lifecycleScope.launch {
            location.available.collectLatest { ok ->
                if (ok) {
                    setLocationFault(null)
                } else {
                    // The fused provider can report "unavailable" briefly while it
                    // reacquires; only surface it once it has persisted.
                    delay(LOCATION_GRACE_MS)
                    setLocationFault("No location fix, so VIGIL can't tell what moves with you. Check that Location is on.")
                }
            }
        }
        pruneJob?.cancel()
        pruneJob = lifecycleScope.launch {
            // The clone cooldown lives in memory, so a service restart used to re-arm
            // it instantly and could re-notify about the same rotating presence.
            // Seed it from what was actually persisted.
            runCatching { repo.lastCloneAlertAt() }.getOrNull()?.let { lastCloneAlertMs = it }
            while (true) {
                // Prune first (frequent restarts previously meant the 6h-delayed
                // prune rarely ran, so stale rotated identities accumulated).
                runCatching { repo.prune() }.onFailure { Log.w(TAG, "prune failed: ${it.message}") }
                delay(PRUNE_INTERVAL_MS)
            }
        }
        placeJob?.cancel()
        placeJob = lifecycleScope.launch {
            // Anchor places must accrue from time actually spent somewhere, not from
            // how many trackers happen to advertise there. Previously dwell was only
            // noted when a sighting arrived, so a tracker-free home never became an
            // anchor and household tags were never auto-trusted.
            while (true) {
                currentFix()?.let { fix ->
                    runCatching { repo.notePlace(fix.latitude, fix.longitude) }
                        .onFailure { Log.w(TAG, "notePlace failed: ${it.message}") }
                }
                delay(PLACE_TICK_MS)
            }
        }
    }

    /** The controller killed the scan. Keep the session, but stop claiming cover. */
    private fun onScanFailure(errorCode: Int) {
        if (!_running.value) return
        Log.e(TAG, "scan failed ($errorCode) — reporting fault")
        setFault(scanner.unavailableReason() ?: "Bluetooth scanning stopped unexpectedly")
    }

    private fun setFault(reason: String?) {
        if (_scanFault.value == reason) return
        _scanFault.value = reason
        refreshOngoingNotification()
    }

    private fun setLocationFault(reason: String?) {
        if (_locationFault.value == reason) return
        _locationFault.value = reason
        refreshOngoingNotification()
    }

    /** The ongoing notification is the only surface visible with the app closed, so
     *  it has to carry the bad news too. A dead scan outranks a missing fix. */
    private fun refreshOngoingNotification() {
        if (!_running.value) return
        val n = buildNotification(
            title = getString(R.string.app_name),
            text = _scanFault.value ?: _locationFault.value ?: getString(R.string.notification_text),
            high = false
        )
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n) }
    }

    /** The latest fix, but only while the provider says it is current. A stale fix
     *  would pin every new sighting to wherever the phone last had location. */
    private fun currentFix() = location.location.value?.takeIf { location.available.value }

    /** Re-arm the scan after Bluetooth comes back, or record why it still can't run. */
    private fun tryResumeScan() {
        if (!_running.value) return
        if (scanner.isScanning) { setFault(null); return }
        if (scanner.start()) setFault(null)
        else setFault(scanner.unavailableReason() ?: "Bluetooth scanning is unavailable")
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    // Android silently drops the scan here without calling
                    // onScanFailed, so this receiver is the only way to notice.
                    scanner.stop()
                    setFault("Bluetooth is turned off — not watching")
                }
                BluetoothAdapter.STATE_ON -> tryResumeScan()
            }
        }
    }

    private fun registerBluetoothReceiver() {
        if (btReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            btReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        btReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!btReceiverRegistered) return
        runCatching { unregisterReceiver(btReceiver) }
        btReceiverRegistered = false
    }

    private fun onObservation(obs: TrackerObservation) {
        val fix = currentFix()

        // Live RSSI for the "find it" screen — every advert, ahead of any throttle.
        if (obs.stableId == finderTarget) {
            _finderRssi.value = obs.rssi
            _finderHeardAt.value = SystemClock.elapsedRealtime()
        }

        // Rotation-clone presence engine (problem #1) — fed synchronously so its
        // streaming state stays ordered. Only a CONFIRMED verdict raises a user
        // alert; softer tiers stay silent until validated on real captures.
        if (obs.separated == SeparatedState.SEPARATED) {
            val v = presence.onSighting(obs.stableId, obs.rssi, obs.timestampMs, fix?.latitude, fix?.longitude)
            if (v.tier == PresenceEngine.Tier.CONFIRMED &&
                obs.timestampMs - lastCloneAlertMs > CLONE_COOLDOWN_MS
            ) {
                lastCloneAlertMs = obs.timestampMs
                raiseCloneAlert()
                // Persist it too. A notification alone left the headline detection
                // out of the alert history AND out of the evidence export, so
                // dismissing the notification destroyed the only record of it.
                lifecycleScope.launch {
                    runCatching {
                        repo.recordCloneAlert(
                            cells = v.distinctCells,
                            peakRssi = obs.rssi,
                            lat = fix?.latitude,
                            lon = fix?.longitude,
                            now = obs.timestampMs
                        )
                    }.onFailure { Log.w(TAG, "clone alert not persisted: ${it.message}") }
                }
            }
        }

        // Temporal co-movement (identity path). CALLBACK_TYPE_ALL_MATCHES fires on
        // every advert (many/sec), so throttle the persisted path per device: the
        // presence engine above still sees every advert, but the DB records at most
        // one sighting per device per MIN_RECORD_INTERVAL_MS. Keeps the count
        // meaningful and avoids hammering the database.
        val last = lastRecordedAt[obs.stableId]
        if (last != null && obs.timestampMs - last < MIN_RECORD_INTERVAL_MS) return
        lastRecordedAt[obs.stableId] = obs.timestampMs
        if (lastRecordedAt.size > MAX_THROTTLE_ENTRIES) {
            // Evict only entries whose throttle has already lapsed. Clearing the whole
            // map made every known device instantly re-recordable, producing a burst of
            // database writes exactly when the device population was largest.
            val expired = obs.timestampMs - MIN_RECORD_INTERVAL_MS
            lastRecordedAt.entries.removeIf { it.value < expired }
        }

        lifecycleScope.launch {
            val result = runCatching {
                repo.record(obs, fix?.latitude, fix?.longitude, settings.sensitivity.value)
            }.getOrNull() ?: return@launch
            if (result.newlyAlerting) raiseAlert(result.tracker)
        }
    }

    private fun end() {
        // No early-return guard: all stops below are idempotent, and begin() calls
        // end() on a failed scanner start (when _running was never set) to make sure
        // the location updates and foreground notification are released, not leaked.
        _running.value = false
        _scanFault.value = null
        _locationFault.value = null
        locationJob?.cancel(); locationJob = null
        unregisterBluetoothReceiver()
        scanner.stop()
        location.stop()
        lastRecordedAt.clear()
        pruneJob?.cancel(); pruneJob = null
        placeJob?.cancel(); placeJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        end()
        super.onDestroy()
    }

    private fun startInForeground() {
        val n = buildNotification(
            title = getString(R.string.app_name),
            text = getString(R.string.notification_text),
            high = false
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, n, type)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun raiseAlert(tracker: TrackerEntity) {
        val n = buildNotification(
            title = "Tracker following you",
            text = "${tracker.label} has been moving with you",
            high = true, channelId = ALERT_CHANNEL_ID, ongoing = false
        )
        getSystemService(NotificationManager::class.java)?.notify(ALERT_NOTIF_ID, n)
        vibrate()
        Log.w(TAG, "ALERT: ${tracker.stableId} (${tracker.ecosystem})")
    }

    private fun raiseCloneAlert() {
        val n = buildNotification(
            title = "Possible hidden tracker",
            text = "A rotating-ID tracker appears to be moving with you",
            high = true, channelId = ALERT_CHANNEL_ID, ongoing = false
        )
        getSystemService(NotificationManager::class.java)?.notify(CLONE_NOTIF_ID, n)
        vibrate()
        Log.w(TAG, "CLONE ALERT: rotating-id presence confirmed")
    }

    private fun vibrate() {
        val v = currentVibrator() ?: return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 400), -1)
        runCatching { v.vibrate(effect) }
    }

    private fun currentVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun buildNotification(
        title: String,
        text: String,
        high: Boolean,
        channelId: String = CHANNEL_ID,
        ongoing: Boolean = true
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(pi)
            .setCategory(if (high) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
            )
        }
        // Alerts get their OWN high-importance channel so a real "following you"
        // warning actually makes noise + a heads-up banner. On Android 8+ channel
        // importance overrides per-notification priority, so the ongoing LOW status
        // channel could never sound an alert.
        if (mgr.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Tracker alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "A tracker appears to be following you"
                    enableVibration(true)
                }
            )
        }
    }
}
