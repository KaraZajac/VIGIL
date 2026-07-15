package org.soulstone.vigil.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.soulstone.vigil.R

/** First-run: what VIGIL does, its offline promise, and why it needs its permissions. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Image(painterResource(R.drawable.vigil_logo), null, modifier = Modifier.size(76.dp))
            Spacer(Modifier.height(14.dp))
            Text("VIGIL", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Know what's been following you.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(36.dp))

            Point(
                Icons.Filled.Sensors,
                "Finds what follows you",
                "Detects AirTags, Tile, Samsung SmartTags and Find My trackers that travel with you over time — not just whatever happens to be nearby."
            )
            Point(
                Icons.Filled.Lock,
                "Stays on your phone",
                "No account, no internet, no telemetry. Everything VIGIL learns lives on this device and never leaves it."
            )
            Point(
                Icons.Filled.LocationOn,
                "Why it needs permissions",
                "Bluetooth to hear trackers, location to tell one is moving with you across places, and notifications to warn you. That's the whole reason it asks."
            )

            Spacer(Modifier.height(32.dp))
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Get started", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp).padding(top = 2.dp))
        Spacer(Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
