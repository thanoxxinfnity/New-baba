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

        return nim.chat(apiKey, model, turns, maxTokens = 1600, temperature = 0.4)
            .mapCatching { reply -> parse(reply.content, prompt) }
    }

    /** Pulls the JSON object out of a reply that may be wrapped in prose or fences. */
    private fun extractJson(reply: String): String {
        val fenced = Regex("```(?:json)?\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(reply)?.groupValues?.get(1)
        if (fenced != null) return fenced
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start >= 0 && end > start) return reply.substring(start, end + 1)
        throw Exception("The model didn't return an animation. Try describing the motion more plainly.")
    }

    private fun parse(reply: String, prompt: String): AutoRigger.MotionSpec {
        val root = runCatching { json.parseToJsonElement(extractJson(reply)).jsonObject }
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

            Shape:
            {
              "name": "short title",
              "frame": "HUMANOID" | "QUADRUPED" | "VEHICLE",
              "seconds": 0.4 to 8,
              "bob": 0 to 0.1,          // whole body rising and falling
              "bobCycles": 1 to 4,
              "tracks": [
                {
                  "bone": "l_thigh",
                  "axis": "x",          // x = swing forward/back, y = turn left/right, z = tilt sideways
                  "amplitude": 0.45,    // radians, keep under 1.2 for limbs
                  "phase": 0.0,         // 0..1, offsets this bone within the loop
                  "wave": "sine",       // sine = both ways, half = one way only, spin = full turns
                  "offset": 0.0,        // constant rotation added on top
                  "cycles": 1           // repeats per loop
                }
              ]
            }

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
