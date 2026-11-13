package com.fibstein.wakestop.ui.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fibstein.wakestop.tracking.Format
import com.fibstein.wakestop.ui.Permissions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    onNeedPermissions: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var hasLocation by remember { mutableStateOf(Permissions.hasFineLocation(context)) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        hasLocation = Permissions.hasFineLocation(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(hasLocation) { if (hasLocation) vm.refreshMyLocation(moveCamera = false) }

    fun tryStart() {
        when {
            !Permissions.hasFineLocation(context) -> onNeedPermissions()
            !Permissions.isLocationEnabled(context) -> scope.launch {
                val r = snackbar.showSnackbar("Konum servisi kapalı", actionLabel = "Aç")
                if (r == SnackbarResult.ActionPerformed) context.startActivity(Permissions.locationSettings())
            }
            else -> {
                if (!Permissions.hasNotifications(context)) {
                    Toast.makeText(context, "Bildirim izni yok: alarm bildirimi gösterilemeyebilir", Toast.LENGTH_LONG).show()
                }
                vm.startTrip()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WakeStop") },
                actions = {
                    IconButton(onClick = onOpenFavorites) { Icon(Icons.Filled.Favorite, "Favoriler") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Ayarlar") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            WakeStopMap(
                destination = state.selection?.point,
                radiusM = state.radiusM.toDouble(),
                camera = state.camera,
                locationPermission = hasLocation,
                onLongPress = vm::onMapLongPress,
                modifier = Modifier.fillMaxSize(),
            )

            SearchBox(
                state = state,
                onQueryChange = vm::onQueryChange,
                onPick = vm::onResultPicked,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
            )

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                SmallFloatingActionButton(onClick = {
                    if (hasLocation) vm.centerOnMe() else onNeedPermissions()
                }) { Icon(Icons.Filled.MyLocation, "Konumum") }
                Spacer(Modifier.size(8.dp))
                SelectionCard(
                    state = state,
                    onRadiusChange = vm::onRadiusChange,
                    onClear = vm::clearSelection,
                    onSave = { saveDialog = true },
                    onStart = ::tryStart,
                )
            }
        }
    }

    if (saveDialog) {
        var name by rememberSaveable { mutableStateOf(state.selection?.name ?: "") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Favorilere kaydet") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Ad") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveFavorite(name)
                    saveDialog = false
                    Toast.makeText(context, "Kaydedildi", Toast.LENGTH_SHORT).show()
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun SearchBox(
    state: HomeUiState,
    onQueryChange: (String) -> Unit,
    onPick: (com.fibstein.wakestop.data.geocoding.PlaceResult) -> Unit,
    modifier: Modifier,
) {
    Card(modifier) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = { Text("Durak veya adres ara") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                when {
                    state.searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    state.query.isNotEmpty() -> IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, "Temizle") }
                }
            },
            singleLine = true,
        )
        state.searchError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (state.results.isNotEmpty()) {
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(state.results) { r ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (r.detail.isNotBlank()) {
                            Text(r.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SelectionCard(
    state: HomeUiState,
    onRadiusChange: (Int) -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onStart: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        val sel = state.selection
        if (sel == null) {
            Text(
                "Varış noktasını seçmek için haritada uzun bas veya yukarıdan ara.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Card
        }
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sel.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Seçimi kaldır") }
            }
            Text("Alarm yarıçapı: ${Format.distance(state.radiusM.toDouble())}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.radiusM.toFloat(),
                onValueChange = { onRadiusChange((it / 50).toInt() * 50) },
                valueRange = 100f..3000f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave) {
                    Icon(Icons.Filled.StarBorder, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Kaydet")
                }
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Başlat") }
            }
        }
    }
}
