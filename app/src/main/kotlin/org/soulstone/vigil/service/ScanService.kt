package org.soulstone.vigil.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        const val ACTION_START = "org.soulstone.vigil.action.START"
        const val ACTION_STOP = "org.soulstone.vigil.action.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

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

    override fun onCreate() {
        super.onCreate()
        settings = Settings.get(this)
        repo = TrackerRepository(VigilDatabase.get(this))
        location = LocationProvider(this)
        scanner = BleTrackerScanner(this, onObservation = ::onObservation)
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
        location.start()  // best-effort; scanning still runs without a fix (just no co-movement)
        if (!scanner.start()) {
            Log.w(TAG, "scanner failed to start (permission/adapter) — stopping")
            end(); stopSelf(); return
        }
        _running.value = true
        pruneJob?.cancel()
        pruneJob = lifecycleScope.launch {
            while (true) {
                delay(PRUNE_INTERVAL_MS)
                runCatching { repo.prune() }.onFailure { Log.w(TAG, "prune failed: ${it.message}") }
            }
        }
    }

    private fun onObservation(obs: TrackerObservation) {
        val fix = location.location.value

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
        if (lastRecordedAt.size > 4096) lastRecordedAt.clear()  // guard vs MAC-rotation growth

        lifecycleScope.launch {
            val result = runCatching {
                repo.record(obs, fix?.latitude, fix?.longitude, settings.sensitivity.value)
            }.getOrNull() ?: return@launch
            if (result.newlyAlerting) raiseAlert(result.tracker)
        }
    }

    private fun end() {
        if (!_running.value) return
        _running.value = false
        scanner.stop()
        location.stop()
        lastRecordedAt.clear()
        pruneJob?.cancel(); pruneJob = null
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
            high = true
        )
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
        vibrate()
        Log.w(TAG, "ALERT: ${tracker.stableId} (${tracker.ecosystem})")
    }

    private fun raiseCloneAlert() {
        val n = buildNotification(
            title = "Possible hidden tracker",
            text = "A rotating-ID tracker appears to be moving with you",
            high = true
        )
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID + 1, n)
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

    private fun buildNotification(title: String, text: String, high: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(!high)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
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
}
