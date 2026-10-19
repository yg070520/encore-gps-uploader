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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class TrackService : Service() {

    companion object {
        const val CHANNEL_ID = "track"
        const val EXTRA_REASON = "reason"
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

        // 车机厂商 ACC 广播的名字是猜的,以实测为准
        val VENDOR_GUESS_ACTIONS = listOf(
            "android.intent.action.ACC_ON",
            "android.intent.action.ACC_OFF",
            "com.android.action.ACC_ON",
            "com.android.action.ACC_OFF",
            "com.android.systemui.ACC_ON",
            "com.android.systemui.ACC_OFF"
        )

        val AMAP_ACTIONS = listOf(AMAP)

        fun start(c: Context, reason: String) {
            val i = Intent(c, TrackService::class.java).putExtra(EXTRA_REASON, reason)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {
                Logger.event(c, "SERVICE_START_FAIL", "reason=$reason $e")
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
    private var amapCount = 0L
    private var amapAtLastBeat = 0L
    private var amapSuppressed = 0
    private val amapLast = HashMap<String, Long>()
    private var lastSleepMs = 0L

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
                amapCount++
                // 按 KEY_TYPE 分别限流(每类最多每 5 秒一条),完整内容写 amap.log
                val type = i.extras?.get("KEY_TYPE")?.toString() ?: "?"
                val now = SystemClock.elapsedRealtime()
                if (now - (amapLast[type] ?: 0L) < 5000) {
                    amapSuppressed++
                    return
                }
                amapLast[type] = now
                Logger.amap(c, "type=$type suppressed=$amapSuppressed ${dump(i, 20000)}")
                amapSuppressed = 0
                return
            }
            // CONNECTIVITY_CHANGE 是 sticky 广播: 刚注册时会立刻收到一条"当前状态",并不代表网络真的变了
            Logger.event(c, "BCAST", "$action ${dump(i, 300)}")
            if (action != CONNECTIVITY) {
                ensureLocation("wake:$action")
                Watchdog.schedule(c)
            }
        }
    }

    private fun dump(i: Intent, max: Int): String {
        val b = i.extras ?: return ""
        return b.keySet().joinToString(", ") { k -> "$k=${b.get(k)}" }.take(max)
    }

    private val beat = object : Runnable {
        override fun run() {
            val el = SystemClock.elapsedRealtime()
            val ago = if (lastFixElapsedMs == 0L) -1L else (el - lastFixElapsedMs) / 1000
            val delta = fixCount - fixesAtLastBeat
            fixesAtLastBeat = fixCount
            val amapDelta = amapCount - amapAtLastBeat
            amapAtLastBeat = amapCount
            Logger.event(
                this@TrackService, "HEARTBEAT",
                "fixes_last_min=$delta total=$fixCount lastFixAgo=${ago}s sats=$satsUsed/$satsTotal " +
                    "amap_msgs=$amapDelta listening=$listening"
            )
            // 休眠检测: elapsedRealtime 含休眠、uptimeMillis 不含,二者之差的增量 = 两次心跳之间睡了多久
            val sleepNow = el - SystemClock.uptimeMillis()
            if (sleepNow - lastSleepMs > 5000) {
                Logger.event(this@TrackService, "SLEPT", "${(sleepNow - lastSleepMs) / 1000}s since last beat")
            }
            lastSleepMs = sleepNow
            if (listening && (ago < 0 || ago > 60)) ensureLocation("stale")
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastSleepMs = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
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
        val reason = when {
            intent == null -> "STICKY-RESTART(null intent)"
            else -> intent.getStringExtra(EXTRA_REASON) ?: "unknown"
        }
        Logger.event(this, "SERVICE_START", "reason=$reason flags=$flags startId=$startId")
        if (!listening) ensureLocation("onStartCommand")
        Watchdog.schedule(this)
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW)
                )
            }
            b = Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            b = Notification.Builder(this)
        }
        val n = b.setContentTitle("CarTracker")
            .setContentText("GPS logging")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun hasLocPerm() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocation(reason: String) {
        if (!hasLocPerm()) {
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
            registerGnss()   // v1 的 bug: 授权晚于服务创建时,GNSS 回调从未注册。这里补上
        } catch (e: Exception) {
            Logger.event(this, "GPS", "request failed ($reason): $e")
        }
    }

    private fun registerGnss() {
        if (gnssCb != null || Build.VERSION.SDK_INT < 24 || !hasLocPerm()) return
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
        if (Build.VERSION.SDK_INT >= 24) {
            try { gnssCb?.let { lm.unregisterGnssStatusCallback(it) } } catch (_: Exception) {}
        }
        gnssCb = null
        try { unregisterReceiver(dynReceiver) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
