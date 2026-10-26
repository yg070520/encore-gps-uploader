package com.example.cartracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Exported entry point used by NaviShell. It hands off to the existing tracking
 * service so the latter can remain private to this application.
 */
class TrackerService : Service() {

    companion object {
        const val ACTION_START_FROM_NAVISHELL =
            "com.example.cartracker.action.START_FROM_NAVISHELL"

        private const val CHANNEL_ID = "navishell_start"
        private const val NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_FROM_NAVISHELL) {
            Logger.event(this, "NAVISHELL_START_REJECT", "action=${intent?.action}")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Logger.event(this, "NAVISHELL_START", "delegating to TrackService")
        TrackService.start(this, "navishell")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "NaviShell startup",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            builder = Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            builder = Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("CarTracker")
            .setContentText("Starting GPS logging")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
