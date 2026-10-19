package com.example.cartracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest 静态注册的触发器。日志里 PROCESS_START 之后紧跟的 TRIGGER 行,就是把进程拉起来的那个广播。 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Logger.event(c, "TRIGGER", "action=${i.action}")
        TrackService.start(c, "trigger:${i.action}")
        Watchdog.schedule(c)
    }
}
