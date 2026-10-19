package com.example.cartracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var tv: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { f() }
        }

        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        row.addView(btn("启动服务") {
            TrackService.start(this, "button")
            Watchdog.schedule(this)
        }, lp())
        row.addView(btn("停止服务") {
            Logger.event(this, "USER_STOP", "")
            Watchdog.cancel(this)
            TrackService.stop(this)
        }, lp())
        row.addView(btn("电池白名单") { requestBatteryWhitelist() }, lp())
        row.addView(btn("清空日志") { Logger.clear(this) }, lp())
        root.addView(row)

        tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        requestLocationPermission()
        TrackService.start(this, "activity")
        Watchdog.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun hasLocPerm() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        if (!hasLocPerm()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        Logger.event(this, "PERMISSION", "location granted=${hasLocPerm()}")
        TrackService.start(this, "permission")   // 触发 onStartCommand,重新挂定位监听
    }

    private fun requestBatteryWhitelist() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Logger.event(this, "BATTERY", "cannot open settings: $e2")
            }
        }
    }

    private fun lastLines(s: String, n: Int) = s.trimEnd().lines().takeLast(n).joinToString("\n")

    private fun refresh() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ago = if (TrackService.lastFixElapsedMs == 0L) "-"
        else "${(android.os.SystemClock.elapsedRealtime() - TrackService.lastFixElapsedMs) / 1000}s"
        val header = "服务运行=${TrackService.running}  定位监听=${TrackService.listening}  " +
            "定位权限=${hasLocPerm()}  电池白名单=${pm.isIgnoringBatteryOptimizations(packageName)}\n" +
            "fix 总数=${TrackService.fixCount}  距上次 fix=$ago\n" +
            "日志目录: ${Logger.dir(this)}\n"
        tv.text = header +
            "\n==== gps.csv (last 8) ====\n" + lastLines(Logger.tail(this, "gps.csv", 4000), 8) +
            "\n\n==== events.log (last 40) ====\n" + lastLines(Logger.tail(this, "events.log"), 40)
    }
}
