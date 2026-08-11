package com.trellis.studio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.trellis.studio.R
import com.trellis.studio.util.GameBooster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps memory free for games in the background: a foreground service that runs
 * a light boost on an interval and shows the current free RAM in its
 * notification. It's a foreground service so Android doesn't kill it — which
 * also means it's honest and visible (a persistent notification), not a hidden
 * battery drain.
 */
class BoosterService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var intervalMs = DEFAULT_INTERVAL_MIN * 60_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intervalMs = (intent?.getIntExtra(EXTRA_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)
            ?: DEFAULT_INTERVAL_MIN).coerceIn(1, 60) * 60_000L

        ensureChannel()
        startForeground(NOTIF_ID, notification(GameBooster.memory(this), null))

        scope.launch {
            while (isActive) {
                val result = runCatching { GameBooster.boost(this@BoosterService) }.getOrNull()
                notify(result)
                delay(intervalMs)
            }
        }
        return START_STICKY
    }

    private fun notify(result: GameBooster.BoostResult?) {
        val mem = result?.after ?: GameBooster.memory(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification(mem, result?.freedMb))
    }

    private fun notification(mem: GameBooster.Memory, freedMb: Long?): Notification {
        val line = buildString {
            append("${mem.availMb} MB free of ${mem.totalMb} MB (${mem.usedPercent}% used)")
            if (freedMb != null && freedMb > 0) append(" · freed ${freedMb} MB")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Game Booster active")
            .setContentText(line)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Game Booster", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Keeps memory free for games" }
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "game_booster"
        private const val NOTIF_ID = 4711
        const val EXTRA_INTERVAL_MIN = "interval_min"
        const val DEFAULT_INTERVAL_MIN = 5

        fun start(context: Context, intervalMin: Int) {
            val intent = Intent(context, BoosterService::class.java)
                .putExtra(EXTRA_INTERVAL_MIN, intervalMin)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BoosterService::class.java))
        }
    }
}
