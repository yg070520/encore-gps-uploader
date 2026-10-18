package com.example.cartracker

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志目录: /sdcard/Android/data/com.example.cartracker/files/
 *   events.log  事件日志(开机、服务、广播、心跳)
 *   gps.csv     GPS 轨迹
 * 行内 up = elapsedRealtime(含休眠), awake = uptime(不含休眠),二者之差 ≈ 休眠时长。
 */
object Logger {
    private const val MAX = 5L * 1024 * 1024
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun dir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    @Synchronized
    private fun file(ctx: Context, name: String): File {
        val f = File(dir(ctx), name)
        if (f.length() > MAX) f.renameTo(File(dir(ctx), "$name.old"))
        return f
    }

    @Synchronized
    private fun raw(f: File, line: String) {
        try {
            FileWriter(f, true).use { it.write(line + "\n") }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun event(ctx: Context, tag: String, msg: String) {
        val up = SystemClock.elapsedRealtime() / 1000
        val awake = SystemClock.uptimeMillis() / 1000
        raw(
            file(ctx, "events.log"),
            "${fmt.format(Date())} [pid=${Process.myPid()} up=${up}s awake=${awake}s] $tag $msg"
        )
        Log.i("CarTracker", "$tag $msg")
    }

    @Synchronized
    fun gps(ctx: Context, line: String) {
        val f = file(ctx, "gps.csv")
        if (!f.exists()) {
            raw(f, "sys_ms,fix_ms,lat,lon,speed_mps,bearing,alt,acc_m,mock,dt_ms,sats_used/total")
        }
        raw(f, line)
    }

    fun clear(ctx: Context) {
        File(dir(ctx), "events.log").delete()
        File(dir(ctx), "gps.csv").delete()
    }

    fun tail(ctx: Context, name: String, maxBytes: Int = 12000): String {
        val f = File(dir(ctx), name)
        if (!f.exists()) return "(empty)"
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val start = maxOf(0L, len - maxBytes)
                raf.seek(start)
                val b = ByteArray((len - start).toInt())
                raf.readFully(b)
                var s = String(b, Charsets.UTF_8)
                if (start > 0) s = s.substringAfter('\n')
                s
            }
        } catch (e: Exception) {
            "(read error: $e)"
        }
    }
}
