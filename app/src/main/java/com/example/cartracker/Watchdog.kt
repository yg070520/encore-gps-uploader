package com.example.cartracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 非唤醒闹钟(ELAPSED_REALTIME): 休眠期间不会把车机叫醒(避免亏电),
 * 车机醒来后会立刻补发。late 反映了休眠时长;
 * 如果进程已死但闹钟还在,这里能把进程拉起来 —— 这正是要验证的。
 * 注意: 若系统对本 app 做了 force-stop,闹钟会被清除,此路径无效。
 */
object Watchdog {
    const val INTERVAL_MS = 60_000L
    private const val PREF = "wd"

    private fun pi(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, 0, Intent(c, WatchdogReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun schedule(c: Context) {
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong("expectedAt", at).apply()
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pi(c))
    }

    fun cancel(c: Context) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c))
    }

    fun lateSeconds(c: Context): Long {
        val exp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("expectedAt", 0L)
        return if (exp == 0L) -1 else (SystemClock.elapsedRealtime() - exp) / 1000
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val late = Watchdog.lateSeconds(c)
        // 服务正常且按时触发就不刷日志;服务不在或迟到(休眠过)才记
        if (!TrackService.running || late > 10) {
            Logger.event(c, "WATCHDOG", "fired late=${late}s serviceAliveInThisProcess=${TrackService.running}")
        }
        if (!TrackService.running) TrackService.start(c, "watchdog")
        Watchdog.schedule(c)
    }
}
