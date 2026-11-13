package com.fibstein.wakestop.ui.onboarding

import android.Manifest
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fibstein.wakestop.ui.Permissions

@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    // Bumped on resume / permission result to re-read system state.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val location = remember(refresh) { Permissions.hasFineLocation(context) }
    val notifications = remember(refresh) { Permissions.hasNotifications(context) }
    val fullScreen = remember(refresh) { Permissions.canUseFullScreenIntent(context) }
    val battery = remember(refresh) { Permissions.isIgnoringBatteryOptimizations(context) }
    val locationOn = remember(refresh) { Permissions.isLocationEnabled(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("WakeStop'a hoş geldin", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Uyuyakalsan bile durağını kaçırmaman için birkaç izne ihtiyaç var. " +
                "Konum yalnızca sen bir yolculuk başlattığında, bildirimde görünen bir servisle takip edilir.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        Step(
            "Hassas konum (zorunlu)",
            "Durağa olan mesafeyi ölçmek için. \"Yaklaşık konum\" yeterli değil.",
            location,
        ) {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (!location) {
            TextButton(onClick = { context.startActivity(Permissions.appDetails(context)) }) {
                Text("Daha önce reddettiysen: uygulama ayarlarını aç")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Step("Bildirimler", "Takip bildirimi ve alarm için.", notifications) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Step("Tam ekran alarm", "Alarmın kilit ekranının üstünde açılması için.", fullScreen) {
                context.startActivity(Permissions.fullScreenIntentSettings(context))
            }
        }
        Step(
            "Pil optimizasyonu muafiyeti",
            "Sistemin takibi uzun yolculukta durdurmaması için.",
            battery,
        ) {
            context.startActivity(Permissions.batteryOptimizationRequest(context))
        }
        Step("Konum servisi açık", "Telefonun konum (GPS) ayarı.", locationOn) {
            context.startActivity(Permissions.locationSettings())
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Xiaomi, Huawei, Samsung gibi bazı üreticilerin ek pil yöneticileri arka plan servislerini yine de " +
                "kapatabilir. Bu cihazlarda WakeStop'u \"kısıtlamasız\" / \"otomatik başlat\" listesine eklemen gerekebilir.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://dontkillmyapp.com".toUri()))
        }) { Text("Cihazına özel talimatlar (dontkillmyapp.com)") }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onDone, enabled = location, modifier = Modifier.fillMaxWidth()) {
            Text(if (location) "Devam" else "Devam için konum izni gerekli")
        }
    }
}

@Composable
private fun Step(title: String, description: String, done: Boolean, onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (!done) OutlinedButton(onClick = onRequest) { Text("İzin ver") }
        }
    }
}
