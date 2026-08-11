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
     * Frees background memory and returns what it recovered. Enumerates the
     * user's non-system apps and asks Android to kill each one's background
     * (cached) processes — leaving the current foreground game untouched.
     */
    suspend fun boost(context: Context): BoostResult = withContext(Dispatchers.Default) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = memory(context).availMb
        val self = context.packageName

        var trimmed = 0
        runCatching {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            for (app in apps) {
                // Skip our own app and system apps (killing those is a no-op and
                // Android protects them anyway). Only user-installed apps have
                // background caches worth releasing.
                if (app.packageName == self) continue
                if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                ) continue
                runCatching { am.killBackgroundProcesses(app.packageName); trimmed++ }
            }
        }
        // Release our own garbage too, so the reading reflects real free memory.
        System.gc()
        Runtime.getRuntime().gc()

        val after = memory(context)
        val freed = (after.availMb - before).coerceAtLeast(0)
        BoostResult(freed, after, trimmed)
    }

    private const val MB = 1024L * 1024L
}
