package com.sandy.cartracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i("BootReceiver", "Boot completed - starting GpsService");
            Intent s = new Intent(context, GpsService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(s);
                } else {
                    context.startService(s);
                }
            } catch (Exception e) {
                Log.e("BootReceiver", "Failed to start service", e);
            }
        }
    }
}
