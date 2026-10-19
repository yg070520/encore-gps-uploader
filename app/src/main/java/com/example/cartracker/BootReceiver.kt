package com.example.cartracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Logger.event(c, "BOOT_RECEIVER", "action=${i.action}")
        TrackService.start(c, "boot:${i.action}")
        Watchdog.schedule(c)
    }
}
