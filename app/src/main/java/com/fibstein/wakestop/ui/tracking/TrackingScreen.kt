package com.fibstein.wakestop.ui.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fibstein.wakestop.tracking.AlarmReason
import com.fibstein.wakestop.tracking.Format
import com.fibstein.wakestop.tracking.LocationTrackingService
import com.fibstein.wakestop.tracking.TripStatus
import com.fibstein.wakestop.ui.Permissions

@Composable
fun TrackingScreen(status: TripStatus) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (status) {
            is TripStatus.Alarming -> {
                Text("Durağına yaklaştın!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(status.trip.name, style = MaterialTheme.typography.titleMedium)
                Text(reasonText(status.reason), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { LocationTrackingService.dismissAlarm(context) },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("Alarmı kapat", fontSize = 20.sp) }
            }
            is TripStatus.Tracking -> {
                val snap = status.snapshot
                Text(status.trip.name.ifBlank { "Varış noktası" }, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Text(Format.distance(snap?.distanceM), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text("kalan mesafe (kuş uçuşu)", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Stat("Tahmini varış", Format.eta(snap?.etaSec))
                    Stat("Hız", Format.speedKmh(snap?.speedMps))
                    Stat("Yarıçap", Format.distance(status.trip.radiusM.toDouble()))
                }
                Spacer(Modifier.height(24.dp))
                if (!status.locationEnabled) {
                    Warning(Icons.Filled.LocationOff, "Konum servisi kapalı. Açmazsan alarm çalamaz.") {
                        context.startActivity(Permissions.locationSettings())
                    }
                } else if (snap?.signalLost == true) {
                    Warning(Icons.Filled.GpsOff, "GPS sinyali yok. Son hıza göre tahmini varışta alarm çalınacak.", null)
                } else if (snap?.distanceM == null) {
                    Text("Konum bekleniyor…")
                }
                if (snap?.preAlerted == true) {
                    Text("Yaklaşma uyarısı verildi", color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(32.dp))
                OutlinedButton(onClick = { LocationTrackingService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Takibi iptal et")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ekranı kapatabilirsin; takip arka planda sürer.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            TripStatus.Idle -> Unit
        }
    }
}

private fun reasonText(r: AlarmReason) = when (r) {
    AlarmReason.DISTANCE -> "Alarm yarıçapına girdin"
    AlarmReason.ETA -> "Tahmini varış süresi eşiğin altına indi"
    AlarmReason.SIGNAL_LOST_FAILSAFE -> "GPS yok; tahmini varış süresi doldu"
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Warning(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onFix: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.padding(4.dp))
            Text(text, modifier = Modifier.weight(1f))
            if (onFix != null) {
                Button(onClick = onFix, colors = ButtonDefaults.buttonColors()) { Text("Aç") }
            }
        }
    }
}
