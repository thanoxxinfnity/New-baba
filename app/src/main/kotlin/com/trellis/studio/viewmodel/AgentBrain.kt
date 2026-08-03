package com.trellis.studio.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.trellis.studio.audio.VoiceIo
import com.trellis.studio.data.model.ChatTurn
import java.io.File
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.network.NimClient
import com.trellis.studio.service.AutomationService
import com.trellis.studio.service.FloatingOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * VOID's own agent: you tell it what you want in plain words, and it drives the
 * phone to do it — no external server, the app's own LLM is the brain.
 *
 * The loop each step is: read what is on screen, ask the model for the next
 * single action, carry it out, feed the result back, and repeat until the model
 * says it is done or needs to ask you something. Because operating another app
 * means VOID is in the background, the whole thing lives here as a process-wide
 * singleton on its own scope rather than in a screen that would die on rotation.
 *
 * The model answers in a tiny JSON protocol, one action per turn:
 *   {"action":"launch","query":"chrome","say":"Opening Chrome"}
 *   {"action":"click_text","text":"Search"}
 *   {"action":"type_text","text":"hello"}
 *   {"action":"say","message":"..."}   — talk, keep going
 *   {"action":"ask","message":"..."}   — need an answer, pause
 *   {"action":"done","message":"..."}  — finished, pause
 */
object AgentBrain {

    enum class Role { USER, AGENT, ACTION, ERROR }

    data class Message(val role: Role, val text: String, val at: Long = System.currentTimeMillis())

    private val nim = NimClient()
    private val scope = CoroutineScope(SupervisorJob())

    private val _transcript = MutableStateFlow<List<Message>>(emptyList())
    val transcript: StateFlow<List<Message>> = _transcript.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The running goal, so a mid-run message is treated as more input, not a new job. */
    private var job: Job? = null
    private var ttsOn = true

    val isRunning: Boolean get() = job?.isActive == true

    /** Speak agent lines aloud, or not. */
    fun setSpeaking(on: Boolean) { ttsOn = on }

    fun clear() {
        stop()
        _transcript.value = emptyList()
    }

    fun stop() {
        job?.cancel()
        job = null
        _busy.value = false
        VoiceIo.stopSpeaking()
    }

    /**
     * Hands the agent a new instruction. If it is already working, the line is
     * added as the next thing it should take into account.
     */
    fun submit(context: Context, instruction: String) {
        val text = instruction.trim()
        if (text.isEmpty()) return
        add(Role.USER, text)

        // A job already running will pick this up from the transcript on its next
        // turn, so a running agent just keeps going with the new context.
        if (isRunning) return

        val app = context.applicationContext
        job = scope.launch {
            _busy.value = true
            runCatching { runLoop(app) }
                .onFailure { add(Role.ERROR, it.message ?: "The agent hit an error.") }
            _busy.value = false
        }
    }

    // ------------------------------------------------------------------ loop

    private suspend fun runLoop(app: Context) {
        val prefs = AppPrefs(app)
        val apiKey = prefs.nvidiaKey.first()
        if (apiKey.isBlank()) {
            add(Role.ERROR, "Add your NVIDIA API key in Settings first.")
            return
        }
        val model = prefs.agentModel.first()
        val visionModel = prefs.agentVisionModel.first()
        ttsOn = prefs.agentTts.first()

        val service = AutomationService.instance
        if (service == null) {
            add(Role.ERROR, "Turn on the Accessibility service so I can control the phone.")
            return
        }

        // Bring up the floating bubble so the process stays alive once VOID goes
        // to the background, and so progress is visible over the app being driven.
        // No-ops without the overlay permission.
        AgentController.showOverlay(app)

        // The agent is launched from inside VOID, so the first thing on screen is
        // VOID's own chat. Left alone the model just taps its own buttons — Speak,
        // Chat, Terminal — and never touches a real app. Leaving to the home
        // screen first is what makes it operate the phone like a person would.
        if (service.currentPackage() == app.packageName) {
            service.goHome()
            delay(LAUNCH_SETTLE_MS)
        }

        // No step cap: a real task — building a game, filling a long form — can
        // take hundreds of moves, so the agent runs until it is done, needs to
        // ask, gets stuck, or the user stops it. Coroutine cancellation from
        // stop() ends the loop; the stuck and repeated-failure guards below keep
        // it from spinning forever on its own.
        var steps = 0
        var lastFailed = false
        var failStreak = 0
        val recent = ArrayDeque<String>()
        while (coroutineContext.isActive) {
            steps++

            // Choose eyes for this turn. Most apps expose accessibility text, which
            // is fast and exact. An app that draws its own UI — Godot, a game, a
            // canvas — exposes almost nothing, so when the reading is sparse (or
            // the last text-mode action just failed on such a screen) the agent
            // screenshots and a vision model decides where to tap instead.
            val onVoid = service.currentPackage() == app.packageName
            val elementCount = if (onVoid) 1 else service.snapshot(limit = 6).size
            val useVision = !onVoid &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (elementCount < VISION_MIN_ELEMENTS || lastFailed)

            val action = if (useVision) {
                visionAction(app, service, apiKey, visionModel)
            } else {
                val turns = buildTurns(app, service)
                val reply = nim.chat(
                    apiKey = apiKey,
                    model = model,
                    turns = turns,
                    // One JSON action is tiny; a tight cap makes the model stop and
                    // return sooner instead of padding the reply.
                    maxTokens = 200,
                    temperature = 0.2,
                ).getOrElse { err ->
                    add(Role.ERROR, err.message ?: "The model did not respond.")
                    return
                }
                parseAction(reply.content.ifBlank { reply.reasoning.orEmpty() })
            }

            if (action == null) {
                add(Role.ERROR, "I couldn't work out the next step. Try rephrasing.")
                return
            }

            // Anything the model wants to say is shown and spoken before it acts.
            action.say?.takeIf { it.isNotBlank() }?.let { say(it) }

            when (action.type) {
                "say" -> { action.message?.let { say(it) } }
                "ask" -> { action.message?.let { say(it) }; return }   // wait for the user
                "done" -> { action.message?.let { say(it) }; return }  // finished

                "wait" -> delay(action.ms.coerceIn(200L, 5_000L))

                else -> {
                    // Keep the bubble out from under the tap so the gesture lands
                    // on the app, not on the overlay.
                    val isTap = action.type == "click_xy" || action.type == "long_press" ||
                        action.type == "swipe" || action.type == "click_text"
                    if (isTap) { FloatingOverlayService.hideBubble(); delay(60) }
                    val result = execute(service, action)
                    if (isTap) { delay(40); FloatingOverlayService.showBubble() }
                    val failed = result.startsWith("no ") || result.contains("couldn't") ||
                        result.contains("failed")
                    lastFailed = failed
                    failStreak = if (failed) failStreak + 1 else 0
                    add(Role.ACTION, "${describe(action)} → $result")

                    // Two guards, since there is no step cap. The same action over
                    // and over is a tight loop; a long run of failures of any kind
                    // means it has lost the thread. Either hands back to the user
                    // rather than spinning — but ordinary progress runs forever.
                    val signature = "${action.type}|${action.text}|${action.query}|$result"
                    recent.addLast(signature)
                    if (recent.size > STUCK_WINDOW) recent.removeFirst()
                    if (recent.size == STUCK_WINDOW && recent.all { it == signature }) {
                        say("I'm repeating the same step without progress. Tell me what to do differently, or open the right screen and I'll carry on.")
                        return
                    }
                    if (failStreak >= MAX_FAIL_STREAK) {
                        say("The last several steps didn't land. I'll pause — say \"carry on\" once the screen looks right, or tell me the next move.")
                        return
                    }

                    // A short beat for the UI to settle before the next screen
                    // read. Opening a whole app needs longer than a tap, so the
                    // pause is matched to the action rather than fixed.
                    delay(if (action.type == "launch") LAUNCH_SETTLE_MS else SETTLE_MS)
                }
            }
        }
    }

    /** Builds the model input: the rules, the conversation, and the live screen. */
    private fun buildTurns(app: Context, service: AutomationService): List<ChatTurn> {
        val turns = ArrayList<ChatTurn>()
        turns += ChatTurn("system", SYSTEM_PROMPT)

        // The conversation so far, trimmed to the recent past so the context does
        // not grow without bound over a long task.
        _transcript.value.takeLast(MAX_HISTORY).forEach { m ->
            when (m.role) {
                Role.USER -> turns += ChatTurn("user", m.text)
                Role.AGENT -> turns += ChatTurn("assistant", m.text)
                Role.ACTION -> turns += ChatTurn("assistant", "[did] ${m.text}")
                Role.ERROR -> turns += ChatTurn("assistant", "[error] ${m.text}")
            }
        }

        turns += ChatTurn("user", screenReport(app, service))
        return turns
    }

    /** A compact description of what is on screen right now. */
    private fun screenReport(app: Context, service: AutomationService): String {
        val pkg = service.currentPackage() ?: "unknown"

        // Never let the model operate VOID itself. If we are looking at our own
        // app, do not even list its buttons — tell the model to leave. This is
        // what stops it tapping Speak/Chat/Terminal in a loop.
        if (pkg == app.packageName) {
            return "You are on VOID's own screen (the app running you). Do NOT tap " +
                "anything here. If the task names an app to open, do it NOW with " +
                "{\"action\":\"launch\",\"query\":\"<app name>\"}. Otherwise go to the " +
                "home screen with {\"action\":\"home\"}. Give the next action as JSON."
        }

        // Fewer, most-relevant elements keep the prompt short so the model
        // answers faster; 28 covers a normal screen's interactive parts.
        val elements = service.snapshot(limit = 28)
        val lines = elements.joinToString("\n") { e ->
            val kind = when {
                e.editable -> "input"
                e.clickable -> "button"
                else -> "text"
            }
            "- \"${e.text}\" [$kind] at (${e.cx},${e.cy})"
        }.ifBlank { "- (no labelled elements read)" }
        return "CURRENT SCREEN (app: $pkg):\n$lines\n\nGive the next single action as JSON."
    }

    // ---------------------------------------------------------------- vision

    /**
     * Decides the next action from a screenshot, for apps that expose no
     * accessibility text. The bubble is folded away and hidden first so it is
     * neither in the picture nor over the tap, then a vision model is asked for
     * the next move with pixel coordinates in the image — which are scaled back
     * to real screen pixels before the gesture runs.
     */
    private suspend fun visionAction(
        app: Context,
        service: AutomationService,
        apiKey: String,
        visionModel: String,
    ): Action? {
        FloatingOverlayService.collapse()
        FloatingOverlayService.hideBubble()
        delay(140)   // let the overlay leave the frame before capturing
        val shot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) service.takeShot() else null
        FloatingOverlayService.showBubble()
        if (shot == null) {
            add(Role.ERROR, "Couldn't capture the screen (needs Android 11+).")
            return null
        }

        // Downscale for token cost; keep the factor to map coordinates back.
        val scale = if (shot.width > VISION_MAX_WIDTH) VISION_MAX_WIDTH.toFloat() / shot.width else 1f
        val outW = (shot.width * scale).toInt().coerceAtLeast(1)
        val outH = (shot.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(shot, outW, outH, true) else shot
        val file = File(app.cacheDir, "agent_shot.jpg")
        runCatching {
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }.onFailure { add(Role.ERROR, "Couldn't save the screenshot."); return null }
        val backX = shot.width.toFloat() / outW
        val backY = shot.height.toFloat() / outH
        if (scaled !== shot) scaled.recycle()
        shot.recycle()

        val prompt = "GOAL: ${lastUserGoal()}\n${recentContext()}\n" +
            "The screenshot is ${outW}x${outH} pixels. Decide the next single action. " +
            "For taps and swipes give x,y in THIS image's pixels. Reply with ONE JSON object."
        val reply = nim.chat(
            apiKey = apiKey,
            model = visionModel,
            turns = listOf(
                ChatTurn("system", VISION_PROMPT),
                ChatTurn("user", prompt, imagePath = file.absolutePath),
            ),
            maxTokens = 250,
            temperature = 0.2,
            isVisionModel = true,
        ).getOrElse { add(Role.ERROR, it.message ?: "The vision model didn't respond."); return null }

        val a = parseAction(reply.content.ifBlank { reply.reasoning.orEmpty() }) ?: return null
        // Map image pixels back to screen pixels for anything that taps.
        return if (a.type == "click_xy" || a.type == "long_press" || a.type == "swipe") {
            a.copy(x = a.x * backX, y = a.y * backY, x2 = a.x2 * backX, y2 = a.y2 * backY)
        } else a
    }

    /** The most recent thing the user asked for. */
    private fun lastUserGoal(): String =
        _transcript.value.lastOrNull { it.role == Role.USER }?.text ?: "help with the current screen"

    /** A couple of recent actions so the vision model has continuity. */
    private fun recentContext(): String {
        val done = _transcript.value.filter { it.role == Role.ACTION }.takeLast(3)
        return if (done.isEmpty()) "" else "Recently: " + done.joinToString("; ") { it.text }
    }

    // --------------------------------------------------------------- execute

    private fun execute(service: AutomationService, a: Action): String = when (a.type) {
        "click_text" -> if (service.clickText(a.text.orEmpty())) "clicked" else "no match for \"${a.text}\""
        "click_xy" -> { service.clickAt(a.x, a.y); "tapped (${a.x.toInt()},${a.y.toInt()})" }
        "long_press" -> { service.longClickAt(a.x, a.y); "long-pressed" }
        "type_text" -> if (service.typeText(a.text.orEmpty())) "typed" else "no input field focused"
        "swipe" -> { service.swipe(a.x, a.y, a.x2, a.y2); "swiped" }
        "scroll" -> { service.scroll(a.dy); "scrolled" }
        "launch" -> {
            val pkg = a.packageName?.let { if (service.launchApp(it)) it else null }
                ?: a.query?.let { service.launchByName(it) }
            if (pkg != null) "opened $pkg" else "couldn't find that app"
        }
        "home" -> if (service.goHome()) "home" else "failed"
        "back" -> if (service.goBack()) "back" else "failed"
        "recents" -> if (service.openRecents()) "recents" else "failed"
        "notifications" -> if (service.openNotifications()) "notifications" else "failed"
        else -> "unknown action \"${a.type}\""
    }

    private fun describe(a: Action): String = when (a.type) {
        "click_text" -> "Tap \"${a.text}\""
        "click_xy" -> "Tap (${a.x.toInt()},${a.y.toInt()})"
        "type_text" -> "Type \"${a.text}\""
        "launch" -> "Open ${a.query ?: a.packageName}"
        "swipe" -> "Swipe"
        "scroll" -> "Scroll"
        else -> a.type
    }

    // ----------------------------------------------------------------- output

    private fun say(text: String) {
        add(Role.AGENT, text)
        if (ttsOn) VoiceIo.speak(text)
    }

    private fun add(role: Role, text: String) {
        // With no step cap a run can produce thousands of lines; keep the visible
        // transcript to a rolling tail so a long build doesn't grow memory without
        // bound. The model's own context is trimmed separately in buildTurns.
        val next = _transcript.value + Message(role, text)
        _transcript.value = if (next.size > MAX_TRANSCRIPT) next.takeLast(MAX_TRANSCRIPT) else next
        FloatingOverlayService.log(
            when (role) {
                Role.USER -> "you: $text"
                Role.AGENT -> text
                Role.ACTION -> "· $text"
                Role.ERROR -> "! $text"
            }
        )
        if (role == Role.AGENT) FloatingOverlayService.status(text.take(40))
    }

    // ------------------------------------------------------- action parsing

    private data class Action(
        val type: String,
        val text: String? = null,
        val message: String? = null,
        val say: String? = null,
        val query: String? = null,
        val packageName: String? = null,
        val x: Float = 0f,
        val y: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
        val dy: Float = 0f,
        val ms: Long = 0L,
    )

    /**
     * Pulls the JSON object out of the model's reply and reads the action from
     * it. Brace-counting rather than a regex: a chat model wraps JSON in prose,
     * code fences and stray braces, and Android's regex engine has rejected
     * patterns the JVM accepted before — so this scans for the first balanced
     * object and ignores everything around it.
     */
    /** Test-only view of the parser, so the messy shapes models emit are covered. */
    internal fun debugParse(raw: String): Map<String, String>? {
        val a = parseAction(raw) ?: return null
        return mapOf(
            "action" to a.type,
            "text" to a.text.orEmpty(),
            "query" to a.query.orEmpty(),
            "say" to a.say.orEmpty(),
            "message" to a.message.orEmpty(),
            "x" to a.x.toInt().toString(),
            "y" to a.y.toInt().toString(),
        )
    }

    private fun parseAction(raw: String): Action? {
        val obj = extractJson(raw) ?: return null
        val map = flatten(obj)
        val type = map["action"]?.lowercase()?.trim() ?: return null
        return Action(
            type = type,
            text = map["text"],
            message = map["message"] ?: map["say"],
            say = map["say"],
            query = map["query"] ?: map["app"] ?: map["name"],
            packageName = map["package"],
            x = map["x"]?.toFloatOrNull() ?: 0f,
            y = map["y"]?.toFloatOrNull() ?: 0f,
            x2 = map["endx"]?.toFloatOrNull() ?: map["x2"]?.toFloatOrNull() ?: 0f,
            y2 = map["endy"]?.toFloatOrNull() ?: map["y2"]?.toFloatOrNull() ?: 0f,
            dy = map["dy"]?.toFloatOrNull() ?: 0f,
            ms = map["ms"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** A forgiving flat key→value read of a single-level JSON object. */
    private fun flatten(jsonObject: String): Map<String, String> {
        val out = HashMap<String, String>()
        val body = jsonObject.trim().removePrefix("{").removeSuffix("}")
        var i = 0
        while (i < body.length) {
            val keyStart = body.indexOf('"', i)
            if (keyStart < 0) break
            val keyEnd = body.indexOf('"', keyStart + 1)
            if (keyEnd < 0) break
            val key = body.substring(keyStart + 1, keyEnd).lowercase()
            val colon = body.indexOf(':', keyEnd + 1)
            if (colon < 0) break

            var j = colon + 1
            while (j < body.length && body[j].isWhitespace()) j++
            if (j >= body.length) break

            val value: String
            if (body[j] == '"') {
                val vEnd = findStringEnd(body, j + 1)
                value = body.substring(j + 1, vEnd)
                    .replace("\\\"", "\"").replace("\\n", " ").replace("\\", "")
                i = vEnd + 1
            } else {
                var vEnd = j
                while (vEnd < body.length && body[vEnd] != ',' && body[vEnd] != '}') vEnd++
                value = body.substring(j, vEnd).trim()
                i = vEnd + 1
            }
            out[key] = value
        }
        return out
    }

    private fun findStringEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '\\') { i += 2; continue }
            if (s[i] == '"') return i
            i++
        }
        return s.length
    }

    // A shorter window keeps the prompt small, which lowers first-token latency —
    // the recent past is what matters for the next tap, not the whole run.
    private const val MAX_HISTORY = 14
    private const val SETTLE_MS = 250L
    private const val LAUNCH_SETTLE_MS = 650L
    /** Identical action this many times in a row means it is stuck, not working. */
    private const val STUCK_WINDOW = 3
    /** This many failed actions in a row means it has lost the thread; hand back. */
    private const val MAX_FAIL_STREAK = 8
    /** Rolling cap on the on-screen transcript so a long run stays bounded. */
    private const val MAX_TRANSCRIPT = 400
    /** Below this many readable elements, the screen is treated as custom-drawn. */
    private const val VISION_MIN_ELEMENTS = 2
    /** Screenshots are downscaled to this width before going to the vision model. */
    private const val VISION_MAX_WIDTH = 1080

    private val SYSTEM_PROMPT = """
        You are VOID Agent, controlling a real Android phone for the user through an
        accessibility service. You can genuinely tap, type, scroll and open apps —
        this is not a simulation.

        You operate OTHER apps — Chrome, WhatsApp, Settings, Godot, whatever the
        user names. You are launched from inside the VOID app (package
        com.trellis.studio). NEVER operate VOID itself: do not tap its buttons
        (Chat, Create, Terminal, Speak, Run, Send, menu, etc.) and do not type into
        its boxes. If the screen is VOID, your only moves are "launch" the target
        app or "home".

        To OPEN an app, ALWAYS use the launch action with the app's name:
        {"action":"launch","query":"chrome"}. Never open an app by typing its name
        into a search box or a terminal — launch is the only correct way.

        Each turn you are given the CURRENT SCREEN: the app package and a list of
        elements with their on-screen text and tap coordinates. Decide the SINGLE
        next action and reply with ONE JSON object, nothing else. No prose, no code
        fences, no explanation outside the JSON.

        Actions:
        {"action":"launch","query":"chrome","say":"Opening Chrome"}   open an app by name
        {"action":"click_text","text":"Search"}                        tap the element with this text
        {"action":"click_xy","x":540,"y":1200}                         tap a coordinate
        {"action":"long_press","x":540,"y":1200}                       press and hold
        {"action":"type_text","text":"hello world"}                    type into the focused field
        {"action":"swipe","x":540,"y":1600,"endX":540,"endY":600}      swipe / drag
        {"action":"scroll","dy":-900}                                  scroll (negative = down the page)
        {"action":"back"} {"action":"home"} {"action":"recents"} {"action":"notifications"}
        {"action":"wait","ms":800}                                     wait for the screen to change
        {"action":"say","message":"..."}                               tell the user something, keep going
        {"action":"ask","message":"..."}                               ask a question, then stop and wait
        {"action":"done","message":"..."}                              the task is finished, stop

        Rules:
        - Prefer click_text over click_xy when the target has a label; only use raw
          coordinates when nothing readable matches.
        - Do exactly one action per turn. After it runs you get a fresh screen.
        - If the screen has not changed the way you expected, look again and adapt —
          do not repeat the same failing action.
        - Keep "say" lines short and natural, in the same language the user used
          (Hindi/Hinglish is fine).
        - When the user's goal is achieved, reply with "done".
        - If you are unsure what the user wants, "ask" instead of guessing.
    """.trimIndent()

    private val VISION_PROMPT = """
        You are VOID Agent, controlling a real Android phone. You are shown a
        SCREENSHOT of an app that draws its own interface (like the Godot game
        engine), so there is no element list — you must read the picture and pick
        where to act by pixel coordinates in the image you are given.

        Reply with ONE JSON object, nothing else:
        {"action":"click_xy","x":640,"y":360,"say":"Tapping New"}   tap a point
        {"action":"long_press","x":640,"y":360}                     press and hold
        {"action":"type_text","text":"MyGame"}                      type into the focused field
        {"action":"swipe","x":600,"y":1500,"endX":600,"endY":500}   swipe / drag
        {"action":"scroll","dy":-800}                               scroll (negative = down)
        {"action":"back"} {"action":"home"}                         navigation
        {"action":"wait","ms":800}                                  wait for it to change
        {"action":"say","message":"..."}                            tell the user, keep going
        {"action":"ask","message":"..."}                            ask, then stop
        {"action":"done","message":"..."}                           finished

        Rules:
        - Coordinates are pixels in the screenshot you were given. Aim for the
          CENTRE of the button, icon or menu item you want.
        - To press a button, click_xy on it. To type into a box, first click_xy on
          the box, then on the next turn type_text.
        - Do exactly one action per turn; you get a fresh screenshot after each.
        - If the same thing hasn't changed after a tap, look again and adjust your
          coordinates rather than repeating them.
        - Keep "say" short, in the user's language. "done" when the goal is met.
    """.trimIndent()
}
