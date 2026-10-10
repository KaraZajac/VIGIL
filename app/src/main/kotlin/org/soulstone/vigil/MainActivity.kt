package org.soulstone.vigil

import android.Manifest
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
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
                        val trackers by repo.observeTrackers().collectAsState(initial = emptyList())
                        val sensitivity by settings.sensitivity.collectAsState()
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
                                lifecycleScope.launch { repo.setApproved(id, approved) }
                            },
                            onDistrust = { id ->
                                lifecycleScope.launch { repo.clearBaseline(id) }
                            },
                            onRing = { tracker -> ringTracker(tracker) },
                            onClearAll = { lifecycleScope.launch { repo.clearAll() } },
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
    }

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

    private fun openAppSettings() {
        startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
