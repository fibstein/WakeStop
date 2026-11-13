package com.fibstein.wakestop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fibstein.wakestop.alarm.SilentModeBehavior
import com.fibstein.wakestop.appContainer
import com.fibstein.wakestop.data.prefs.AppSettings
import com.fibstein.wakestop.tracking.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val store = remember { context.appContainer.settings }
    val s by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    fun update(f: (AppSettings) -> AppSettings) = scope.launch { store.update(f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Section("Varsayılan alarm yarıçapı: ${Format.distance(s.defaultRadiusM.toDouble())}")
            Slider(
                value = s.defaultRadiusM.toFloat(),
                onValueChange = { v -> update { it.copy(defaultRadiusM = (v / 50).toInt() * 50) } },
                valueRange = 100f..3000f,
            )

            Section(
                if (s.etaThresholdMin == 0) "Süre tetikleyici: kapalı"
                else "Tahmini varışa ${s.etaThresholdMin} dk kala alarm",
            )
            Slider(
                value = s.etaThresholdMin.toFloat(),
                onValueChange = { v -> update { it.copy(etaThresholdMin = v.toInt()) } },
                valueRange = 0f..10f,
                steps = 9,
            )
            Hint("Mesafe kuş uçuşu ölçülür; süre tahmini kıvrımlı güzergâhta iyimser olabilir. Yarıçap tetikleyici her zaman paralel çalışır.")

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Section("Telefon sessizdeyken (kulaklık yoksa)")
            Choice("Yine de sesli çal", s.silentModeBehavior == SilentModeBehavior.SOUND) {
                update { it.copy(silentModeBehavior = SilentModeBehavior.SOUND) }
            }
            Choice("Sadece titreşim", s.silentModeBehavior == SilentModeBehavior.VIBRATE_ONLY) {
                update { it.copy(silentModeBehavior = SilentModeBehavior.VIBRATE_ONLY) }
            }
            if (s.silentModeBehavior == SilentModeBehavior.VIBRATE_ONLY) {
                Text(
                    "Uyarı: Titreşim derin uykudaki birini uyandırmayabilir.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Hint("Kulaklık bağlıysa alarm yalnızca kulaklıktan çalar; titreşim her durumda çalışır.")

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Section("Kulaklıkta kapatılmazsa hoparlöre geç")
                    Hint("Kulaklık kulaktan düşmüş olabilir. Sessiz modda \"sadece titreşim\" seçiliyse geçiş yapılmaz.")
                }
                Switch(
                    checked = s.headphoneFallbackToSpeaker,
                    onCheckedChange = { v -> update { it.copy(headphoneFallbackToSpeaker = v) } },
                )
            }
            if (s.headphoneFallbackToSpeaker) {
                Text("Bekleme: ${s.headphoneFallbackDelaySec} sn")
                Slider(
                    value = s.headphoneFallbackDelaySec.toFloat(),
                    onValueChange = { v -> update { it.copy(headphoneFallbackDelaySec = (v / 5).toInt() * 5) } },
                    valueRange = 10f..120f,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("İzinler ve pil optimizasyonu")
            }
        }
    }
}

@Composable
private fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
