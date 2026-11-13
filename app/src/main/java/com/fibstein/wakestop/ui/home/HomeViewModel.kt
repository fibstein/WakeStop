package com.fibstein.wakestop.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fibstein.wakestop.appContainer
import com.fibstein.wakestop.data.db.FavoriteStop
import com.fibstein.wakestop.data.geocoding.PlaceResult
import com.fibstein.wakestop.tracking.GeoPoint
import com.fibstein.wakestop.tracking.LocationTrackingService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class Selection(val name: String, val point: GeoPoint)

data class HomeUiState(
    val query: String = "",
    val results: List<PlaceResult> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val selection: Selection? = null,
    val radiusM: Int = 500,
    val myLocation: GeoPoint? = null,
    val camera: CameraRequest? = null,
)

@OptIn(FlowPreview::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val queries = MutableStateFlow("")
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val radius = container.settings.current().defaultRadiusM
            _state.update { it.copy(radiusM = radius) }
        }
        viewModelScope.launch {
            // Photon is fair-use: debounce and skip short queries.
            queries.debounce(600).distinctUntilChanged().collect { q -> search(q) }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        queries.value = q
    }

    private suspend fun search(q: String) {
        if (q.trim().length < 3) {
            _state.update { it.copy(results = emptyList(), searching = false, searchError = null) }
            return
        }
        _state.update { it.copy(searching = true, searchError = null) }
        try {
            val results = container.photon.search(q, _state.value.myLocation)
            _state.update { it.copy(results = results, searching = false) }
        } catch (e: Exception) {
            Log.w(TAG, "Search failed", e)
            _state.update { it.copy(results = emptyList(), searching = false, searchError = "Arama yapılamadı") }
        }
    }

    fun onResultPicked(r: PlaceResult) {
        _state.update {
            it.copy(
                selection = Selection(r.name, r.point),
                results = emptyList(),
                query = "",
                camera = CameraRequest(r.point, 15.0),
            )
        }
        queries.value = ""
    }

    fun onMapLongPress(p: GeoPoint) {
        _state.update { it.copy(selection = Selection("Seçilen nokta", p)) }
        viewModelScope.launch {
            val name = runCatching { container.photon.reverse(p)?.name }.getOrNull() ?: return@launch
            _state.update { s -> if (s.selection?.point == p) s.copy(selection = Selection(name, p)) else s }
        }
    }

    fun clearSelection() = _state.update { it.copy(selection = null) }

    fun onRadiusChange(m: Int) = _state.update { it.copy(radiusM = m) }

    fun centerOnMe() {
        val me = _state.value.myLocation
        if (me != null) _state.update { it.copy(camera = CameraRequest(me, 15.0)) } else refreshMyLocation(true)
    }

    @SuppressLint("MissingPermission")
    fun refreshMyLocation(moveCamera: Boolean) {
        val ctx = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        viewModelScope.launch {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val loc = runCatching {
                client.lastLocation.await()
                    ?: client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
            }.getOrNull() ?: return@launch
            val p = GeoPoint(loc.latitude, loc.longitude)
            _state.update {
                it.copy(myLocation = p, camera = if (moveCamera || it.camera == null) CameraRequest(p, 14.0) else it.camera)
            }
        }
    }

    fun saveFavorite(name: String) {
        val sel = _state.value.selection ?: return
        viewModelScope.launch {
            container.favorites.insert(
                FavoriteStop(name = name.trim().ifBlank { sel.name }, lat = sel.point.lat, lon = sel.point.lon, radiusM = _state.value.radiusM),
            )
        }
    }

    fun startTrip() {
        val sel = _state.value.selection ?: return
        val radius = _state.value.radiusM
        viewModelScope.launch { LocationTrackingService.startNew(getApplication(), sel.name, sel.point, radius) }
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
