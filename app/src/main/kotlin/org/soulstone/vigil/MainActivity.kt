package org.soulstone.vigil

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import org.soulstone.vigil.data.TrackerRepository
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.data.db.VigilDatabase
import org.soulstone.vigil.ring.TrackerRinger
import org.soulstone.vigil.data.settings.Settings
import org.soulstone.vigil.service.ScanService
import org.soulstone.vigil.ui.HistoryScreen
import org.soulstone.vigil.ui.MainScreen
import org.soulstone.vigil.ui.OnboardingScreen
import org.soulstone.vigil.ui.SafetyScreen
import org.soulstone.vigil.ui.theme.VigilTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var repo: TrackerRepository
    private val permissionsGranted = mutableStateOf(false)
    private val notificationsGranted = mutableStateOf(true)
    private val pairedAddresses = mutableStateOf<Set<String>>(emptySet())

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // NOTE: ACCESS_BACKGROUND_LOCATION ("Allow all the time") must be
            // granted separately from app settings; scanning still runs via the
            // foreground service while the app is open. Prompt for it later.
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Scanning needs BLE + location; POST_NOTIFICATIONS is optional and must not
        // block protection if the user declines it.
        val granted = hasEssentialPermissions()
        permissionsGranted.value = granted
        notificationsGranted.value = hasNotificationPermission()
        pairedAddresses.value = readPairedAddresses()
        if (granted) ScanService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.get(this)
        // Settings doubles as the durable allowlist, so approvals survive the
        // database's destructive migration fallback.
        repo = TrackerRepository(VigilDatabase.get(this), approvedStore = settings)
        permissionsGranted.value = hasEssentialPermissions()

        setContent {
            VigilTheme {
                val onboarded by settings.onboarded.collectAsState()
                var showSafety by remember { mutableStateOf(false) }
                var showHistory by remember { mutableStateOf(false) }
                when {
                    !onboarded -> OnboardingScreen(onFinish = {
                        settings.setOnboarded(true)
                        if (!hasEssentialPermissions()) permissionLauncher.launch(requiredPermissions)
                    })
                    showSafety -> SafetyScreen(onBack = { showSafety = false })
                    showHistory -> {
                        val alerts by repo.observeAlerts().collectAsState(initial = emptyList())
                        HistoryScreen(
                            alerts = alerts,
                            onExport = { exportEvidence() },
                            onClear = { lifecycleScope.launch { repo.clearAlerts() } },
                            onBack = { showHistory = false }
                        )
                    }
                    else -> {
                        val running by ScanService.running.collectAsState()
                        val scanFault by ScanService.scanFault.collectAsState()
                        val locationFault by ScanService.locationFault.collectAsState()
                        // Other Bluetooth devices that were only ever heard in passing
                        // are left out once they are a while gone; the cutoff is moved
                        // forward once a minute rather than on every recomposition.
                        val since by produceState(System.currentTimeMillis() - LIST_WINDOW_MS) {
                            while (true) {
                                delay(60_000L)
                                value = System.currentTimeMillis() - LIST_WINDOW_MS
                            }
                        }
                        val trackers by remember(since) { repo.observeTrackersForUi(since) }
                            .collectAsState(initial = emptyList())
                        // Newest first. The hero needs it because a rotating-clone alert
                        // has no tracker row, and an alerted tag drops off the Active list
                        // after 10 min — either way the card would otherwise go green.
                        val alerts by repo.observeAlerts().collectAsState(initial = emptyList())
                        val sensitivity by settings.sensitivity.collectAsState()
                        val nearbyEnabled by settings.nearbyDevices.collectAsState()
                        val lastSweepAt by ScanService.lastSweepAt.collectAsState()
                        val sweepFault by ScanService.sweepFault.collectAsState()
                        val paired by pairedAddresses
                        val granted by permissionsGranted
                        val notifyGranted by notificationsGranted

                        MainScreen(
                            running = running,
                            trackers = trackers,
                            sensitivity = sensitivity,
                            // Notifications are deliberately not required to start
                            // watching, but if they are off, alerts can only vibrate —
                            // so say so rather than leaving it unsignalled.
                            permissionMessage = when {
                                !granted -> "Grant Bluetooth + location to start watching"
                                !notifyGranted -> "Notifications are off — alerts can only vibrate. Tap to fix."
                                else -> null
                            },
                            onPermissionClick = {
                                if (!granted) permissionLauncher.launch(requiredPermissions)
                                else openAppSettings()
                            },
                            scanFault = scanFault,
                            locationFault = locationFault,
                            alerts = alerts,
                            nearbyEnabled = nearbyEnabled,
                            lastSweepAt = lastSweepAt,
                            sweepFault = sweepFault,
                            pairedAddresses = paired,
                            onSetNearby = { on ->
                                settings.setNearbyDevices(on)
                                if (on) ScanService.requestSweep()
                            },
                            onStartStop = {
                                if (running) {
                                    ScanService.stop(this)
                                } else if (granted) {
                                    ScanService.start(this)
                                } else {
                                    permissionLauncher.launch(requiredPermissions)
                                }
                            },
                            onSetSensitivity = { settings.setSensitivity(it) },
                            onApprove = { id, approved ->
                                lifecycleScope.launch {
                                    repo.setApproved(id, approved)
                                    ScanService.noteApprovalChanged(this@MainActivity, id, approved)
                                }
                            },
                            onDistrust = { id ->
                                lifecycleScope.launch { repo.clearBaseline(id) }
                            },
                            onRing = { tracker -> ringTracker(tracker) },
                            onClearAll = {
                                lifecycleScope.launch {
                                    repo.clearAll()
                                    ScanService.noteDevicesCleared(this@MainActivity)
                                }
                            },
                            onOpenSafety = { showSafety = true },
                            onOpenHistory = { showHistory = true },
                            loadTrail = { id -> repo.trailFor(id) }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted.value = hasEssentialPermissions()
        notificationsGranted.value = hasNotificationPermission()
        pairedAddresses.value = readPairedAddresses()
        // The list of other devices is only as fresh as the last sweep; don't make
        // someone who just opened the app wait up to two minutes for one.
        ScanService.requestSweep()
    }

    /**
     * Addresses of devices paired with this phone, to label them in the list of
     * other Bluetooth devices. A label only: an address can be copied, so a paired
     * address is never treated as trusted on its own.
     */
    @SuppressLint("MissingPermission")
    private fun readPairedAddresses(): Set<String> = runCatching {
        getSystemService(BluetoothManager::class.java)?.adapter
            ?.bondedDevices?.mapTo(HashSet()) { it.address }
    }.getOrNull() ?: emptySet()

    private fun hasEssentialPermissions(): Boolean = requiredPermissions
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    /** Optional, but without it a confirmed follower can only buzz the phone. */
    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    private fun ringTracker(tracker: TrackerEntity) {
        Toast.makeText(this, "Trying to ring ${tracker.label}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = TrackerRinger.ring(this@MainActivity, tracker.lastMac, tracker.ecosystem)
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportEvidence() {
        lifecycleScope.launch {
            val report = repo.buildTextReport()
            if (report == null) {
                Toast.makeText(this@MainActivity, "No alerts to export yet.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            runCatching {
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                val txt = File(dir, "vigil-report.txt").apply { writeText(report) }
                val gpx = File(dir, "vigil-track.gpx").apply { writeText(repo.buildGpx()) }
                val auth = "$packageName.fileprovider"
                val uris = arrayListOf(
                    FileProvider.getUriForFile(this@MainActivity, auth, txt),
                    FileProvider.getUriForFile(this@MainActivity, auth, gpx)
                )
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Export VIGIL evidence"))
            }.onFailure {
                Toast.makeText(this@MainActivity, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        // How long an other-device row that was only heard in passing stays loaded.
        // Longer than the list's own 10-minute cut so nothing vanishes early.
        const val LIST_WINDOW_MS = 15 * 60_000L
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
