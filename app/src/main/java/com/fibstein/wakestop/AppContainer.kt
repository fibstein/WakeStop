package com.fibstein.wakestop

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.fibstein.wakestop.alarm.AlarmNotifier
import com.fibstein.wakestop.data.db.AppDatabase
import com.fibstein.wakestop.data.geocoding.PhotonClient
import com.fibstein.wakestop.data.prefs.SettingsStore
import com.fibstein.wakestop.tracking.TripRepository
import org.maplibre.android.MapLibre

/** Manual DI: one instance per process. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val settings = SettingsStore(app)
    val trips = TripRepository(app)
    val photon = PhotonClient()
    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "wakestop.db").build()
    val favorites get() = database.favoriteStopDao()
}

class WakeStopApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AlarmNotifier.createChannels(this)
        MapLibre.getInstance(this)
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as WakeStopApp).container
