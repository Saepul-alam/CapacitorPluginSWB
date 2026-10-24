package com.wahana.swbplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service that anchors the app process in memory (oom_score_adj <= 200).
 * Prevents Android Low Memory Killer (LMK) from killing the application while heavy
 * external activities (such as camera capture, video recording, or file picker) are active.
 */
class SwbKeepAliveService : Service() {

    companion object {
        private const val TAG = "SwbKeepAliveService"
        const val CHANNEL_ID = "swb_keep_alive_channel"
        const val NOTIFICATION_ID = 9102

        const val ACTION_START = "com.wahana.swbplugin.ACTION_START_KEEP_ALIVE"
        const val ACTION_STOP = "com.wahana.swbplugin.ACTION_STOP_KEEP_ALIVE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, title: String? = null, text: String? = null) {
            try {
                val intent = Intent(context, SwbKeepAliveService::class.java).apply {
                    action = ACTION_START
                    title?.let { putExtra(EXTRA_TITLE, it) }
                    text?.let { putExtra(EXTRA_TEXT, it) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start SwbKeepAliveService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, SwbKeepAliveService::class.java).apply {
                    action = ACTION_STOP
                }
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop SwbKeepAliveService: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Wahana Express"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Aplikasi aktif di latar belakang"

        val notification = buildNotification(title, text)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground with dataSync failed: ${e.message}, retrying generic startForeground")
            try {
                startForeground(NOTIFICATION_ID, notification)
                isRunning = true
            } catch (e2: Exception) {
                Log.e(TAG, "Generic startForeground also failed: ${e2.message}")
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            stopForeground(true)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Aplikasi Wahana",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga proses aplikasi tetap aktif di latar belakang"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val iconRes = try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            if (info.icon != 0) info.icon else android.R.drawable.ic_dialog_info
        } catch (_: Exception) {
            android.R.drawable.ic_dialog_info
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

