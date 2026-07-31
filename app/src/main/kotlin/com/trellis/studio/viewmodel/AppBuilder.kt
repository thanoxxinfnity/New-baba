package com.trellis.studio.viewmodel

import com.trellis.studio.data.model.ChatTurn

/**
 * Turns "build me an app that…" into a real APK.
 *
 * The model writes the project as fenced files, this parses them out, and the
 * build runs on the user's own machine through the terminal — Android cannot
 * compile an APK on device, so that machine is the only path to a real one.
 */
object AppBuilder {

    /** Phrases that mean "make me an Android app", in English or Hinglish. */
    private val TRIGGERS = listOf(
        "build an app", "build me an app", "make an app", "create an app",
        "build an android", "make an android", "android app banao", "app banao",
        "apk banao", "build apk", "make apk", "/buildapp", "/apk",
    )

    fun looksLikeAppRequest(text: String): Boolean {
        val t = text.lowercase().trim()
        return TRIGGERS.any { t.startsWith(it) || t.contains(it) }
    }

    /** Strips the command prefix so the description reads naturally. */
    fun cleanRequest(text: String): String =
        text.trim().removePrefix("/buildapp").removePrefix("/apk").trim().ifBlank { text.trim() }

    /**
     * System prompt that makes the model emit a complete, compilable project
     * as one file per fenced block, each opened with a `// file: path` line.
     */
    fun projectPrompt(request: String): List<ChatTurn> = listOf(
        ChatTurn(
            role = "system",
            text = """
                You write complete, compilable Android projects.

                Rules:
                - Output ONLY fenced code blocks. No prose before or after.
                - Start every block with a comment naming the path, exactly:
                  // file: app/src/main/java/com/example/app/MainActivity.java
                - Include every file needed to build: settings.gradle, build.gradle,
                  gradle.properties, app/build.gradle, AndroidManifest.xml and sources.
                - Use the Android Gradle Plugin 8.5.2, compileSdk 34, minSdk 24,
                  and plain Java with android.app.Activity — no Compose, no Kotlin,
                  no external dependencies. Those keep the build fast and reliable.
                - namespace and applicationId must both be com.example.app.
                - Keep it small: one Activity is usually enough.
            """.trimIndent(),
        ),
        ChatTurn(role = "user", text = request),
    )

    /**
     * Pulls `// file: path` blocks out of a reply.
     * Returns an empty map when the model answered with prose instead.
     */
    fun parseProject(reply: String): Map<String, String> {
        val files = linkedMapOf<String, String>()
        val fence = Regex("```[a-zA-Z]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val header = Regex("^\\s*(?://|#|<!--)\\s*file:\\s*(\\S+?)\\s*(?:-->)?\\s*$")

        for (match in fence.findAll(reply)) {
            val body = match.groupValues[1]
            val lines = body.lines()
            val path = lines.firstOrNull()?.let { header.find(it)?.groupValues?.get(1) } ?: continue
            val content = lines.drop(1).joinToString("\n").trimEnd()
            if (content.isNotBlank()) files[path.trimStart('/')] = content
        }
        return files
    }

    /** Adds the boilerplate a model commonly leaves out. */
    fun withDefaults(files: Map<String, String>): Map<String, String> {
        val out = files.toMutableMap()
        out.getOrPut("gradle.properties") {
            "org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\n"
        }
        out.getOrPut("settings.gradle") {
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "VoidApp"
            include ":app"
            """.trimIndent()
        }
        out.getOrPut("build.gradle") {
            """plugins { id "com.android.application" version "8.5.2" apply false }"""
        }
        return out
    }

    /** Shown when there's no terminal to build on. */
    const val OFFLINE_HINT =
        "Your build terminal is offline, so I can't compile this into an APK yet.\n\n" +
            "Start it and I'll build immediately:\n" +
            "1. Open your Cloud Shell / PC terminal\n" +
            "2. Run:  start\n" +
            "3. Tell me \"terminal is on\" and I'll build and drop the APK here.\n\n" +
            "The project code is above — it's complete and ready to compile."
}
