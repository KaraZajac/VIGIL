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
import org.soulstone.vigil.service.ScanService
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.soulstone.vigil.BuildConfig
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.model.TrackerStatus
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
    onStartStop: () -> Unit,
    onSetSensitivity: (Sensitivity) -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit,
    onRing: (TrackerEntity) -> Unit
) {
    var detail by remember { mutableStateOf<TrackerEntity?>(null) }
    var finding by remember { mutableStateOf<TrackerEntity?>(null) }

    val now = System.currentTimeMillis()
    val active = trackers
        .filter { !isTrusted(it) && now - it.lastSeen < ACTIVE_WINDOW_MS }
        .sortedByDescending { riskRank(it) }
    val trusted = trackers.filter { isTrusted(it) }
    val alerting = active.count { statusOf(it) == TrackerStatus.ALERTING }
    val suspicious = active.count { statusOf(it) == TrackerStatus.SUSPICIOUS }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text("VIGIL", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
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
            item { StatusHero(running, alerting, suspicious) }
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
                    Text(it, style = MaterialTheme.typography.bodySmall, color = VigilPeach)
                }
            }
            item { SensitivitySelector(sensitivity, onSetSensitivity) }

            item {
                SectionHeader(
                    if (active.isEmpty()) "Nothing following you" else "Active (${active.size})"
                )
            }
            if (active.isEmpty()) {
                item {
                    Text(
                        if (running) "Listening… no trackers are moving with you."
                        else "Not scanning. Tap Start Watching.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(active, key = { it.stableId }) { t ->
                    TrackerCard(t, onClick = { detail = t }, onApprove = onApprove, onDistrust = onDistrust)
                }
            }

            if (trusted.isNotEmpty()) {
                item { SectionHeader("Trusted (${trusted.size})") }
                items(trusted, key = { it.stableId }) { t ->
                    TrackerCard(t, onClick = { detail = t }, onApprove = onApprove, onDistrust = onDistrust)
                }
            }

            item {
                Text(
                    "VIGIL ${BuildConfig.VERSION_NAME} · no network, no accounts, no data leaves this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }

    detail?.let { t ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            TrackerDetail(
                t,
                onApprove = { id, a -> onApprove(id, a); detail = null },
                onDistrust = { id -> onDistrust(id); detail = null },
                onFind = { dev -> ScanService.setFinderTarget(dev.stableId); finding = dev; detail = null },
                onRing = onRing
            )
        }
    }

    finding?.let { dev ->
        FinderScreen(
            dev,
            onRing = onRing,
            onClose = { ScanService.setFinderTarget(null); finding = null }
        )
    }
}

@Composable
private fun StatusHero(running: Boolean, alerting: Int, suspicious: Int) {
    data class Look(val title: String, val subtitle: String, val icon: ImageVector, val color: Color)

    val look = when {
        !running -> Look("Idle", "Tap Start Watching to begin.", Icons.Filled.Shield, MaterialTheme.colorScheme.surfaceVariant)
        alerting > 0 -> Look(
            if (alerting == 1) "A tracker is following you" else "$alerting trackers following you",
            "Moving with you across places and time.", Icons.Filled.Warning, VigilRed
        )
        suspicious > 0 -> Look(
            "Keeping watch",
            "$suspicious possible follower(s) — not yet confirmed.", Icons.Filled.GppMaybe, VigilPeach
        )
        else -> Look("You're clear", "Nothing has been following you.", Icons.Filled.CheckCircle, VigilGreen)
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
    onClick: () -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit
) {
    val status = statusOf(t)
    val accent = statusColor(status)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                Text(ecosystemDisplay(t.ecosystem), fontWeight = FontWeight.SemiBold)
                Text(
                    "${statusLabel(status)} · ${t.distinctPlaces} places · ${t.lastRssi} dBm · ${relative(t.lastSeen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit,
    onFind: (TrackerEntity) -> Unit,
    onRing: (TrackerEntity) -> Unit
) {
    val status = statusOf(t)
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bluetooth, null, tint = statusColor(status))
            Spacer(Modifier.size(10.dp))
            Text(ecosystemDisplay(t.ecosystem), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(statusLabel(status), color = statusColor(status), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))

        DetailRow("Signal (last / peak)", "${t.lastRssi} / ${t.peakRssi} dBm")
        DetailRow("Distinct places", t.distinctPlaces.toString())
        DetailRow("Co-movement sightings", t.effectiveSightings.toString())
        DetailRow("Adverts logged", t.sightingCount.toString())
        DetailRow("First seen", relative(t.firstSeen))
        DetailRow("Last seen", relative(t.lastSeen))
        if (t.anchorDayCount > 0) {
            DetailRow("Days at your places", "${t.anchorDayCount} / 3 to trust")
        }
        DetailRow("Identity", t.stableId.take(22) + "…")

        Spacer(Modifier.height(16.dp))
        Text(
            explanation(status),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onFind(t) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Sensors, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Find it")
            }
            FilledTonalButton(onClick = { onRing(t) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Ring it")
            }
        }
        Spacer(Modifier.height(12.dp))
        when (status) {
            TrackerStatus.SAFE_APPROVED ->
                Button(onClick = { onApprove(t.stableId, false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove from approved")
                }
            TrackerStatus.SAFE_BASELINE ->
                Button(onClick = { onDistrust(t.stableId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Not mine — re-check this tracker")
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
    val rssi by ScanService.finderRssi.collectAsState()
    val smoothed = remember { mutableStateOf(-100f) }
    LaunchedEffect(rssi) { rssi?.let { smoothed.value = 0.35f * it + 0.65f * smoothed.value } }
    val hasSignal = rssi != null
    val p = ((smoothed.value + 100f) / 60f).coerceIn(0f, 1f)  // -100 dBm..-40 dBm -> 0..1
    val label = when {
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
                ecosystemDisplay(t.ecosystem),
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
                if (hasSignal) "$rssi dBm" else "no signal yet",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))
            Button(onClick = { onRing(t) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.NotificationsActive, null)
                Spacer(Modifier.size(8.dp))
                Text("Make it ring")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Spacer(Modifier.height(12.dp))
            Text(
                "If it won't ring, it may be a silent or modified tracker — use the signal above to home in on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- pure helpers ----------------------------------------------------------

private const val ACTIVE_WINDOW_MS = 10 * 60_000L  // trackers not seen this recently drop off "Active"

private fun isTrusted(t: TrackerEntity) = t.approved || t.baselineSafe

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

private fun statusColor(s: TrackerStatus): Color = when (s) {
    TrackerStatus.ALERTING -> VigilRed
    TrackerStatus.SUSPICIOUS -> VigilPeach
    TrackerStatus.SAFE_APPROVED, TrackerStatus.SAFE_BASELINE -> VigilGreen
    TrackerStatus.OBSERVED -> Color(0xFF89B4FA)
}

private fun statusIcon(s: TrackerStatus): ImageVector = when (s) {
    TrackerStatus.ALERTING -> Icons.Filled.Warning
    TrackerStatus.SUSPICIOUS -> Icons.Filled.GppMaybe
    TrackerStatus.SAFE_APPROVED, TrackerStatus.SAFE_BASELINE -> Icons.Filled.Verified
    TrackerStatus.OBSERVED -> Icons.Filled.Bluetooth
}

private fun statusLabel(s: TrackerStatus): String = when (s) {
    TrackerStatus.SAFE_APPROVED -> "Approved (yours)"
    TrackerStatus.SAFE_BASELINE -> "Known (around home)"
    TrackerStatus.ALERTING -> "Following you"
    TrackerStatus.SUSPICIOUS -> "Watching"
    TrackerStatus.OBSERVED -> "Seen nearby"
}

private fun explanation(s: TrackerStatus): String = when (s) {
    TrackerStatus.ALERTING -> "This tracker has been close to you across several distinct places over a sustained window — the signature of something travelling with you. If it isn't yours, treat it seriously."
    TrackerStatus.SUSPICIOUS -> "Seen repeatedly and close, but not yet across enough places/time to confirm it's following you. VIGIL keeps watching."
    TrackerStatus.OBSERVED -> "Seen nearby but with no co-movement pattern — most likely ambient."
    TrackerStatus.SAFE_APPROVED -> "You marked this as yours, so VIGIL won't alert on it."
    TrackerStatus.SAFE_BASELINE -> "Regularly around the places you live — learned as part of your normal environment, entirely on-device."
}

private fun ecosystemDisplay(name: String): String =
    runCatching { TrackerEcosystem.valueOf(name).display }.getOrDefault(name)

private fun relative(ts: Long): String =
    DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
