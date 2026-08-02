package com.trellis.studio.service

import android.animation.LayoutTransition
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * A draggable bubble that floats over every app, so the agent can be started,
 * stopped and watched without leaving whatever is on screen.
 *
 * The whole UI is built in code — no layout XML — because an overlay window is
 * added straight to the [WindowManager] and never inflated into an Activity.
 * Tapping the bubble expands a small panel with the controls and a live log;
 * dragging it moves the window. A tap and a drag are told apart by how far the
 * finger travelled, so a shaky tap still registers as a tap.
 *
 * Log lines arrive from anywhere via [log]; the newest are kept and shown so a
 * background run is visible at a glance.
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var logView: TextView
    private lateinit var params: WindowManager.LayoutParams

    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        buildUi()
        addToWindow()
        render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LOG)?.let { pushLog(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { windowManager.removeView(root) }
        super.onDestroy()
    }

    // --------------------------------------------------------------------- UI

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition()
        }

        bubble = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            val d = dp(56)
            layoutParams = LinearLayout.LayoutParams(d, d)
            background = circle(0xFF7C3AED.toInt())
            setOnTouchListener(DragTapListener())
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(0xEE12121B.toInt(), dp(14))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }

        val title = TextView(this).apply {
            text = "VOID Agent"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 0, 0, dp(6))
        }

        statusLine = TextView(this).apply {
            text = STATUS_IDLE
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }

        // The headline control: hold-to-talk. Tapping it starts listening, and
        // the spoken instruction is handed straight to the agent.
        val talk = actionButton("🎤  Speak a command", 0xFF7C3AED.toInt()) {
            onStart?.invoke() ?: pushLog("No voice handler connected.")
        }
        val stop = actionButton("Stop agent", 0xFFDC2626.toInt()) {
            onStop?.invoke() ?: pushLog("No stop handler connected.")
        }
        val status = actionButton("Status", 0xFF2563EB.toInt()) {
            pushLog(onStatus?.invoke() ?: statusText)
        }

        // A short, scrolling log window so background actions stay visible.
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120),
            ).apply { topMargin = dp(8) }
            background = rounded(0xFF0A0A12.toInt(), dp(8))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        logView = TextView(this).apply {
            setTextColor(0xFF34D399.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "—"
        }
        logScroll.addView(logView)

        panel.addView(title)
        panel.addView(statusLine)
        panel.addView(talk)
        panel.addView(stop)
        panel.addView(status)
        panel.addView(logScroll)

        root.addView(bubble)
        root.addView(panel)
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = rounded(color, dp(10))
            val v = dp(10)
            setPadding(v, v, v, v)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun addToWindow() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        windowManager.addView(root, params)
    }

    private fun toggle() {
        expanded = !expanded
        render()
    }

    private fun render() {
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        bubble.text = if (expanded) "×" else "AI"
        statusLine.text = statusText
    }

    // ------------------------------------------------------------------ state

    private val logLines = ArrayDeque<String>()
    private var statusText = STATUS_IDLE

    private fun pushLog(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        main.post {
            logLines.addLast("$stamp  $message")
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
        }
    }

    private fun setStatus(text: String) {
        statusText = text
        main.post { if (::statusLine.isInitialized) statusLine.text = text }
    }

    // ------------------------------------------------------- drag / tap gesture

    private inner class DragTapListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(root, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downX) + abs(event.rawY - downY)
                    if (moved < TAP_SLOP) toggle()
                    return true
                }
            }
            return false
        }
    }

    // ---------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VOID Agent")
            .setContentText("Overlay active")
            .setSmallIcon(com.trellis.studio.R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    // ------------------------------------------------------------------ utils

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    companion object {
        @Volatile
        var instance: FloatingOverlayService? = null
            private set

        private val main = Handler(Looper.getMainLooper())

        // Wired by whoever owns the agent — the overlay only reports and requests.
        var onStart: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null
        var onStatus: (() -> String)? = null

        /** Append a line to the live log from anywhere. Safe before onCreate. */
        fun log(message: String) {
            instance?.pushLog(message)
        }

        /** Update the status line the bubble shows. */
        fun status(text: String) {
            instance?.setStatus(text)
        }

        fun isRunning(): Boolean = instance != null

        const val CHANNEL_ID = "void_overlay"
        const val NOTIFICATION_ID = 4202
        const val EXTRA_LOG = "log"
        const val STATUS_IDLE = "Idle · not connected"

        private const val MAX_LOG_LINES = 40
        private const val TAP_SLOP = 24f
    }
}
