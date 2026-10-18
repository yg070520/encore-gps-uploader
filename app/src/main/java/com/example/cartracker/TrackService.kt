package com.example.cartracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class TrackService : Service() {

    companion object {
        const val CHANNEL_ID = "track"
        const val USE_WAKELOCK = false   // 想测"持有 CPU 唤醒锁"的效果时改成 true

        @Volatile var running = false
        @Volatile var listening = false
        @Volatile var lastFixElapsedMs = 0L
        @Volatile var fixCount = 0L

        private const val CONNECTIVITY = "android.net.conn.CONNECTIVITY_CHANGE"
        private const val AMAP = "AUTONAVI_STANDARD_BROADCAST_SEND"

        val STANDARD_ACTIONS = listOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_SHUTDOWN,
            Intent.ACTION_DREAMING_STARTED,
            Intent.ACTION_DREAMING_STOPPED,
            Intent.ACTION_TIME_CHANGED,
            CONNECTIVITY
        )

        // 车机厂商 ACC 广播的名字是猜的,需要你自己点火/熄火后看日志,再改成真实 action
        val VENDOR_GUESS_ACTIONS = listOf(
            "android.intent.action.ACC_ON",
            "android.intent.action.ACC_OFF",
            "com.android.action.ACC_ON",
            "com.android.action.ACC_OFF",
            "com.android.systemui.ACC_ON",
            "com.android.systemui.ACC_OFF"
        )

        val AMAP_ACTIONS = listOf(AMAP)

        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, TrackService::class.java))
            } catch (e: Exception) {
                Logger.event(c, "SERVICE_START_FAIL", e.toString())
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, TrackService::class.java))
        }
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var gnssCb: GnssStatus.Callback? = null
    private var lastFixNanos = 0L
    private var satsUsed = 0
    private var satsTotal = 0
    private var fixesAtLastBeat = 0L
    private var lastAmapLog = 0L
    private var amapSuppressed = 0

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val dt = if (lastFixNanos == 0L) 0L else (l.elapsedRealtimeNanos - lastFixNanos) / 1_000_000
            lastFixNanos = l.elapsedRealtimeNanos
            lastFixElapsedMs = SystemClock.elapsedRealtime()
            fixCount++
            Logger.gps(
                this@TrackService,
                "${System.currentTimeMillis()},${l.time},${l.latitude},${l.longitude}," +
                    "${l.speed},${l.bearing},${l.altitude},${l.accuracy}," +
                    "${l.isFromMockProvider},$dt,$satsUsed/$satsTotal"
            )
        }

        override fun onProviderEnabled(provider: String) {
            Logger.event(this@TrackService, "PROVIDER", "enabled $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Logger.event(this@TrackService, "PROVIDER", "disabled $provider")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    private val dynReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val action = i.action ?: return
            if (action == AMAP) {
                // 高德广播可能很频繁,最多每 5 秒记一条
                val now = SystemClock.elapsedRealtime()
                if (now - lastAmapLog < 5000) {
                    amapSuppressed++
                    return
                }
                lastAmapLog = now
                Logger.event(c, "AMAP", "suppressed=$amapSuppressed ${dump(i)}")
                amapSuppressed = 0
                return
            }
            Logger.event(c, "BCAST", "$action ${dump(i)}")
            if (action != CONNECTIVITY) {
                // 唤醒类事件: 重新挂定位监听,并重置看门狗
                ensureLocation("wake:$action")
                Watchdog.schedule(c)
            }
        }
    }

    private fun dump(i: Intent): String {
        val b = i.extras ?: return ""
        return b.keySet().joinToString(", ") { k -> "$k=${b.get(k)}" }.take(800)
    }

    private val beat = object : Runnable {
        override fun run() {
            val ago = if (lastFixElapsedMs == 0L) -1L
            else (SystemClock.elapsedRealtime() - lastFixElapsedMs) / 1000
            val delta = fixCount - fixesAtLastBeat
            fixesAtLastBeat = fixCount
            Logger.event(
                this@TrackService, "HEARTBEAT",
                "fixes_last_min=$delta total=$fixCount lastFixAgo=${ago}s sats=$satsUsed/$satsTotal listening=$listening"
            )
            if (listening && (ago < 0 || ago > 60)) ensureLocation("stale")
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        goForeground()
        Logger.event(this, "SERVICE_CREATE", "")

        if (USE_WAKELOCK) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cartracker:track").apply { acquire() }
        }

        val f = IntentFilter()
        (STANDARD_ACTIONS + VENDOR_GUESS_ACTIONS + AMAP_ACTIONS).forEach { f.addAction(it) }
        registerReceiver(dynReceiver, f)

        registerGnss()
        handler.postDelayed(beat, 60_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        Logger.event(
            this, "SERVICE_START",
            "intent=${if (intent == null) "null(system restart)" else "ok"} flags=$flags startId=$startId"
        )
        if (!listening) ensureLocation("onStartCommand")
        Watchdog.schedule(this)
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CarTracker")
            .setContentText("GPS logging")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun ensureLocation(reason: String) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Logger.event(this, "GPS", "no ACCESS_FINE_LOCATION permission ($reason)")
            return
        }
        try {
            lm.removeUpdates(locListener)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
            listening = true
            Logger.event(
                this, "GPS",
                "requestLocationUpdates ($reason) gpsEnabled=${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}"
            )
        } catch (e: Exception) {
            Logger.event(this, "GPS", "request failed ($reason): $e")
        }
    }

    private fun registerGnss() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val cb = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var used = 0
                    for (k in 0 until status.satelliteCount) if (status.usedInFix(k)) used++
                    satsUsed = used
                    satsTotal = status.satelliteCount
                }
            }
            lm.registerGnssStatusCallback(cb)
            gnssCb = cb
        } catch (e: Exception) {
            Logger.event(this, "GPS", "gnss callback failed: $e")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.event(this, "TASK_REMOVED", "")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Logger.event(this, "SERVICE_DESTROY", "")
        running = false
        listening = false
        handler.removeCallbacksAndMessages(null)
        try { lm.removeUpdates(locListener) } catch (_: Exception) {}
        try { gnssCb?.let { lm.unregisterGnssStatusCallback(it) } } catch (_: Exception) {}
        try { unregisterReceiver(dynReceiver) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
