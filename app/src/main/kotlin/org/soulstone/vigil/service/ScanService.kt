package org.soulstone.vigil.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.soulstone.vigil.detect.NearbyThrottle
import org.soulstone.vigil.detect.PresenceEngine
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.model.TrackerObservation
import org.soulstone.vigil.scan.BleTrackerScanner
import org.soulstone.vigil.scan.BluetoothCompanyIds
import org.soulstone.vigil.scan.LeScanClient
import org.soulstone.vigil.scan.NearbyDeviceScanner

/**
 * Foreground service that owns the BLE scanner, the location provider, and the
 * temporal repository. Every parsed observation is geotagged and handed to the
 * repository, which persists it and re-evaluates co-movement; a fresh ALERTING
 * verdict raises a high-priority notification and vibrates.
 *
 * Everything is on-device. START_NOT_STICKY — the user explicitly starts/stops.
 *
 * ## Timekeeping: [tick]
 *
 * Nothing periodic here runs on a `delay()` loop alone. A coroutine delay is
 * measured in uptime, which stands still while the phone sleeps, so a "5 minute"
 * loop could take hours with the screen off. And VIGIL holds no wake lock and sets
 * no alarms, on purpose. Instead [tick] compares deadlines against
 * [SystemClock.elapsedRealtime] (which does count sleep) and is called from
 * everything that already wakes the phone for this app: each location fix, each
 * tracker advert, each advert in an all-devices sweep, plus a 5 s loop that covers
 * the time the phone is awake anyway. Periodic work therefore happens on time
 * whenever the phone is awake, and as soon as it next wakes otherwise.
 *
 * ## Why the tracker scan is restarted every few minutes
 *
 * Android 14 and later demote a scan that has run "too long" from LOW_LATENCY to
 * LOW_POWER (listening 10% of the time) and never promote it back. Measured on the
 * stock emulator images: after 5 minutes on API 34 and 10 minutes on API 36
 * (logcat: "Moving filtered scan client to downgraded scan"; the scan then shows
 * as "Forced" in `dumpsys bluetooth_manager`). The limit applies to one scan
 * registration, so stopping and re-registering before it expires keeps full rate.
 * Android's default limit is 10 minutes and a device's configuration can override
 * it (the API 34 image's 5 minutes is presumably such an override), so a phone set
 * below [SCAN_RESTART_MS] would still demote the scan between restarts.
 * This costs battery compared with letting the demotion happen. It is what the
 * rotation-clone detector's timing assumes, and what "watching" should mean.
 * Like everything else here it happens on a [tick]: while the phone sleeps with
 * nothing to wake it, the restart waits for the next wake and the scan can spend
 * that time demoted. What demotion costs on a real phone's radio has not been
 * measured.
 *
 * ## Location off
 *
 * While the system Location switch is off, Android gives this app no scan results
 * at all: its scan permission is not declared "never for location", because it
 * geotags what it hears. That is shown as a watching fault, sweeps are not run
 * (they would "check" and hear nothing), and both scans are registered afresh when
 * Location returns. See [maintainLocationSwitch].
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
        private const val NEARBY_CHANNEL_ID = "vigil_nearby"
        private const val NEARBY_NOTIF_ID = 0x5164

        private const val TICK_MS = 5_000L
        // Re-register the tracker scan before Android's long-running-scan timeout
        // (5 min on the Android 14 image, the shortest measured) can demote it.
        private const val SCAN_RESTART_MS = 4 * 60_000L
        private const val SCAN_RETRY_MS = 30_000L
        // A scan started again after a failure is believed once it has lasted this long.
        private const val SCAN_SETTLE_MS = 5_000L
        // All-devices sweep: listen for SWEEP_MS every SWEEP_PERIOD_MS.
        const val SWEEP_MS = 12_000L
        const val SWEEP_PERIOD_MS = 2 * 60_000L
        private const val SWEEP_FIRST_DELAY_MS = 4_000L
        private const val SWEEP_ON_OPEN_GAP_MS = 20_000L
        // Android allows 5 scan starts per 30 s. The tracker scan may use 4 of
        // them, a sweep only starts while at most 2 are used, so a sweep can never
        // take the start the tracker scan needs.
        private const val MAX_STARTS_TRACKER = 4
        private const val MAX_STARTS_SWEEP = 2
        // A flagged device still counts as "with you" for the notification summary
        // if it was heard this recently.
        private const val NEARBY_WITH_YOU_MS = 30 * 60_000L

        private const val FAULT_STARTING =
            "Starting in a few seconds. Android limits how often an app may restart Bluetooth scanning."
        private const val FAULT_RESTARTING = "Bluetooth scanning is restarting."
        private const val FAULT_LOCATION_OFF =
            "Location is turned off, and while it is, Android gives VIGIL no Bluetooth scan results. " +
                "VIGIL is hearing nothing. Turn Location on."

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
        // A flow, so the service can react the moment the finder opens (see
        // maintainFinder) instead of at its next tick.
        private val finderTargetFlow = MutableStateFlow<String?>(null)
        private val finderTarget: String? get() = finderTargetFlow.value
        private val _finderRssi = MutableStateFlow<Int?>(null)
        val finderRssi: StateFlow<Int?> = _finderRssi.asStateFlow()
        // When the target was last heard (elapsedRealtime). A StateFlow of RSSI alone
        // can't show silence: the finder kept "Very close" up indefinitely after the
        // tag went quiet, rotated its ID, or scanning stopped.
        private val _finderHeardAt = MutableStateFlow(0L)
        val finderHeardAt: StateFlow<Long> = _finderHeardAt.asStateFlow()
        fun setFinderTarget(id: String?) {
            finderTargetFlow.value = id
            _finderRssi.value = null
            _finderHeardAt.value = 0L
        }

        /**
         * Wall-clock time the last all-devices sweep finished, 0 if none has yet
         * this session. The UI shows it so "nothing nearby" always comes with how
         * fresh that is.
         */
        private val _lastSweepAt = MutableStateFlow(0L)
        val lastSweepAt: StateFlow<Long> = _lastSweepAt.asStateFlow()

        /** Non-null while the all-devices sweep cannot run although it is switched on. */
        private val _sweepFault = MutableStateFlow<String?>(null)
        val sweepFault: StateFlow<String?> = _sweepFault.asStateFlow()

        @Volatile private var sweepRequested = false

        private val approvalChanged = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        /**
         * The user marked a device "mine" or took that back (call it after the
         * change is stored). A notification that still calls it a follower is now
         * wrong: a tracker's own alert is taken down, and the other-devices
         * notification, which summarises everything flagged, is rewritten.
         */
        fun noteApprovalChanged(context: Context, id: String, approved: Boolean) {
            if (approved && !id.startsWith(TrackerEcosystem.GENERIC_ID_PREFIX)) {
                runCatching {
                    context.getSystemService(NotificationManager::class.java)?.cancel(id, ALERT_NOTIF_ID)
                }
            }
            noteDevicesCleared(context)
        }

        /** The list of devices changed under the other-devices notification. */
        fun noteDevicesCleared(context: Context) {
            if (_running.value) {
                approvalChanged.tryEmit(Unit)
            } else {
                // Nobody is running to rewrite it, and what it says dates from
                // before watching stopped. The user is in the app looking at the
                // list; the alert history keeps the record.
                runCatching {
                    context.getSystemService(NotificationManager::class.java)?.cancel(NEARBY_NOTIF_ID)
                }
            }
        }

        /** The app was opened: sweep soon rather than waiting out the period. */
        fun requestSweep() { sweepRequested = true }

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
    private lateinit var sweeper: NearbyDeviceScanner
    private val nearbyThrottle = NearbyThrottle()
    private val presence = PresenceEngine()
    private val lastRecordedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var lastCloneAlertMs = 0L
    private var tickJob: Job? = null
    private var locationJob: Job? = null
    private var finderJob: Job? = null
    private var approvalJob: Job? = null
    // The finder's own listen, used only while it is open on a non-tracker device.
    private var finderClient: LeScanClient? = null
    private var finderMac: String? = null
    // Deadlines for tick(), all in SystemClock.elapsedRealtime(). Main thread only.
    private var nextPlaceAt = 0L
    private var nextPruneAt = 0L
    private var nextSweepAt = 0L
    private var nextScanRetryAt = 0L
    private var lastSweepEndedAt = 0L
    private var nextLocationCheckAt = 0L
    // When to believe a scan that was started again after a failure; 0 = not waiting.
    private var scanSettleAt = 0L
    private var btReceiverRegistered = false
    // Why the tracker scan is not giving cover, when it is not.
    private var scanProblem: String? = null
    // The system Location switch is off. Android then withholds every scan result
    // from an app like this one, so VIGIL is deaf however healthy the scan looks.
    private var locationOff = false
    // Location came back on: the scans have to be registered afresh (see
    // maintainLocationSwitch).
    private var rearmScans = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings.get(this)
        // The durable allowlist has to be wired here too: this is the repository that
        // records sightings, so without it approvals were never restored after a
        // "Clear all" or a destructive migration.
        repo = TrackerRepository(VigilDatabase.get(this), approvedStore = settings)
        location = LocationProvider(this, onFix = ::tick)
        scanner = BleTrackerScanner(this, onObservation = ::onObservation, onFailure = ::onScanFailure)
        sweeper = NearbyDeviceScanner(this, onActivity = ::tick, onFailure = ::onSweepFailure)
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
        // Android refuses a scan start once an app has made 5 within 30 s, on older
        // versions without saying so, and Start/Stop tapped a few times gets there.
        // With the allowance used up, come up without the scan and say so; tick()
        // starts it as soon as a start is free.
        val now = SystemClock.elapsedRealtime()
        val mustWait = LeScanClient.startsInWindow(now) >= MAX_STARTS_TRACKER
        if (scanner.unavailableReason() != null || (!mustWait && !scanner.start())) {
            Log.w(TAG, "scanner failed to start (permission/adapter) — stopping")
            end(); stopSelf(); return
        }
        if (mustWait) Log.w(TAG, "out of scan starts for now; the scan begins shortly")
        _running.value = true
        scanProblem = if (mustWait) FAULT_STARTING else null
        scanSettleAt = 0L
        rearmScans = false
        locationOff = !location.systemLocationOn()
        nextLocationCheckAt = now + TICK_MS
        _scanFault.value = null
        publishFault()
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
        // The clone cooldown lives in memory, so a service restart used to re-arm
        // it instantly and could re-notify about the same rotating presence. Seed
        // it from what was actually persisted.
        lifecycleScope.launch {
            runCatching { repo.lastCloneAlertAt() }.getOrNull()?.let { lastCloneAlertMs = it }
        }
        // Maker names for the all-devices list; a file read, so off the main thread.
        lifecycleScope.launch(Dispatchers.IO) { BluetoothCompanyIds.ensureLoaded(applicationContext) }

        // Prune on the first tick (frequent restarts previously meant a delayed prune
        // rarely ran, so stale rotated identities piled up), and note the place as
        // soon as there is a fix.
        nextPruneAt = now
        nextPlaceAt = now
        nextSweepAt = now + SWEEP_FIRST_DELAY_MS
        nextScanRetryAt = 0L
        lastSweepEndedAt = 0L
        _lastSweepAt.value = 0L
        _sweepFault.value = null
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
        finderJob?.cancel()
        finderJob = lifecycleScope.launch { finderTargetFlow.collect { tick() } }
        approvalJob?.cancel()
        approvalJob = lifecycleScope.launch { approvalChanged.collect { refreshNearbyAlert() } }
    }

    /**
     * Do whatever periodic work has come due. Cheap when nothing has (a handful of
     * comparisons), because it runs on every advert. Main thread only. See the
     * class comment for why this is not a set of delay() loops.
     */
    private fun tick() {
        if (!_running.value) return
        val now = SystemClock.elapsedRealtime()
        maintainLocationSwitch(now)
        maintainTrackerScan(now)
        maintainSweep(now)
        maintainFinder(now)
        if (now >= nextPlaceAt) {
            // Anchor places must accrue from time actually spent somewhere, not from
            // how many trackers happen to advertise there. Previously dwell was only
            // noted when a sighting arrived, so a tracker-free home never became an
            // anchor and household tags were never auto-trusted.
            // The deadline only moves on once a place was noted, so the first note
            // comes with the first fix rather than a full interval after it.
            currentFix()?.let { fix ->
                nextPlaceAt = now + PLACE_TICK_MS
                lifecycleScope.launch {
                    runCatching { repo.notePlace(fix.latitude, fix.longitude) }
                        .onFailure { Log.w(TAG, "notePlace failed: ${it.message}") }
                }
            }
        }
        if (now >= nextPruneAt) {
            nextPruneAt = now + PRUNE_INTERVAL_MS
            lifecycleScope.launch {
                runCatching { repo.prune() }.onFailure { Log.w(TAG, "prune failed: ${it.message}") }
            }
        }
    }

    /**
     * Notice the system Location switch going off or coming back.
     *
     * Android delivers no scan results at all to an app whose scans may be used
     * for location (this one's are: it geotags what it hears) while Location is
     * off. That is a watching fault, not just a missing fix. And a
     * scan REGISTERED while Location was off stays deaf after it comes back, until
     * it is registered again; one registered beforehand resumes by itself. Both
     * seen on the stock Android 16 emulator image: a tracker scan re-registered
     * while Location was off heard nothing for the 3.5 minutes between Location
     * returning and its next re-registration.
     */
    private fun maintainLocationSwitch(now: Long) {
        if (now < nextLocationCheckAt) return
        nextLocationCheckAt = now + TICK_MS
        val off = !location.systemLocationOn()
        if (off == locationOff) return
        locationOff = off
        if (off) {
            Log.w(TAG, "Location switched off: Android withholds scan results")
            // A sweep in flight would report "heard nothing" as if it had listened.
            sweeper.cancel()
        } else {
            Log.i(TAG, "Location is back on: registering the scans afresh")
            rearmScans = true
            finderClient?.stop(); finderMac = null
            nextSweepAt = now + SWEEP_FIRST_DELAY_MS
        }
        publishFault()
    }

    /** Keep the tracker scan registered, at full rate, or say that it is not. */
    private fun maintainTrackerScan(now: Long) {
        if (!scanner.isScanning) {
            // Dead (controller error, or Bluetooth came back without a broadcast we
            // saw). Retry on a slow clock, but only when a start could succeed, so
            // the "Bluetooth is turned off" fault is left alone.
            if (now < nextScanRetryAt || scanner.unavailableReason() != null) return
            tryResumeScan()
            return
        }
        if (scanSettleAt != 0L) {
            // Started again after a failure and still registered: believe it now.
            if (now < scanSettleAt) return
            scanSettleAt = 0L
            setFault(null)
        }
        // Deaf while Location is off whatever is done here; it is registered afresh
        // when Location returns.
        if (locationOff) return
        if (!rearmScans && now - scanner.startedAtElapsed < SCAN_RESTART_MS) return
        if (LeScanClient.startsInWindow(now) >= MAX_STARTS_TRACKER) return
        rearmScans = false
        if (scanner.restart()) {
            Log.i(TAG, "tracker scan re-registered")
        } else {
            nextScanRetryAt = now + SCAN_RETRY_MS
            setFault(scanner.unavailableReason() ?: "Bluetooth scanning stopped unexpectedly")
        }
    }

    /** Start an all-devices sweep when one is due; end the running one when its time is up. */
    private fun maintainSweep(now: Long) {
        if (sweeper.isSweeping) {
            if (now - sweeper.startedAtElapsed >= SWEEP_MS) finishSweep(now)
            return
        }
        if (!settings.nearbyDevices.value) {
            sweepRequested = false
            return
        }
        // No sweep while the tracker scan is down: it is the more important of the
        // two and whatever stopped it (Bluetooth off, a controller error) would
        // stop this as well.
        if (!scanner.isScanning) return
        // Nor while Location is off: it would hear nothing and say "checked".
        if (locationOff) return
        val wanted = sweepRequested && now - lastSweepEndedAt >= SWEEP_ON_OPEN_GAP_MS
        if (!wanted && now < nextSweepAt) return
        if (LeScanClient.startsInWindow(now) > MAX_STARTS_SWEEP) return
        sweepRequested = false
        nextSweepAt = now + SWEEP_PERIOD_MS
        if (sweeper.begin()) {
            _sweepFault.value = null
        } else {
            Log.w(TAG, "all-devices sweep did not start")
            _sweepFault.value = sweeper.unavailableReason() ?: "The all-devices check could not start"
        }
    }

    private fun finishSweep(now: Long) {
        val heard = sweeper.end()
        lastSweepEndedAt = now
        _lastSweepAt.value = System.currentTimeMillis()
        if (!settings.nearbyDevices.value) return
        val fix = currentFix()
        val batch = nearbyThrottle.select(heard, fix?.latitude, fix?.longitude, System.currentTimeMillis())
        Log.i(TAG, "sweep heard ${heard.size} devices, recording ${batch.size}")
        if (heard.isEmpty()) return
        val recorded = batch.mapTo(HashSet()) { it.stableId }
        val alsoHeard = heard.filter { it.stableId !in recorded }
        val accuracy = fix?.takeIf { it.hasAccuracy() }?.accuracy
        lifecycleScope.launch {
            val results = runCatching {
                repo.recordAll(
                    batch, fix?.latitude, fix?.longitude, settings.sensitivity.value, accuracy, alsoHeard
                )
            }.onFailure {
                Log.w(TAG, "sweep not recorded: ${it.message}")
                // Nothing was written (the batch is one transaction), so do not
                // leave these marked as recorded for the next five minutes.
                nearbyThrottle.forget(recorded)
            }.getOrNull() ?: return@launch
            if (results.any { it.newlyAlerting }) raiseNearbyAlert()
        }
    }

    /**
     * The finder on a non-tracker device needs a listen of its own: the tracker
     * scan's hardware filters never match such a device, and a sweep is a 12 s
     * burst every two minutes. So while the finder is open on one, hear everything
     * and keep the adverts from that one address. It listens to everything rather
     * than filtering on the address in the radio because how an address filter
     * treats random addresses differs between Bluetooth chips, and a finder that
     * silently hears nothing is worse than one that costs more while it is open.
     */
    private fun maintainFinder(now: Long) {
        val mac = finderTarget
            ?.takeIf { it.startsWith(TrackerEcosystem.GENERIC_ID_PREFIX) }
            ?.removePrefix(TrackerEcosystem.GENERIC_ID_PREFIX)
        if (mac != finderMac) {
            finderClient?.stop()
            finderMac = null
        }
        if (mac == null || !scanner.isScanning) return
        val client = finderClient ?: LeScanClient(
            this, "$TAG/finder", NearbyDeviceScanner.allDeviceFilters(),
            onResult = ::onFinderResult,
            onFailure = { code -> Log.w(TAG, "finder listen failed ($code)"); finderMac = null }
        ).also { finderClient = it }
        if (!client.isScanning) {
            if (LeScanClient.startsInWindow(now) > MAX_STARTS_SWEEP) return
            if (client.start()) finderMac = mac
        } else if (now - client.startedAtElapsed >= SCAN_RESTART_MS &&
            LeScanClient.startsInWindow(now) <= MAX_STARTS_SWEEP
        ) {
            client.restart()
        }
    }

    private fun onFinderResult(result: ScanResult) {
        val mac = finderMac ?: return
        if (result.device?.address != mac) return
        _finderRssi.value = result.rssi
        _finderHeardAt.value = SystemClock.elapsedRealtime()
    }

    /** A sweep died. The tracker scan is unaffected, so this is not a watching fault. */
    private fun onSweepFailure(errorCode: Int) {
        Log.w(TAG, "all-devices sweep failed ($errorCode)")
        lastSweepEndedAt = SystemClock.elapsedRealtime()
        _sweepFault.value = "The all-devices check failed (Bluetooth error $errorCode). It will try again."
    }

    /** The controller killed the scan. Keep the session, but stop claiming cover. */
    private fun onScanFailure(errorCode: Int) {
        if (!_running.value) return
        Log.e(TAG, "scan failed ($errorCode) — reporting fault")
        scanSettleAt = 0L
        setFault(scanner.unavailableReason() ?: "Bluetooth scanning stopped unexpectedly")
    }

    private fun setFault(reason: String?) {
        scanProblem = reason
        publishFault()
    }

    /** What the user is told about watching: a scan problem first, else Location
     *  being off, else nothing. */
    private fun publishFault() {
        val shown = scanProblem ?: FAULT_LOCATION_OFF.takeIf { locationOff }
        if (_scanFault.value == shown) return
        _scanFault.value = shown
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

    /** Start the tracker scan again after it died or Bluetooth came back, or record
     *  why it still can't run. */
    private fun tryResumeScan() {
        if (!_running.value) return
        if (scanner.isScanning) {
            if (scanSettleAt == 0L) setFault(null)
            return
        }
        val now = SystemClock.elapsedRealtime()
        // Out of scan starts for the moment (see LeScanClient). The fault stays as
        // it is and tick() comes back here as soon as a start is free.
        if (LeScanClient.startsInWindow(now) >= MAX_STARTS_TRACKER) return
        nextScanRetryAt = now + SCAN_RETRY_MS
        if (scanner.start()) {
            // Not "watching" yet. A registration the controller refuses is reported
            // a moment after this returns; clearing the fault here made a scan that
            // kept failing read as fine for an instant on every retry.
            scanSettleAt = now + SCAN_SETTLE_MS
            setFault(FAULT_RESTARTING)
        } else {
            scanSettleAt = 0L
            setFault(scanner.unavailableReason() ?: "Bluetooth scanning is unavailable")
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    // Android silently drops the scan here without calling
                    // onScanFailed, so this receiver is the only way to notice.
                    scanner.stop()
                    sweeper.cancel()
                    finderClient?.stop(); finderMac = null
                    scanSettleAt = 0L
                    setFault("Bluetooth is turned off — not watching")
                }
                BluetoothAdapter.STATE_ON -> {
                    // No longer "turned off", and not yet listening either.
                    nextScanRetryAt = 0L
                    if (!scanner.isScanning) setFault(FAULT_RESTARTING)
                    tryResumeScan()
                }
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
        // A tracker advert wakes the phone even when it is asleep: use the wake.
        tick()
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
                repo.record(
                    obs, fix?.latitude, fix?.longitude, settings.sensitivity.value,
                    fix?.takeIf { it.hasAccuracy() }?.accuracy
                )
            }.onFailure { Log.w(TAG, "sighting not recorded: ${it.message}") }.getOrNull() ?: return@launch
            if (result.newlyAlerting) raiseAlert(result.tracker)
        }
    }

    private fun end() {
        // No early-return guard: all stops below are idempotent, and begin() calls
        // end() on a failed scanner start (when _running was never set) to make sure
        // the location updates and foreground notification are released, not leaked.
        _running.value = false
        scanProblem = null
        locationOff = false
        rearmScans = false
        scanSettleAt = 0L
        _scanFault.value = null
        _locationFault.value = null
        locationJob?.cancel(); locationJob = null
        unregisterBluetoothReceiver()
        scanner.stop()
        sweeper.cancel()
        location.stop()
        lastRecordedAt.clear()
        nearbyThrottle.clear()
        tickJob?.cancel(); tickJob = null
        finderJob?.cancel(); finderJob = null
        approvalJob?.cancel(); approvalJob = null
        finderClient?.stop(); finderClient = null; finderMac = null
        _sweepFault.value = null
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
        // Tagged per tracker: with one shared id, a second tracker's alert silently
        // replaced the first one's notification.
        getSystemService(NotificationManager::class.java)?.notify(tracker.stableId, ALERT_NOTIF_ID, n)
        vibrate()
        Log.w(TAG, "ALERT: ${tracker.stableId} (${tracker.ecosystem})")
    }

    /**
     * One notification for all flagged non-tracker devices, updated in place. It is
     * deliberately quieter and less certain than a tracker alert: nothing says the
     * device is a tracker, and the user's own things, a companion's, or a vehicle's
     * own beacon produce exactly the same pattern as a planted one.
     */
    private suspend fun raiseNearbyAlert() {
        val flagged = flaggedNearby()
        if (flagged.isEmpty()) return
        getSystemService(NotificationManager::class.java)
            ?.notify(NEARBY_NOTIF_ID, nearbyNotification(flagged, quiet = false))
        Log.w(TAG, "NEARBY ALERT: ${flagged.size} device(s): ${flagged.joinToString { it.stableId }}")
    }

    /**
     * The user marked or unmarked a device. If the notification is still up it may
     * name one they have just dealt with: rewrite it without making a sound, or
     * take it down when nothing unmarked is left. Never posts one that was not
     * already showing.
     */
    private suspend fun refreshNearbyAlert() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val showing = runCatching {
            mgr.activeNotifications.any { it.id == NEARBY_NOTIF_ID && it.tag == null }
        }.getOrDefault(false)
        if (!showing) return
        val flagged = flaggedNearby()
        if (flagged.isEmpty()) mgr.cancel(NEARBY_NOTIF_ID)
        else mgr.notify(NEARBY_NOTIF_ID, nearbyNotification(flagged, quiet = true))
    }

    /** Unmarked non-tracker devices that are flagged and were heard recently. */
    private suspend fun flaggedNearby(): List<TrackerEntity> {
        val since = System.currentTimeMillis() - NEARBY_WITH_YOU_MS
        return runCatching { repo.alertingNearby(since) }.getOrNull().orEmpty()
    }

    private fun nearbyNotification(flagged: List<TrackerEntity>, quiet: Boolean): Notification {
        val names = flagged.take(3).joinToString(", ") { it.label }
        val more = if (flagged.size > 3) " and ${flagged.size - 3} more" else ""
        val (title, text) = if (flagged.size == 1) {
            "A Bluetooth device may be moving with you" to
                "$names has stayed close across several places. If it's yours or a companion's, mark it in VIGIL."
        } else {
            "${flagged.size} Bluetooth devices may be moving with you" to
                "$names$more. Mark the ones that are yours or a companion's in VIGIL."
        }
        return buildNotification(
            title = title, text = text,
            high = false, channelId = NEARBY_CHANNEL_ID, ongoing = false, quiet = quiet
        )
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
        ongoing: Boolean = true,
        /** An update to a notification already showing: no sound, no banner. */
        quiet: Boolean = false
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
            // Alert texts run past one line; without this they are cut off.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(quiet)
            .setContentIntent(pi)
            .setCategory(
                when {
                    high -> NotificationCompat.CATEGORY_ALARM
                    ongoing -> NotificationCompat.CATEGORY_SERVICE
                    else -> NotificationCompat.CATEGORY_STATUS
                }
            )
            .setPriority(
                when {
                    high -> NotificationCompat.PRIORITY_HIGH
                    ongoing -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_DEFAULT
                }
            )
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
        // Devices that are NOT known trackers get a separate, ordinary-importance
        // channel: they are flagged far more often for innocent reasons, and the
        // user must be able to quieten them without silencing tracker alerts.
        if (mgr.getNotificationChannel(NEARBY_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    NEARBY_CHANNEL_ID,
                    "Other Bluetooth devices",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "A Bluetooth device that is not a known tracker has been moving with you"
                }
            )
        }
    }
}
