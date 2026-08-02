package com.trellis.studio.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The hands of the agent: it turns high-level instructions — tap here, type
 * this, open that app — into the gestures and node actions the OS accepts.
 *
 * Everything runs through the one live [AccessibilityService] instance the
 * system binds, exposed as [instance]. A caller that arrives before the user
 * has switched the service on in Settings gets a `false`/`null` back rather
 * than a crash, so the rest of the app can check [isReady] and prompt.
 *
 * Gestures are asynchronous. Each entry point takes an optional [onResult] so
 * a remote command can report back whether the tap actually dispatched.
 */
class AutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // The service must exist, but it does no work on the event stream itself —
    // commands are pushed in, not pulled from what the user is doing.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ----------------------------------------------------------- tap & gesture

    /** Taps a single point. [onResult] fires true once the gesture dispatches. */
    fun clickAt(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_MS)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onResult)
    }

    /** A press-and-hold, for long-click targets. */
    fun longClickAt(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, LONG_PRESS_MS)
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onResult)
    }

    /** A straight drag from start to end — used for both swipes and scrolls. */
    fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = SWIPE_MS,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 10_000L))
        dispatch(GestureDescription.Builder().addStroke(stroke).build(), onResult)
    }

    /**
     * Scrolls the screen by swiping in the opposite direction. Positive [dy]
     * scrolls the content up (finger moves up), which is what "scroll down" means
     * to a user reading a list.
     */
    fun scroll(dy: Float, onResult: ((Boolean) -> Unit)? = null) {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val midY = metrics.heightPixels / 2f
        swipe(cx, midY + dy / 2f, cx, midY - dy / 2f, onResult = onResult)
    }

    private fun dispatch(gesture: GestureDescription, onResult: ((Boolean) -> Unit)?) {
        // dispatchGesture must be called on the main thread; a WebSocket callback
        // is not on it, so every gesture is posted there.
        main.post {
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) { onResult?.invoke(true) }
                override fun onCancelled(description: GestureDescription?) { onResult?.invoke(false) }
            }, null)
            if (!ok) onResult?.invoke(false)
        }
    }

    // -------------------------------------------------------------- node clicks

    /**
     * Finds a visible clickable node whose text (or content description) contains
     * [text] and clicks it. Returns false if nothing on screen matches.
     *
     * Matching walks up from the found node to the nearest clickable ancestor,
     * because a label is often a child of the button rather than the button.
     */
    fun clickText(text: String, ignoreCase: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNode(root) { node ->
            val hay = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            hay.contains(text, ignoreCase)
        } ?: return false

        var node: AccessibilityNodeInfo? = target
        while (node != null) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            node = node.parent
        }
        // Nothing in the chain declared itself clickable — tap its centre instead.
        val bounds = android.graphics.Rect().also { target.getBoundsInScreen(it) }
        if (bounds.width() > 0 && bounds.height() > 0) {
            clickAt(bounds.exactCenterX(), bounds.exactCenterY())
            return true
        }
        return false
    }

    /**
     * Types [text] into the currently focused input, or the first editable field
     * on screen if nothing is focused. Returns false if there is no field to fill.
     */
    fun typeText(text: String, replace: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findNode(root) { it.isEditable }
            ?: return false

        val existing = field.text?.toString().orEmpty()
        val value = if (replace) text else existing + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Depth-first search for the first node matching [predicate]. */
    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), predicate)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ global & apps

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * Launches an app by package name. Returns false if the package is not
     * installed or exposes no launcher activity.
     */
    fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent) }.isSuccess
    }

    /**
     * Launches an app by a loose name — "chrome", "whatsapp", "godot". The
     * brain rarely knows exact package names, so the label is matched instead,
     * preferring an exact match before a contains. Returns the package it opened,
     * or null if nothing matched.
     */
    fun launchByName(name: String): String? {
        val query = name.trim().lowercase()
        if (query.isEmpty()) return null
        val apps = installedApps()
        val exact = apps.firstOrNull { it.second.equals(query, true) }
        val hit = exact ?: apps.firstOrNull { it.second.contains(query, true) }
            ?: apps.firstOrNull { it.first.contains(query, true) }
            ?: return null
        return if (launchApp(hit.first)) hit.first else null
    }

    /** (package, lowercase label) for every launchable app. */
    private fun installedApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0).map {
            val pkg = it.activityInfo.packageName
            pkg to it.loadLabel(packageManager).toString().lowercase()
        }
    }

    // --------------------------------------------------------- screen reading

    /** One interactable thing on screen, with the point that taps its centre. */
    data class ScreenElement(
        val text: String,
        val cx: Int,
        val cy: Int,
        val clickable: Boolean,
        val editable: Boolean,
    )

    /**
     * The brain's eyes: every labelled or interactable node currently on screen,
     * with the coordinates that would tap it. Without this the model is guessing
     * at blind x/y; with it, it can say "click the node that reads Login".
     */
    fun snapshot(limit: Int = 45): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<ScreenElement>()
        val seen = HashSet<String>()
        collect(root, out, seen, limit)
        return out
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<ScreenElement>,
        seen: MutableSet<String>,
        limit: Int,
    ) {
        if (node == null || out.size >= limit) return
        val label = (node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString())?.trim().orEmpty()
        val useful = node.isVisibleToUser && (label.isNotEmpty() || node.isEditable)
        if (useful) {
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() > 0 && bounds.height() > 0) {
                val shown = label.take(60).ifBlank { if (node.isEditable) "(input field)" else "" }
                val key = "$shown@${bounds.centerX()},${bounds.centerY()}"
                if (shown.isNotEmpty() && seen.add(key)) {
                    out += ScreenElement(
                        text = shown,
                        cx = bounds.centerX(),
                        cy = bounds.centerY(),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                    )
                }
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, seen, limit)
    }

    /** The foreground package, so the brain knows which app it is looking at. */
    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    companion object {
        /** The live service, or null until the user enables it in Settings. */
        @Volatile
        var instance: AutomationService? = null
            private set

        val isReady: Boolean get() = instance != null

        private val main = Handler(Looper.getMainLooper())

        private const val TAP_MS = 60L
        private const val LONG_PRESS_MS = 600L
        private const val SWIPE_MS = 300L
    }
}
