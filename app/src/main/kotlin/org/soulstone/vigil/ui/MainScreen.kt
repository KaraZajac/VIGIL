package org.soulstone.vigil.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import org.soulstone.vigil.service.ScanService
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.soulstone.vigil.BuildConfig
import android.os.SystemClock
import org.soulstone.vigil.data.TrackerRepository
import org.soulstone.vigil.data.db.AlertEntity
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.detect.CoMovementEvaluator
import org.soulstone.vigil.model.AddressKind
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.R
import org.soulstone.vigil.model.TrackerStatus
import org.soulstone.vigil.model.TrailPoint
import org.soulstone.vigil.ui.theme.VigilGreen
import org.soulstone.vigil.ui.theme.VigilPeach
import org.soulstone.vigil.ui.theme.VigilRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    running: Boolean,
    trackers: List<TrackerEntity>,
    sensitivity: Sensitivity,
    permissionMessage: String?,
    onPermissionClick: () -> Unit,
    scanFault: String?,
    locationFault: String?,
    /** Newest first. */
    alerts: List<AlertEntity>,
    /** Whether the all-devices sweep is switched on. */
    nearbyEnabled: Boolean,
    /** Wall-clock time of the last finished sweep; 0 if none yet this session. */
    lastSweepAt: Long,
    sweepFault: String?,
    /** Addresses of devices paired with this phone. Shown as a hint, never trusted. */
    pairedAddresses: Set<String>,
    onSetNearby: (Boolean) -> Unit,
    onStartStop: () -> Unit,
    onSetSensitivity: (Sensitivity) -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit,
    onRing: (TrackerEntity) -> Unit,
    onClearAll: () -> Unit,
    onOpenSafety: () -> Unit,
    onOpenHistory: () -> Unit,
    loadTrail: suspend (String) -> List<TrailPoint>
) {
    var detail by remember { mutableStateOf<TrackerEntity?>(null) }
    var finding by remember { mutableStateOf<TrackerEntity?>(null) }
    var showAllNearby by remember { mutableStateOf(false) }

    // Ticks so the Active window keeps advancing. A single composition-time value
    // meant the list only re-filtered when a new sighting arrived, so with scanning
    // stopped, stale trackers sat in "Active" forever.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    // Known tracker types and "any other Bluetooth device" are kept apart all the
    // way down: different evidence, different certainty, different wording.
    val (others, known) = trackers.partition { isGeneric(it) }
    val active = known
        .filter { !isTrusted(it) && now - it.lastSeen < ACTIVE_WINDOW_MS }
        .sortedByDescending { riskRank(it) }
    // Devices learned at the user's usual places stay in this list (dimmed) rather
    // than in "Trusted": a home can have dozens, and nobody chose them.
    val nearby = others
        .filter { !it.approved && now - it.lastSeen < ACTIVE_WINDOW_MS }
        .sortedWith(
            compareByDescending<TrackerEntity> { riskRank(it) }
                .thenBy { AddressKind.fromCode(it.addrKind).rotates }
                .thenByDescending { it.lastRssi }
        )
    val trusted = known.filter { isTrusted(it) } + others.filter { it.approved }
    val alerting = active.count { statusOf(it) == TrackerStatus.ALERTING }
    val suspicious = active.count { statusOf(it) == TrackerStatus.SUSPICIOUS }
    val nearbyAlerting = nearby.count { statusOf(it) == TrackerStatus.ALERTING }
    // An alert the user has since answered with "It's mine" is no longer news. It
    // used to keep the card red for four hours after they had said so.
    val openAlerts = alerts.asSequence()
        .takeWhile { now - it.timestamp < RECENT_ALERT_MS }
        .filter { a -> trackers.none { it.stableId == a.trackerId && isTrusted(it) } }
        .toList()
    val recentAlert = openAlerts.firstOrNull { !isGenericName(it.ecosystem) }
    val recentNearbyAlert = openAlerts.firstOrNull { isGenericName(it.ecosystem) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(R.drawable.vigil_logo),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("VIGIL", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "Alert history",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSafety) {
                        Icon(
                            Icons.Filled.HealthAndSafety,
                            contentDescription = "Safety & help",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (trackers.isNotEmpty()) {
                        IconButton(onClick = onClearAll) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "Clear all trackers",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(
                            Icons.Filled.Lock, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "on-device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item {
                StatusHero(
                    running, alerting, suspicious, scanFault, locationFault, recentAlert,
                    nearbyAlerting, recentNearbyAlert, nearbyEnabled, now
                )
            }
            item {
                Button(
                    onClick = onStartStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        contentColor = if (running) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(if (running) Icons.Filled.Radar else Icons.Filled.Shield, null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (running) "STOP WATCHING" else "START WATCHING", fontWeight = FontWeight.Bold)
                }
            }
            permissionMessage?.let {
                item {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = VigilPeach,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onPermissionClick)
                    )
                }
            }
            item { SensitivitySelector(sensitivity, onSetSensitivity) }

            item {
                SectionHeader(
                    if (active.isEmpty()) "Trackers nearby" else "Trackers nearby (${active.size})"
                )
            }
            if (active.isEmpty()) {
                item {
                    Text(
                        when {
                            !running -> "Not scanning. Tap Start Watching."
                            scanFault != null -> "Not listening right now — see above."
                            else -> "Listening… no unknown trackers heard in the last 10 minutes."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(active, key = { it.stableId }) { t ->
                    TrackerCard(t, now, false, onClick = { detail = t }, onApprove = onApprove, onDistrust = onDistrust)
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SectionHeader(
                            if (nearbyEnabled && nearby.isNotEmpty()) "Other devices nearby (${nearby.size})"
                            else "Other Bluetooth devices"
                        )
                    }
                    Switch(checked = nearbyEnabled, onCheckedChange = onSetNearby)
                }
            }
            if (!nearbyEnabled) {
                item {
                    Text(
                        "Off. VIGIL is watching for known tracker types only.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                if (sweepFault != null && running) {
                    item { Text(sweepFault, style = MaterialTheme.typography.bodySmall, color = VigilPeach) }
                }
                if (nearby.isEmpty()) {
                    item {
                        Text(
                            when {
                                !running -> "Not scanning."
                                scanFault != null -> "Not listening right now — see above."
                                lastSweepAt == 0L -> "First check in a few seconds…"
                                else -> "No other devices heard in the last 10 minutes."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val shown = if (showAllNearby) nearby else nearby.take(NEARBY_COLLAPSED)
                    items(shown, key = { it.stableId }) { t ->
                        TrackerCard(
                            t, now, t.lastMac in pairedAddresses,
                            onClick = { detail = t }, onApprove = onApprove, onDistrust = onDistrust
                        )
                    }
                    if (nearby.size > NEARBY_COLLAPSED) {
                        item {
                            TextButton(onClick = { showAllNearby = !showAllNearby }) {
                                Text(if (showAllNearby) "Show fewer" else "Show all ${nearby.size}")
                            }
                        }
                    }
                }
                item {
                    Text(
                        nearbyFooter(running, lastSweepAt, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (trusted.isNotEmpty()) {
                item { SectionHeader("Trusted (${trusted.size})") }
                items(trusted, key = { it.stableId }) { t ->
                    TrackerCard(
                        t, now, isGeneric(t) && t.lastMac in pairedAddresses,
                        onClick = { detail = t }, onApprove = onApprove, onDistrust = onDistrust
                    )
                }
            }

            item {
                Text(
                    "VIGIL ${BuildConfig.VERSION_NAME} · no network, no accounts, no cloud backup",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }

    // Show the live row, not the copy taken when the sheet opened: the numbers on
    // an open sheet used to stay frozen while the device kept being heard. Falls
    // back to the copy when the row has left the list.
    detail?.let { opened ->
        val t = trackers.firstOrNull { it.stableId == opened.stableId } ?: opened
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            TrackerDetail(
                t,
                paired = isGeneric(t) && t.lastMac in pairedAddresses,
                onApprove = { id, a -> onApprove(id, a); detail = null },
                onDistrust = { id -> onDistrust(id); detail = null },
                onFind = { dev -> finding = dev; detail = null },
                onRing = onRing,
                onSafety = { onOpenSafety(); detail = null },
                loadTrail = loadTrail
            )
        }
    }

    finding?.let { dev ->
        FinderScreen(
            dev,
            onRing = onRing,
            onClose = { finding = null }
        )
    }
}

@Composable
private fun StatusHero(
    running: Boolean,
    alerting: Int,
    suspicious: Int,
    scanFault: String?,
    locationFault: String?,
    recentAlert: AlertEntity?,
    nearbyAlerting: Int,
    recentNearbyAlert: AlertEntity?,
    nearbyEnabled: Boolean,
    now: Long
) {
    data class Look(val title: String, val subtitle: String, val icon: ImageVector, val color: Color)

    // A fresh alert stays the headline after watching stops. "Idle" used to replace
    // it, so stopping VIGIL straight after an alert hid the alert from the card.
    val alertShown = alerting > 0 || recentAlert != null || nearbyAlerting > 0 || recentNearbyAlert != null
    val look = when {
        !running && !alertShown ->
            Look("Idle", "Tap Start Watching to begin.", Icons.Filled.Shield, MaterialTheme.colorScheme.surfaceVariant)
        alerting > 0 -> Look(
            if (alerting == 1) "A tracker is following you" else "$alerting trackers following you",
            "Moving with you across places and time.", Icons.Filled.Warning, VigilRed
        )
        // An alert outlives its tracker row: a rotating clone never has one, and an
        // alerted tag leaves the Active list after 10 min. Without this the card went
        // green the moment someone opened the app from the alert notification.
        recentAlert != null -> Look(
            if (recentAlert.trackerId == TrackerRepository.CLONE_TRACKER_ID) "Possible hidden tracker" else "Tracker alert",
            "${recentAlert.label} flagged ${relative(recentAlert.timestamp, now)}. See Alert history.",
            Icons.Filled.Warning, VigilRed
        )
        // A dead scan must never read as safety. This case used to be invisible, so
        // the app showed "You're clear" while it had stopped listening entirely.
        scanFault != null -> Look(
            "Not watching", scanFault, Icons.Filled.BluetoothDisabled, VigilPeach
        )
        // Same for location: with no fix nothing can alert, so quiet means nothing.
        locationFault != null -> Look(
            "Location needed", locationFault, Icons.Filled.LocationOff, VigilPeach
        )
        // A device that is NOT a known tracker type. Worded and coloured so it can't
        // be taken for a tracker alert. It names the innocent causes without saying
        // how likely they are: nobody has measured that.
        // "May be": the rule cannot tell carried from planted, nor a companion's
        // device from either.
        nearbyAlerting > 0 -> Look(
            if (nearbyAlerting == 1) "A device may be moving with you"
            else "$nearbyAlerting devices may be moving with you",
            (if (nearbyAlerting == 1) "Not a known tracker type. It may be yours, a companion's, or part of a vehicle."
            else "Not known tracker types. They may be yours, a companion's, or part of a vehicle.") +
                (if (nearbyEnabled) " Check the list below."
                else " Switch on Other Bluetooth devices below to see the list."),
            Icons.Filled.GppMaybe, VigilPeach
        )
        recentNearbyAlert != null -> Look(
            "Bluetooth device flagged",
            "${recentNearbyAlert.label} was moving with you ${relative(recentNearbyAlert.timestamp, now)}. Not a known tracker type. See Alert history.",
            Icons.Filled.GppMaybe, VigilPeach
        )
        suspicious > 0 -> Look(
            "Keeping watch",
            "$suspicious possible follower(s) — not yet confirmed.", Icons.Filled.GppMaybe, VigilPeach
        )
        // Says what VIGIL observed, not that nothing is there: rotation in the gap,
        // duty-cycled clones and tags that never report "separated" all stay quiet.
        else -> Look("All quiet", "No tracker seen moving with you.", Icons.Filled.CheckCircle, VigilGreen)
    }

    // A standing alert outranks a fault for the headline, but must not hide that
    // VIGIL has stopped hearing anything since, or that it is not watching at all.
    val faultNote = when {
        !running && alertShown -> "Not watching right now. Tap Start Watching to keep checking."
        running && scanFault != null && (alerting > 0 || recentAlert != null) -> "Not listening right now. $scanFault"
        else -> null
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = look.color)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color(0x22111111)),
                contentAlignment = Alignment.Center
            ) {
                Icon(look.icon, null, tint = Color(0xFF11111B), modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.size(16.dp))
            Column {
                Text(
                    look.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF11111B)
                )
                Text(look.subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xCC11111B))
                faultNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF11111B),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensitivitySelector(current: Sensitivity, onSet: (Sensitivity) -> Unit) {
    Column {
        SectionHeader("Sensitivity")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sensitivity.entries.forEach { s ->
                FilterChip(
                    selected = s == current,
                    onClick = { onSet(s) },
                    label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (current) {
                Sensitivity.HIGH -> "Alerts fastest — more early warnings, more false alarms."
                Sensitivity.MEDIUM -> "Balanced — the recommended default."
                Sensitivity.LOW -> "Alerts only on strong, sustained evidence."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun TrackerCard(
    t: TrackerEntity,
    now: Long,
    paired: Boolean,
    onClick: () -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit
) {
    val status = statusOf(t)
    val generic = isGeneric(t)
    val accent = statusColor(status, generic)
    // Other devices VIGIL has learned at the user's usual places sit in the nearby
    // list; dim them so the ones nobody has accounted for stand out.
    val dim = generic && status == TrackerStatus.SAFE_BASELINE
    Card(
        Modifier.fillMaxWidth().alpha(if (dim) 0.6f else 1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(statusIcon(status), null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName(t), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${statusLabel(status, generic)} · ${t.distinctPlaces} ${if (t.distinctPlaces == 1) "place" else "places"} · ${t.lastRssi} dBm · ${relative(t.lastSeen, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (generic) {
                    Text(
                        nearbyHint(t, paired),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            when (status) {
                TrackerStatus.SAFE_APPROVED ->
                    TextButton(onClick = { onApprove(t.stableId, false) }) { Text("Undo") }
                TrackerStatus.SAFE_BASELINE ->
                    TextButton(onClick = { onDistrust(t.stableId) }) { Text("Not mine") }
                else -> TextButton(onClick = { onApprove(t.stableId, true) }) { Text("It's mine") }
            }
        }
    }
}

@Composable
private fun TrackerDetail(
    t: TrackerEntity,
    paired: Boolean,
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit,
    onFind: (TrackerEntity) -> Unit,
    onRing: (TrackerEntity) -> Unit,
    onSafety: () -> Unit,
    loadTrail: suspend (String) -> List<TrailPoint>
) {
    val status = statusOf(t)
    val generic = isGeneric(t)
    val kind = AddressKind.fromCode(t.addrKind)
    // The sheet can be taller than the screen now that it carries more rows.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bluetooth, null, tint = statusColor(status, generic))
            Spacer(Modifier.size(10.dp))
            Text(displayName(t), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(statusLabel(status, generic), color = statusColor(status, generic), fontWeight = FontWeight.SemiBold)
        if (generic) {
            Text(
                "Bluetooth device · not a known tracker type",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // For a device that is not a known tracker, "Watching" is the everyday state
        // of the user's own things, so the safety button waits for a real flag.
        if (status == TrackerStatus.ALERTING || (status == TrackerStatus.SUSPICIOUS && !generic)) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onSafety,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (generic) VigilPeach else VigilRed,
                    contentColor = Color(0xFF11111B)
                )
            ) {
                Icon(Icons.Filled.HealthAndSafety, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("What should I do?", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))

        DetailRow("Signal (last / peak)", "${t.lastRssi} / ${t.peakRssi} dBm")
        DetailRow("Distinct places", t.distinctPlaces.toString())
        DetailRow("Co-movement sightings", t.effectiveSightings.toString())
        DetailRow("Sightings logged", t.sightingCount.toString())
        DetailRow("First seen", relative(t.firstSeen))
        DetailRow("Last seen", relative(t.lastSeen))
        if (t.anchorDayCount > 0) {
            DetailRow("Days at your places", "${t.anchorDayCount} / 3 to trust")
        }
        if (generic) {
            if (t.vendor.isNotEmpty()) DetailRow("Maker (as broadcast)", t.vendor)
            DetailRow("Address", t.lastMac)
            DetailRow("Address type", addressKindLabel(kind))
            if (paired) DetailRow("Paired with this phone", "Yes")
        } else {
            DetailRow("Identity", t.stableId.take(22) + "…")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            explanation(status, generic),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (generic) {
            Spacer(Modifier.height(10.dp))
            Text(
                genericCaveat(kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Reloaded when it reaches a new place, so an open sheet's trail keeps up.
        var trail by remember(t.stableId) { mutableStateOf(emptyList<TrailPoint>()) }
        LaunchedEffect(t.stableId, t.distinctPlaces) { trail = loadTrail(t.stableId) }
        if (trail.size >= 2) {
            Spacer(Modifier.height(18.dp))
            Text("Where it was seen", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            TrailView(
                trail,
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            )
            Text(
                "A schematic of the ${trail.size} points it was seen — not a real map.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onFind(t) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Sensors, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Find it")
            }
            // Ringing means connecting, which VIGIL only does to known tracker types.
            if (!generic) {
                FilledTonalButton(onClick = { onRing(t) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Ring it")
                }
            }
        }
        var showInfo by remember { mutableStateOf(false) }
        if (!generic) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showInfo = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Who owns it?")
            }
        }
        if (showInfo) {
            AlertDialog(
                onDismissRequest = { showInfo = false },
                confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } },
                title = { Text("Who owns this tracker?") },
                text = {
                    Text(
                        "Tap the tracker against the top of your phone (NFC). If it's an AirTag or a DULT tag in lost mode, your phone opens a page with the owner's masked phone or email. VIGIL stays offline - the tag's NFC hands the link to your browser."
                    )
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        when (status) {
            TrackerStatus.SAFE_APPROVED ->
                Button(onClick = { onApprove(t.stableId, false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove from approved")
                }
            TrackerStatus.SAFE_BASELINE ->
                Button(onClick = { onDistrust(t.stableId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (generic) "Not mine — re-check this device" else "Not mine — re-check this tracker")
                }
            else ->
                Button(onClick = { onApprove(t.stableId, true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("This is mine — stop alerting")
                }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Passive hot/cold finder — smoothed live RSSI as a growing, colour-shifting
 *  proximity meter. Works even on silent/modified tags that refuse to ring. */
@Composable
private fun FinderScreen(t: TrackerEntity, onRing: (TrackerEntity) -> Unit, onClose: () -> Unit) {
    // Intercept system back so it closes the finder instead of exiting the app.
    BackHandler(onBack = onClose)
    // The finder listens only while it is on screen. For a non-tracker device that
    // listen hears everything at full rate, so it must not outlive the screen:
    // set from the button and cleared from Close, it kept running after the screen
    // went away any other way (rotated, closed from the recents list) until
    // watching stopped. Tied to this screen's lifecycle it cannot.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(t.stableId, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> ScanService.setFinderTarget(t.stableId)
                Lifecycle.Event.ON_STOP -> ScanService.setFinderTarget(null)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ScanService.setFinderTarget(null)
        }
    }
    val rssi by ScanService.finderRssi.collectAsState()
    val heardAt by ScanService.finderHeardAt.collectAsState()
    val smoothed = remember { mutableStateOf(-100f) }
    LaunchedEffect(rssi, heardAt) { rssi?.let { smoothed.value = 0.35f * it + 0.65f * smoothed.value } }
    // Re-check freshness every second: a tag that goes quiet, rotates its ID or
    // drops out of range sends nothing, so nothing else would clear the reading.
    val nowElapsed by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val lost = rssi != null && nowElapsed - heardAt > FINDER_SILENCE_MS
    val hasSignal = rssi != null && !lost
    val p = if (hasSignal) ((smoothed.value + 100f) / 60f).coerceIn(0f, 1f) else 0f  // -100 dBm..-40 dBm -> 0..1
    val label = when {
        lost -> "Signal lost — keep moving"
        !hasSignal -> "Searching… walk around"
        p > 0.8f -> "Right here"
        p > 0.6f -> "Very close"
        p > 0.4f -> "Close"
        p > 0.2f -> "Getting warmer"
        else -> "Far"
    }
    val color = lerp(VigilRed, VigilGreen, p)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                displayName(t),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier.size((120 + p * 160).dp).clip(CircleShape).background(
                    if (hasSignal) color.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Sensors, null,
                    tint = if (hasSignal) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(
                label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (hasSignal) color else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when {
                    hasSignal -> "$rssi dBm"
                    lost -> "nothing heard for ${FINDER_SILENCE_MS / 1000} s"
                    else -> "no signal yet"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))
            val generic = isGeneric(t)
            if (!generic) {
                Button(onClick = { onRing(t) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.NotificationsActive, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Make it ring")
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Spacer(Modifier.height(12.dp))
            Text(
                if (generic) "VIGIL only listens to this device and never connects to it. The signal stops if it changes its address or VIGIL stops watching."
                else "If it won't ring, it may be a silent or modified tracker — use the signal above to home in on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Offline schematic of a tracker's geotagged sightings — points + path, scaled
 *  to fit. Not a real map (VIGIL has no network); the GPX export opens in one. */
@Composable
private fun TrailView(points: List<TrailPoint>, modifier: Modifier = Modifier) {
    val line = Color(0xFF89B4FA)
    val startC = Color(0xFFA6E3A1)
    val endC = Color(0xFFFAB387)
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val pad = 18f
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        val rLat = (maxLat - minLat).let { if (it > 1e-9) it else 1e-9 }
        val rLon = (maxLon - minLon).let { if (it > 1e-9) it else 1e-9 }
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val pts = points.map { p ->
            Offset(
                pad + ((p.lon - minLon) / rLon).toFloat() * w,
                pad + (1f - ((p.lat - minLat) / rLat).toFloat()) * h
            )
        }
        for (i in 0 until pts.size - 1) {
            drawLine(line.copy(alpha = 0.55f), pts[i], pts[i + 1], strokeWidth = 3f)
        }
        pts.forEachIndexed { i, o ->
            val c = if (i == 0) startC else if (i == pts.lastIndex) endC else line
            drawCircle(c, radius = if (i == 0 || i == pts.lastIndex) 6f else 4f, center = o)
        }
    }
}

// ---- pure helpers ----------------------------------------------------------

private const val ACTIVE_WINDOW_MS = 10 * 60_000L  // trackers not seen this recently drop off "Active"
// Keep an alert on the hero for as long as VIGIL won't re-notify about the same device.
private const val RECENT_ALERT_MS = CoMovementEvaluator.ALERT_COOLDOWN_MS
private const val FINDER_SILENCE_MS = 10_000L       // finder reading counts as lost after this

// How many other devices the list shows before "Show all".
private const val NEARBY_COLLAPSED = 6

private fun isTrusted(t: TrackerEntity) = t.approved || t.baselineSafe

private fun isGenericName(ecosystem: String) = ecosystem == TrackerEcosystem.OTHER_BLE.name

/** True for "any other Bluetooth device", false for a known tracker type. */
private fun isGeneric(t: TrackerEntity) = isGenericName(t.ecosystem)

/** Card and sheet title: the tracker type, or for other devices what they broadcast. */
private fun displayName(t: TrackerEntity): String =
    if (isGeneric(t)) t.label.ifEmpty { TrackerEcosystem.OTHER_BLE.display } else ecosystemDisplay(t.ecosystem)

/** Second line of an other-device card: what tells this one from the next. */
private fun nearbyHint(t: TrackerEntity, paired: Boolean): String = buildList {
    if (t.name.isNotEmpty() && t.vendor.isNotEmpty()) add(t.vendor)
    add("…" + t.lastMac.takeLast(5))
    if (paired) add("paired with this phone")
    if (AddressKind.fromCode(t.addrKind).rotates) add("address changes")
}.joinToString(" · ")

private fun nearbyFooter(running: Boolean, lastSweepAt: Long, now: Long): String {
    val checked = if (running && lastSweepAt > 0L) " Last check ${relative(lastSweepAt, now)}." else ""
    return "Headphones, watches, beacons, and trackers VIGIL has no profile for. Bluetooth Low Energy only, " +
        "checked in a ${ScanService.SWEEP_MS / 1000}-second burst about every ${ScanService.SWEEP_PERIOD_MS / 60_000} " +
        "minutes, less often while the phone sleeps.$checked A device can only be followed while it keeps the same " +
        "address. Phones and watches change theirs regularly (the Bluetooth standard suggests every 15 minutes), " +
        "and some earbuds and other accessories do too."
}

private fun addressKindLabel(kind: AddressKind): String = when (kind) {
    AddressKind.PUBLIC -> "Fixed (public)"
    AddressKind.STATIC -> "Static (can change when it restarts)"
    AddressKind.RESOLVABLE -> "Private (changes regularly)"
    AddressKind.NON_RESOLVABLE -> "Private (changes)"
    AddressKind.UNKNOWN -> "Not reported before Android 15"
}

/** What a user must know before reading anything into an other-device row. */
private fun genericCaveat(kind: AddressKind): String {
    val address = when {
        kind.rotates -> "This device changes its Bluetooth address, so VIGIL can only follow it until the next change and will then list it as a new device."
        kind == AddressKind.UNKNOWN -> "If this device changes its Bluetooth address (phones and watches do regularly, and so do some earbuds and other accessories), it shows up as a new device and VIGIL can't follow it across the change."
        kind == AddressKind.STATIC -> "This device uses an address that normally stays the same until it restarts, so VIGIL can follow it."
        else -> "This device uses a fixed address, so VIGIL can follow it."
    }
    return "$address The name, maker and address are whatever the device broadcasts; one built to hide can change all three."
}

private fun riskRank(t: TrackerEntity): Int = when (statusOf(t)) {
    TrackerStatus.ALERTING -> 3
    TrackerStatus.SUSPICIOUS -> 2
    TrackerStatus.OBSERVED -> 1
    else -> 0
}

private fun statusOf(t: TrackerEntity): TrackerStatus = when {
    t.approved -> TrackerStatus.SAFE_APPROVED
    t.baselineSafe -> TrackerStatus.SAFE_BASELINE
    else -> when (t.riskState) {
        "ALERTING" -> TrackerStatus.ALERTING
        "SUSPICIOUS" -> TrackerStatus.SUSPICIOUS
        else -> TrackerStatus.OBSERVED
    }
}

// Other devices never use the tracker-alert red, and "Watching" (the everyday state
// of the user's own headphones) is not a warning colour for them.
private fun statusColor(s: TrackerStatus, generic: Boolean = false): Color = when (s) {
    TrackerStatus.ALERTING -> if (generic) VigilPeach else VigilRed
    TrackerStatus.SUSPICIOUS -> if (generic) Color(0xFF89B4FA) else VigilPeach
    TrackerStatus.SAFE_APPROVED, TrackerStatus.SAFE_BASELINE -> VigilGreen
    TrackerStatus.OBSERVED -> Color(0xFF89B4FA)
}

private fun statusIcon(s: TrackerStatus): ImageVector = when (s) {
    TrackerStatus.ALERTING -> Icons.Filled.Warning
    TrackerStatus.SUSPICIOUS -> Icons.Filled.GppMaybe
    TrackerStatus.SAFE_APPROVED, TrackerStatus.SAFE_BASELINE -> Icons.Filled.Verified
    TrackerStatus.OBSERVED -> Icons.Filled.Bluetooth
}

private fun statusLabel(s: TrackerStatus, generic: Boolean = false): String = when (s) {
    TrackerStatus.SAFE_APPROVED -> "Approved (yours)"
    TrackerStatus.SAFE_BASELINE -> "Known (usual places)"
    TrackerStatus.ALERTING -> if (generic) "Moving with you" else "Following you"
    TrackerStatus.SUSPICIOUS -> "Watching"
    TrackerStatus.OBSERVED -> "Seen nearby"
}

private fun explanation(s: TrackerStatus, generic: Boolean): String =
    if (generic) genericExplanation(s) else trackerExplanation(s)

private fun genericExplanation(s: TrackerStatus): String = when (s) {
    TrackerStatus.ALERTING -> "This device has been heard close to you at several separate places over a sustained time: the pattern of something travelling with you. VIGIL does not recognise it as a tracker. It may be something of yours, something carried by a person travelling with you, or part of a vehicle you ride in. If you can't account for it, treat it as a possible tracker."
    TrackerStatus.SUSPICIOUS -> "Heard close to you at more than one place, but not across enough places and time to say it is travelling with you. Your own devices sit here until you mark them. VIGIL keeps watching."
    TrackerStatus.OBSERVED -> "Heard nearby. Nothing so far suggests it is moving with you."
    TrackerStatus.SAFE_APPROVED -> "You marked this as yours, so VIGIL won't alert on this address. If the device changes its address it will appear as new."
    TrackerStatus.SAFE_BASELINE -> "Heard on 3+ days at places where you spend a lot of time, without moving with you — learned on-device."
}

private fun trackerExplanation(s: TrackerStatus): String = when (s) {
    TrackerStatus.ALERTING -> "This tracker has been heard near you at several distinct places over a sustained window — the pattern of something travelling with you. If it isn't yours, treat it seriously."
    TrackerStatus.SUSPICIOUS -> "Heard near you repeatedly, but not yet across enough places and time to say it's following you. VIGIL keeps watching."
    TrackerStatus.OBSERVED -> "Heard nearby, but not moving with you while away from its owner — most likely ambient."
    TrackerStatus.SAFE_APPROVED -> "You marked this as yours, so VIGIL won't alert on this ID. Trackers other than Tile change ID at least daily, so yours may reappear as new."
    TrackerStatus.SAFE_BASELINE -> "Heard on 3+ days at places where you spend a lot of time, without moving with you — learned on-device."
}

private fun ecosystemDisplay(name: String): String =
    runCatching { TrackerEcosystem.valueOf(name).display }.getOrDefault(name)

/** Pass MainScreen's ticking `now` wherever the text must age on screen. Reading the
 *  clock in here alone froze "2 minutes ago" on cards and on the alert banner: their
 *  inputs never changed, so Compose skipped them on every tick. Clamped so a sighting
 *  newer than the last tick reads as now, not as the future. */
private fun relative(ts: Long, now: Long = System.currentTimeMillis()): String =
    // DateUtils words anything under a minute as "0 minutes ago".
    if (now - ts < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(ts, now, DateUtils.MINUTE_IN_MILLIS).toString()
