package com.trellis.studio.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.trellis.studio.R
import com.trellis.studio.util.GameBooster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny draggable HUD that floats over your game: live RAM% and the clock, in a
 * neon chip. Tap it to run a quick boost. It's a normal system overlay (the app
 * already holds the "draw over other apps" permission for the automation bubble).
 */
class GamingHudService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var chip: LinearLayout? = null
    private var label: TextView? = null
    private val clockFmt = SimpleDateFormat("h:mm", Locale.getDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIF_ID, notification())

        if (chip == null) addChip()
        scope.launch {
            while (isActive) {
                updateLabel()
                delay(1500)
            }
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addChip() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "…"
        }
        label = text
        val pad = dp(10)
        val padV = dp(6)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, padV, pad, padV)
            background = pill()
            addView(text)
        }
        chip = container

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12); y = dp(80)
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        container.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    runCatching { wm?.updateViewLayout(container, params) }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) scope.launch {
                        text.text = "Boosting…"
                        val r = withContext(Dispatchers.Default) { GameBooster.boost(this@GamingHudService) }
                        text.text = "+${r.freedMb}MB"
                        delay(1200); updateLabel()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm?.addView(container, params) }
    }

    private suspend fun updateLabel() {
        val mem = withContext(Dispatchers.Default) { GameBooster.memory(this@GamingHudService) }
        label?.text = "⚡ ${mem.usedPercent}%  ·  ${clockFmt.format(Date())}"
    }

    private fun pill(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#CC13131C"))
            setStroke(dp(1), Color.parseColor("#5538BDF8"))
        }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Gaming HUD active")
            .setContentText("Floating RAM meter over your games")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Gaming HUD", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { chip?.let { wm?.removeView(it) } }
        chip = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gaming_hud"
        private const val NOTIF_ID = 4712

        fun start(context: Context) {
            val i = Intent(context, GamingHudService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) = context.stopService(Intent(context, GamingHudService::class.java))
    }
}
