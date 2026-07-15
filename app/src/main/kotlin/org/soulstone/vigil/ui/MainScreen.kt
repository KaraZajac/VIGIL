package org.soulstone.vigil.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.soulstone.vigil.data.db.TrackerEntity
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.model.TrackerStatus
import org.soulstone.vigil.ui.theme.VigilGreen
import org.soulstone.vigil.ui.theme.VigilPeach
import org.soulstone.vigil.ui.theme.VigilRed

@Composable
fun MainScreen(
    running: Boolean,
    trackers: List<TrackerEntity>,
    sensitivity: Sensitivity,
    permissionMessage: String?,
    onStartStop: () -> Unit,
    onSetSensitivity: (Sensitivity) -> Unit,
    onApprove: (String, Boolean) -> Unit
) {
    val alerting = trackers.count { statusOf(it) == TrackerStatus.ALERTING }
    val suspicious = trackers.count { statusOf(it) == TrackerStatus.SUSPICIOUS }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("VIGIL", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "what's been following you",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            StatusBanner(running, alerting, suspicious)
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onStartStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (running) "STOP WATCHING" else "START WATCHING")
            }
            permissionMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = VigilPeach)
            }

            Spacer(Modifier.height(20.dp))
            Text("Sensitivity", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sensitivity.entries.forEach { s ->
                    FilterChip(
                        selected = s == sensitivity,
                        onClick = { onSetSensitivity(s) },
                        label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Trackers seen (${trackers.size})", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            if (trackers.isEmpty()) {
                Text(
                    if (running) "Listening… nothing near you yet." else "Not scanning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(trackers, key = { it.stableId }) { t -> TrackerRow(t, onApprove) }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(running: Boolean, alerting: Int, suspicious: Int) {
    val (label, color) = when {
        !running -> "Idle" to MaterialTheme.colorScheme.surfaceVariant
        alerting > 0 -> "$alerting tracker(s) following you" to VigilRed
        suspicious > 0 -> "Watching $suspicious possible follower(s)" to VigilPeach
        else -> "Clear — nothing is following you" to VigilGreen
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Text(
            label,
            Modifier.padding(16.dp),
            color = Color(0xFF11111B),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TrackerRow(t: TrackerEntity, onApprove: (String, Boolean) -> Unit) {
    val status = statusOf(t)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(ecosystemDisplay(t.ecosystem), fontWeight = FontWeight.SemiBold)
                Text(
                    "${statusLabel(status)} · ${t.sightingCount} sightings · ${relative(t.lastSeen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (status == TrackerStatus.SAFE_APPROVED) {
                TextButton(onClick = { onApprove(t.stableId, false) }) { Text("Un-approve") }
            } else if (status != TrackerStatus.SAFE_BASELINE) {
                TextButton(onClick = { onApprove(t.stableId, true) }) { Text("This is mine") }
            }
        }
    }
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

private fun statusLabel(s: TrackerStatus): String = when (s) {
    TrackerStatus.SAFE_APPROVED -> "Approved (yours)"
    TrackerStatus.SAFE_BASELINE -> "Known (home)"
    TrackerStatus.ALERTING -> "⚠ Following you"
    TrackerStatus.SUSPICIOUS -> "Watching"
    TrackerStatus.OBSERVED -> "Seen once"
}

private fun ecosystemDisplay(name: String): String =
    runCatching { TrackerEcosystem.valueOf(name).display }.getOrDefault(name)

private fun relative(ts: Long): String =
    DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
