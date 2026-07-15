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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.soulstone.vigil.data.TrackerRepository
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.data.db.VigilDatabase
import org.soulstone.vigil.ring.TrackerRinger
import org.soulstone.vigil.data.settings.Settings
import org.soulstone.vigil.service.ScanService
import org.soulstone.vigil.ui.MainScreen
import org.soulstone.vigil.ui.theme.VigilTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var repo: TrackerRepository
    private val permissionsGranted = mutableStateOf(false)

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
        if (granted) ScanService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.get(this)
        repo = TrackerRepository(VigilDatabase.get(this))
        permissionsGranted.value = hasEssentialPermissions()

        setContent {
            VigilTheme {
                val running by ScanService.running.collectAsState()
                val trackers by repo.observeTrackers().collectAsState(initial = emptyList())
                val sensitivity by settings.sensitivity.collectAsState()
                val granted by permissionsGranted

                MainScreen(
                    running = running,
                    trackers = trackers,
                    sensitivity = sensitivity,
                    permissionMessage = if (granted) null
                    else "Grant Bluetooth + location to start watching",
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
                    onClearAll = { lifecycleScope.launch { repo.clearAll() } }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted.value = hasEssentialPermissions()
    }

    private fun hasEssentialPermissions(): Boolean = requiredPermissions
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun ringTracker(tracker: TrackerEntity) {
        Toast.makeText(this, "Trying to ring ${tracker.label}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = TrackerRinger.ring(this@MainActivity, tracker.lastMac, tracker.ecosystem)
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("unused")
    private fun openAppSettings() {
        startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
