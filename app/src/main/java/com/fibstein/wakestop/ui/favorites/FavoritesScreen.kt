package com.fibstein.wakestop.ui.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fibstein.wakestop.appContainer
import com.fibstein.wakestop.data.db.FavoriteStop
import com.fibstein.wakestop.tracking.Format
import com.fibstein.wakestop.tracking.GeoPoint
import com.fibstein.wakestop.tracking.LocationTrackingService
import com.fibstein.wakestop.ui.Permissions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(onBack: () -> Unit, onNeedPermissions: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { context.appContainer.favorites }
    val favorites by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<FavoriteStop?>(null) }

    fun start(f: FavoriteStop) {
        when {
            !Permissions.hasFineLocation(context) -> onNeedPermissions()
            !Permissions.isLocationEnabled(context) -> context.startActivity(Permissions.locationSettings())
            else -> scope.launch { LocationTrackingService.startNew(context, f.name, GeoPoint(f.lat, f.lon), f.radiusM) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favori duraklar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        if (favorites.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Henüz favori yok. Haritada bir nokta seçip \"Kaydet\"e bas.")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(favorites, key = { it.id }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name) },
                        supportingContent = { Text("Yarıçap ${Format.distance(f.radiusM.toDouble())}") },
                        trailingContent = {
                            androidx.compose.foundation.layout.Row {
                                IconButton(onClick = { toDelete = f }) { Icon(Icons.Filled.Delete, "Sil") }
                                IconButton(onClick = { start(f) }) { Icon(Icons.Filled.PlayArrow, "Başlat") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Silinsin mi?") },
            text = { Text(f.name) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.delete(f.id) }
                    toDelete = null
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Vazgeç") } },
        )
    }
}
