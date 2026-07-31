package com.trellis.studio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.trellis.studio.MainActivity
import com.trellis.studio.R
import com.trellis.studio.viewmodel.ModelJob
import com.trellis.studio.viewmodel.ModelQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps 3D generation alive while the app is backgrounded or swiped away.
 *
 * A coroutine on an application scope dies with the process, so the queue
 * stopped the moment the app was killed. A foreground service is what Android
 * requires to keep work — and its network calls — running past that.
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…", 0, 0))

        val queue = ModelQueue.get(application)
        watcher = scope.launch {
            queue.jobs.collectLatest { jobs ->
                val active = jobs.filter {
                    it.status == ModelJob.Status.QUEUED || it.status == ModelJob.Status.RUNNING
                }
                if (active.isEmpty()) {
                    // Nothing left to do — drop the notification and stop.
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                val running = jobs.firstOrNull { it.status == ModelJob.Status.RUNNING }
                val done = jobs.count { it.status == ModelJob.Status.DONE }
                notify(
                    buildNotification(
                        running?.let { "${it.prompt} — ${it.progressLabel}" } ?: "Waiting…",
                        done,
                        jobs.size,
                    )
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if the system kills us mid-queue.
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "3D generation",
            NotificationManager.IMPORTANCE_LOW,   // silent; it's a progress ticker
        ).apply { description = "Shows progress while models are being generated." }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (total > 0) "Generating 3D — $done of $total done" else "Generating 3D")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "trellis_generation"
        private const val NOTIFICATION_ID = 4201

        /** Safe to call repeatedly; the service ignores duplicate starts. */
        fun start(context: Context) {
            val intent = Intent(context, GenerationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
