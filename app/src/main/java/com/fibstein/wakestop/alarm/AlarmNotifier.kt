package com.fibstein.wakestop.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fibstein.wakestop.MainActivity
import com.fibstein.wakestop.R
import com.fibstein.wakestop.tracking.LocationTrackingService
import com.fibstein.wakestop.ui.alarm.AlarmActivity

object AlarmNotifier {
    const val CH_TRACKING = "tracking"
    const val CH_PRE_ALERT = "pre_alert"
    const val CH_ALARM = "alarm"
    const val CH_SIGNAL = "signal"

    const val ID_TRACKING = 1
    const val ID_PRE_ALERT = 2
    const val ID_ALARM = 3
    const val ID_SIGNAL = 4

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_TRACKING, "Yolculuk takibi", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Kalan mesafeyi gösteren kalıcı bildirim"
                    setShowBadge(false)
                },
                NotificationChannel(CH_PRE_ALERT, "Yaklaşma uyarısı", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Durağa yaklaşırken verilen ön uyarı"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                },
                NotificationChannel(CH_ALARM, "Varış alarmı", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Durağa varınca çalan alarm"
                    // Sound and vibration are produced by AlarmPlayer, not by the notification.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CH_SIGNAL, "GPS sinyali", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Konum sinyali kaybolduğunda uyarı"
                    enableVibration(true)
                },
            ),
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, LocationTrackingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun trackingNotification(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_wakestop)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "İptal", serviceIntent(context, LocationTrackingService.ACTION_STOP, 10))
            .build()

    fun alarmNotification(context: Context, tripName: String): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, 1,
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_wakestop)
            .setContentTitle("Durağına yaklaştın!")
            .setContentText(tripName.ifBlank { "Varış noktası" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Kapat", serviceIntent(context, LocationTrackingService.ACTION_DISMISS_ALARM, 11))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showPreAlert(context: Context, text: String) = post(
        context, ID_PRE_ALERT,
        NotificationCompat.Builder(context, CH_PRE_ALERT)
            .setSmallIcon(R.drawable.ic_stat_wakestop)
            .setContentTitle("Durağına yaklaşıyorsun")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build(),
    )

    fun showSignalLost(context: Context) = post(
        context, ID_SIGNAL,
        NotificationCompat.Builder(context, CH_SIGNAL)
            .setSmallIcon(R.drawable.ic_stat_wakestop)
            .setContentTitle("GPS sinyali kayboldu")
            .setContentText("Konum alınamıyor. Tahmini varışta yine de alarm çalınacak.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context))
            .build(),
    )

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
