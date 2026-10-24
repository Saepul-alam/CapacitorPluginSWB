package com.wahana.swbplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences(SwbLocationService.PREFS_NAME, Context.MODE_PRIVATE)
            val restartOnBoot = prefs.getBoolean("restartOnBoot", false)
            val wasRunning = prefs.getBoolean(SwbLocationService.KEY_IS_RUNNING, false)

            if (restartOnBoot && wasRunning) {
                Log.d("BootReceiver", "Restarting SwbLocationService after boot / app replace")
                val serviceIntent = Intent(context, SwbLocationService::class.java).apply {
                    this.action = SwbLocationService.ACTION_START_SERVICE
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to restart SwbLocationService: ${e.message}")
                }
            }
        }
    }
}

