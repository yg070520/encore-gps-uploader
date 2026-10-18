package com.example.cartracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object Watchdog {
    const val INTERVAL_MS = 5 * 60 * 1000L

    private fun pi(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, 0, Intent(c, WatchdogReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            pi(c)
        )
    }

    fun cancel(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c))
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // running 是进程内静态变量: false 表示进程是新建的,或服务已不在
        Logger.event(c, "WATCHDOG", "fired, serviceAliveInThisProcess=${TrackService.running}")
        TrackService.start(c)
        Watchdog.schedule(c)
    }
}
