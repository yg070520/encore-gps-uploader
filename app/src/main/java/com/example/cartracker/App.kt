package com.example.cartracker

import android.app.Application
import android.os.Process

/** 每次进程被创建都会记一行。PID 变了 = 进程被杀过/冷启动过。 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.event(this, "PROCESS_START", "pid=${Process.myPid()}")
    }
}
