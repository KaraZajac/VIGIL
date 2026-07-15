package org.soulstone.vigil.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.soulstone.vigil.data.db.AlertEntity
import org.soulstone.vigil.model.TrackerEcosystem

/** Log of past alerts, with a one-tap evidence export (report + GPX). */
@Composable
fun HistoryScreen(
    alerts: List<AlertEntity>,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                Spacer(Modifier.size(4.dp))
                Text("Alert history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Export evidence")
                }
                OutlinedButton(onClick = onClear, enabled = alerts.isNotEmpty()) { Text("Clear") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Export saves a timestamped report and a GPX track (open it in a maps app). Files stay on your phone until you share them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            if (alerts.isEmpty()) {
                Text(
                    "No alerts yet. When a tracker is confirmed following you, it'll be logged here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(alerts, key = { it.id }) { a -> AlertRow(a) }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(a: AlertEntity) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(ecoName(a.ecosystem), fontWeight = FontWeight.SemiBold)
            Text(
                "${a.distinctPlaces} places · peak ${a.peakRssi} dBm · ${rel(a.timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ecoName(name: String) = runCatching { TrackerEcosystem.valueOf(name).display }.getOrDefault(name)
private fun rel(ts: Long) = DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
