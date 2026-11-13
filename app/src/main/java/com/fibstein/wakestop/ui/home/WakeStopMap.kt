package com.fibstein.wakestop.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fibstein.wakestop.tracking.Geo
import com.fibstein.wakestop.tracking.GeoPoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SRC_AREA = "wakestop-area"
private const val SRC_POINT = "wakestop-point"

/** Camera move request; [id] makes repeated requests to the same point distinct. */
data class CameraRequest(val target: GeoPoint, val zoom: Double, val id: Long = System.nanoTime())

@Composable
fun WakeStopMap(
    destination: GeoPoint?,
    radiusM: Double,
    camera: CameraRequest?,
    locationPermission: Boolean,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val longPress by rememberUpdatedState(onLongPress)

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isRotateGesturesEnabled = false
            m.addOnMapLongClickListener { latLng ->
                longPress(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }
            m.setStyle(STYLE_URL) { s ->
                s.addSource(GeoJsonSource(SRC_AREA))
                s.addSource(GeoJsonSource(SRC_POINT))
                s.addLayer(
                    FillLayer("wakestop-area-fill", SRC_AREA)
                        .withProperties(PropertyFactory.fillColor("#1E5BD8"), PropertyFactory.fillOpacity(0.15f)),
                )
                s.addLayer(
                    LineLayer("wakestop-area-line", SRC_AREA)
                        .withProperties(PropertyFactory.lineColor("#1E5BD8"), PropertyFactory.lineWidth(2f)),
                )
                s.addLayer(
                    CircleLayer("wakestop-point-circle", SRC_POINT).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor("#E8590C"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                style = s
            }
            map = m
        }
        onDispose { }
    }

    LaunchedEffect(style, destination, radiusM) {
        val s = style ?: return@LaunchedEffect
        val area = s.getSourceAs<GeoJsonSource>(SRC_AREA) ?: return@LaunchedEffect
        val point = s.getSourceAs<GeoJsonSource>(SRC_POINT) ?: return@LaunchedEffect
        if (destination == null) {
            area.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            point.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        } else {
            val ring = Geo.circle(destination, radiusM).map { Point.fromLngLat(it.lon, it.lat) }
            area.setGeoJson(Polygon.fromLngLats(listOf(ring)))
            point.setGeoJson(Point.fromLngLat(destination.lon, destination.lat))
        }
    }

    LaunchedEffect(map, camera) {
        val m = map ?: return@LaunchedEffect
        val c = camera ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.target.lat, c.target.lon), c.zoom), 800)
    }

    LaunchedEffect(map, style, locationPermission) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (locationPermission) enableLocation(context, m, s)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun enableLocation(context: Context, map: MapLibreMap, style: Style) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) {
        lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, style).build())
    }
    lc.isLocationComponentEnabled = true
    lc.cameraMode = CameraMode.NONE
    lc.renderMode = RenderMode.COMPASS
}
