package com.fibstein.wakestop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fibstein.wakestop.data.prefs.AppSettings
import com.fibstein.wakestop.tracking.LocationTrackingService
import com.fibstein.wakestop.tracking.TripStatus
import com.fibstein.wakestop.ui.Permissions
import com.fibstein.wakestop.ui.favorites.FavoritesScreen
import com.fibstein.wakestop.ui.home.HomeScreen
import com.fibstein.wakestop.ui.onboarding.PermissionsScreen
import com.fibstein.wakestop.ui.settings.SettingsScreen
import com.fibstein.wakestop.ui.theme.WakeStopTheme
import com.fibstein.wakestop.ui.tracking.TrackingScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer

        // A persisted trip without a running service means the process was killed: resume it.
        lifecycleScope.launch {
            if (container.trips.status.value is TripStatus.Idle && container.trips.load() != null &&
                Permissions.hasFineLocation(this@MainActivity)
            ) {
                LocationTrackingService.resume(this@MainActivity)
            }
        }

        setContent {
            WakeStopTheme {
                Surface(Modifier.fillMaxSize()) {
                    val status by container.trips.status.collectAsStateWithLifecycle()
                    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
                    val s = settings
                    when {
                        s == null -> Unit
                        status !is TripStatus.Idle -> Box(Modifier.safeDrawingPadding()) { TrackingScreen(status) }
                        else -> AppNav(s)
                    }
                }
            }
        }
    }

    @Composable
    private fun AppNav(settings: AppSettings) {
        val nav = rememberNavController()
        val start = remember { if (settings.onboardingDone && Permissions.hasFineLocation(this)) "home" else "permissions" }
        NavHost(nav, startDestination = start) {
            composable("permissions") {
                Box(Modifier.safeDrawingPadding()) {
                    PermissionsScreen(onDone = {
                        lifecycleScope.launch { appContainer.settings.update { it.copy(onboardingDone = true) } }
                        if (!nav.popBackStack()) {
                            nav.navigate("home") { popUpTo("permissions") { inclusive = true } }
                        }
                    })
                }
            }
            composable("home") {
                HomeScreen(
                    onOpenFavorites = { nav.navigate("favorites") },
                    onOpenSettings = { nav.navigate("settings") },
                    onNeedPermissions = { nav.navigate("permissions") },
                )
            }
            composable("favorites") {
                FavoritesScreen(onBack = { nav.popBackStack() }, onNeedPermissions = { nav.navigate("permissions") })
            }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() }, onOpenPermissions = { nav.navigate("permissions") })
            }
        }
    }
}
