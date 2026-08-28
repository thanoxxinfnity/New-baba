package com.trellis.studio.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A game booster that frees memory by asking Android to drop other apps' cached
 * background processes, then reports the RAM it actually recovered — measured
 * before and after, never a made-up number.
 *
 * Honest about the limits: Android manages memory well on its own, and it will
 * re-cache apps over time, so a "boost" gives the foreground game more free
 * headroom for a while rather than a permanent change. The kill only touches
 * killable BACKGROUND processes — it can't and won't close the game you're
 * playing or anything running in the foreground.
 */
object GameBooster {

    data class Memory(
        val availMb: Long,
        val totalMb: Long,
        /** 0..100, how much RAM is in use. */
        val usedPercent: Int,
        val low: Boolean,
    )

    data class BoostResult(
        /** RAM recovered by this boost, in MB (never negative for display). */
        val freedMb: Long,
        val after: Memory,
        /** How many background apps were asked to release memory. */
        val appsTrimmed: Int,
    )

    fun memory(context: Context): Memory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val avail = info.availMem / MB
        val total = info.totalMem / MB
        val used = if (total > 0) (((total - avail) * 100) / total).toInt().coerceIn(0, 100) else 0
        return Memory(avail, total, used, info.lowMemory)
    }

    /**
     * Frees as much background memory as Android allows and returns what it
     * recovered. It asks the OS to kill every user-installed app's background
     * (cached) processes — twice, with a short pause, because some apps respawn a
     * helper the second pass can also clear — then GCs our own heap.
     *
     * What it never touches: the app you're using. [killBackgroundProcesses]
     * only affects BACKGROUND/cached processes; the foreground app (your game)
     * and the currently-active apps are protected by Android and are also skipped
     * explicitly here. Freeing 100% of RAM isn't possible without root — the OS,
     * system services and the foreground app always hold memory.
     */
    suspend fun boost(context: Context): BoostResult = withContext(Dispatchers.Default) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = memory(context).availMb
        val self = context.packageName
        val active = activePackages(am) + self

        var trimmed = 0
        repeat(2) { pass ->
            runCatching {
                val apps = context.packageManager.getInstalledApplications(0)
                for (app in apps) {
                    // Skip the apps in active/foreground use and system apps
                    // (killing those is a no-op Android blocks anyway). Only
                    // user-installed, backgrounded apps have caches worth freeing.
                    if (app.packageName in active) continue
                    if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    ) continue
                    runCatching {
                        am.killBackgroundProcesses(app.packageName)
                        if (pass == 0) trimmed++
                    }
                }
            }
            if (pass == 0) kotlinx.coroutines.delay(400)
        }
        System.gc()
        Runtime.getRuntime().gc()

        val after = memory(context)
        val freed = (after.availMb - before).coerceAtLeast(0)
        BoostResult(freed, after, trimmed)
    }

    /** Packages Android reports as currently running in the foreground/visible. */
    private fun activePackages(am: ActivityManager): Set<String> = runCatching {
        am.runningAppProcesses.orEmpty()
            .filter {
                it.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            }
            .flatMap { it.pkgList?.toList() ?: listOf(it.processName) }
            .toSet()
    }.getOrDefault(emptySet())

    private const val MB = 1024L * 1024L
}
