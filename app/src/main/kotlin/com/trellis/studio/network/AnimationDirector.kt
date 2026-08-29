package com.trellis.studio.network

import com.trellis.studio.data.model.ChatTurn
import com.trellis.studio.util.AutoRigger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns "make the dog walk and wag its tail" into bone keyframes.
 *
 * There is no AI model on this account that outputs animation — checked against
 * the live NVCF function list, the only motion-adjacent ones understand or
 * detect video. What the account does have is capable language models, and
 * choreography is a language problem: which bone, which axis, how far, how out
 * of step with the others. So the LLM writes the score and [AutoRigger] plays
 * it against a skeleton fitted to the actual mesh.
 *
 * Everything coming back is treated as untrusted: unknown bones are dropped,
 * every number is clamped, and a reply that yields nothing usable is reported
 * as a failure rather than written out as a still model.
 */
class AnimationDirector(private val nim: NimClient = NimClient()) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun choreograph(
        apiKey: String,
        model: String,
        prompt: String,
        /** What the model is, so the LLM knows whether it has legs or wheels. */
        subject: String,
    ): Result<AutoRigger.MotionSpec> {
        if (prompt.isBlank()) {
            return Result.failure(Exception("Describe what the model should do."))
        }

        val turns = listOf(
            ChatTurn("system", SYSTEM_PROMPT),
            ChatTurn("user", "Model: \"$subject\"\nAnimation wanted: \"${prompt.trim()}\""),
        )

        val first = attempt(apiKey, model, turns, prompt)
        if (first.isSuccess || model == FALLBACK_MODEL) return first

        // Whichever model is picked for chat also gets asked for the animation,
        // and not all of them can hold a JSON shape: measured across the whole
        // catalogue, a reasoning model spent its entire budget thinking and
        // returned empty content. Rather than blame the user for their model
        // choice, ask one that reliably answers in JSON.
        return attempt(apiKey, FALLBACK_MODEL, turns, prompt).recoverCatching { retryError ->
            throw Exception(
                "\"$model\" didn't return a usable animation, and the fallback failed too: " +
                    "${retryError.message}"
            )
        }
    }

    private suspend fun attempt(
        apiKey: String,
        model: String,
        turns: List<ChatTurn>,
        prompt: String,
    ): Result<AutoRigger.MotionSpec> =
        // Reasoning models spend tokens before they write anything, so the budget
        // has to cover the thinking as well as the answer.
        nim.chat(apiKey, model, turns, maxTokens = MAX_TOKENS, temperature = 0.4)
            .mapCatching { reply ->
                // Some models put the JSON only in their reasoning trace, and some
                // return prose in `content` with the real answer behind it.
                val candidates = listOfNotNull(reply.content, reply.reasoning)
                candidates.firstNotNullOfOrNull { text ->
                    runCatching { parse(text, prompt) }.getOrNull()
                } ?: parse(reply.content, prompt)   // rethrow the real reason
            }

    /**
     * Pulls the JSON object out of a reply that may be wrapped in prose or fences.
     *
     * Deliberately no regex. The pattern this replaced used a literal `}`, which
     * the JVM accepts and Android's ICU engine rejects outright — so every unit
     * test passed while the feature threw "Syntax error in regexp pattern" on a
     * real phone. Brace counting is also simply more correct: it stops at the
     * first complete object instead of running to the last `}` in the reply.
     */
    private fun extractJson(reply: String): String {
        val start = reply.indexOf('{')
        if (start < 0) {
            throw Exception("The model didn't return an animation. Try describing the motion more plainly.")
        }

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until reply.length) {
            val c = reply[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return reply.substring(start, i + 1)
                }
            }
        }
        // Unbalanced — usually the reply was cut off mid-object.
        throw Exception("The model's animation plan was cut off. Try again.")
    }

    /**
     * Strips the two things models emit that strict JSON forbids: `//` comments
     * and trailing commas. Both are common because the example in the system
     * prompt is annotated, and models copy the style they are shown.
     */
    private fun tidy(raw: String): String {
        val withoutComments = raw.lineSequence().joinToString("\n") { line ->
            var inString = false
            var escaped = false
            var cut = line.length
            for (i in line.indices) {
                val c = line[i]
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    // Only outside a string: a URL inside one must survive.
                    !inString && c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                        cut = i; return@joinToString line.substring(0, cut)
                    }
                }
            }
            line.substring(0, cut)
        }
        return dropTrailingCommas(withoutComments)
    }

    /** Removes a comma that sits before a closing brace or bracket. */
    private fun dropTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (!inString && c == ',') {
                // Look past whitespace: a comma followed by a close is illegal JSON.
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) continue
            }
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun parse(reply: String, prompt: String): AutoRigger.MotionSpec {
        val raw = extractJson(reply)
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .recoverCatching { json.parseToJsonElement(tidy(raw)).jsonObject }
            .getOrElse { throw Exception("The model's animation plan was unreadable. Try again.") }

        val frame = when (root["frame"]?.jsonPrimitive?.content?.uppercase()) {
            "HUMANOID", "BIPED", "TWO_LEGS" -> AutoRigger.Frame.HUMANOID
            "QUADRUPED", "ANIMAL", "FOUR_LEGS" -> AutoRigger.Frame.QUADRUPED
            "VEHICLE", "WHEELS", "CAR" -> AutoRigger.Frame.VEHICLE
            else -> throw Exception("The model couldn't tell what shape this is. Pick a rig by hand instead.")
        }

        val known = AutoRigger.boneNames(frame).map { it.lowercase() }.toSet()
        val tracks = root["tracks"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val t = entry.jsonObject
            val bone = t["bone"]?.jsonPrimitive?.content?.trim().orEmpty()
            val isWheelGroup = bone.equals("wheels", true) || bone.equals("wheel", true)
            // A bone this skeleton does not have is dropped: bending something
            // that isn't there is worse than leaving it still.
            if (bone.isBlank()) return@mapNotNull null
            if (!isWheelGroup && bone.lowercase() !in known) return@mapNotNull null

            AutoRigger.Track(
                bone = bone,
                axis = t["axis"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: "x",
                amplitude = t["amplitude"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null,
                phase = t["phase"]?.jsonPrimitive?.floatOrNull ?: 0f,
                wave = t["wave"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: "sine",
                offset = t["offset"]?.jsonPrimitive?.floatOrNull ?: 0f,
                cycles = t["cycles"]?.jsonPrimitive?.floatOrNull ?: 1f,
            )
        }

        if (tracks.isEmpty()) {
            throw Exception(
                "That description didn't map onto any bones this model has. " +
                    "Try naming the parts — legs, wheels, head, tail."
            )
        }

        val name = root["name"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 48 }
            ?: prompt.trim().take(40)

        return AutoRigger.MotionSpec(
            name = name,
            frame = frame,
            seconds = root["seconds"]?.jsonPrimitive?.floatOrNull ?: 1.4f,
            tracks = tracks,
            bob = root["bob"]?.jsonPrimitive?.floatOrNull ?: 0f,
            bobCycles = root["bobCycles"]?.jsonPrimitive?.floatOrNull ?: 2f,
        )
    }

    private companion object {
        /** Enough for a reasoning model to think and still answer. */
        const val MAX_TOKENS = 4096

        /**
         * Asked when the chosen model cannot produce JSON. Picked by measurement,
         * not preference: it returned valid JSON on every attempt.
         */
        const val FALLBACK_MODEL = "openai/gpt-oss-20b"

        // The bone lists are spelled out because the model has to pick from them
        // exactly; anything invented gets dropped on the way back in.
        val SYSTEM_PROMPT = """
            You are a 3D animation director. You choreograph looping animations by
            writing keyframe tracks for a skeleton. Reply with ONE JSON object and
            nothing else — no prose, no explanation.

            Pick the frame that matches the model:
              HUMANOID  bones: hips, spine, chest, head,
                        l_shoulder, l_elbow, l_hand, l_thigh, l_knee, l_foot,
                        r_shoulder, r_elbow, r_hand, r_thigh, r_knee, r_foot
              QUADRUPED bones: hips, spine, chest, head,
                        l_front_upper, l_front_lower, l_front_paw,
                        r_front_upper, r_front_lower, r_front_paw,
                        l_rear_upper, l_rear_lower, l_rear_paw,
                        r_rear_upper, r_rear_lower, r_rear_paw
              VEHICLE   bones: body, l_front_wheel, r_front_wheel,
                        l_rear_wheel, r_rear_wheel, or "wheels" for all four

            Reply with exactly this shape, and no comments inside the JSON:
            {"name":"short title","frame":"HUMANOID","seconds":1.2,"bob":0.02,"bobCycles":2,
             "tracks":[{"bone":"l_thigh","axis":"x","amplitude":0.45,"phase":0.0,"wave":"sine","offset":0.0,"cycles":1}]}

            What each field means:
              frame      HUMANOID, QUADRUPED or VEHICLE
              seconds    length of one loop, 0.4 to 8
              bob        whole body rising and falling, 0 to 0.1
              bone       must come from the list above
              axis       x = swing forward/back, y = turn left/right, z = tilt sideways
              amplitude  radians; keep under 1.2 for limbs
              phase      0..1, offsets this bone within the loop
              wave       sine = both ways, half = one way only, spin = full turns
              offset     constant rotation added on top
              cycles     repeats per loop

            Rules that make it read as real motion:
            - Opposite limbs are half a loop apart: phase 0 on one side, 0.5 on the other.
            - Knees and elbows only fold one way. Use "half" with a negative amplitude.
            - Arms swing opposite the legs on the same side.
            - A four-legged walk is diagonal: left-front with right-rear, right-front with left-rear.
            - Wheels use "spin" with amplitude 1 on axis x. Faster driving = fewer seconds.
            - A wagging tail or shaking head is fast and small: cycles 2-4, amplitude 0.15-0.4.
            - Only use bones from the frame you chose. Never invent a bone name.
        """.trimIndent()
    }
}
