package org.soulstone.vigil.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.soulstone.vigil.ui.theme.VigilRed

/** Survivor-centred guidance shown when a tracker may be following you. Every
 *  action hands off to the dialer / messages / browser — VIGIL stays offline. */
@Composable
fun SafetyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    fun go(intent: Intent) = runCatching { ctx.startActivity(intent) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                Spacer(Modifier.size(4.dp))
                Text("If a tracker is following you", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "VIGIL is a detection tool, not a substitute for professional help. If you're in immediate danger, call emergency services.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Step(1, "Get somewhere safe", "If you feel in danger, head to a public place or someone you trust before doing anything else.")
            Step(2, "Think before you remove it", "Turning a tracker off can alert whoever placed it and escalate the risk. If you might be in danger, consider documenting it and getting help first.")
            Step(3, "Document what you can", "Save when and where it was seen (use Export), and photograph the device if you find it. A dated record helps police and advocates.")
            Step(4, "Report it", "Contact local police — 911 if you're in immediate danger. If this involves a partner or ex, a domestic-violence advocate can help you make a safety plan.")

            Spacer(Modifier.height(24.dp))
            Text("Get help", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VigilRed, contentColor = Color(0xFF11111B))
            ) {
                Icon(Icons.Filled.Call, null); Spacer(Modifier.size(8.dp)); Text("Call 911 (emergency)", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("U.S. National Domestic Violence Hotline", fontWeight = FontWeight.SemiBold)
                    Text("Free, confidential, 24/7 — help with stalking and abuse, including safety planning.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:18007997233"))) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Call, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Call")
                        }
                        OutlinedButton(onClick = { go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:88788")).putExtra("sms_body", "START")) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Chat, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Text START")
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:18554842846"))) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Call, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("VictimConnect — 1-855-484-2846 (stalking victims)")
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.stalkingawareness.org/what-to-do-if-you-are-being-stalked/"))) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("What to do if you're being stalked (SPARC)")
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "These open your phone, messages, or browser — VIGIL itself never connects to the internet. Resources shown are U.S.; check what's available where you are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(n: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) { Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.size(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
