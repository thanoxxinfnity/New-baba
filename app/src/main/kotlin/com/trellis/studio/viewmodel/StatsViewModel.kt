package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import kotlinx.coroutines.flow.*
import java.util.Calendar

/** Creation stats + unlockable badges — the "aura" screen you show friends. */
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    data class Badge(val emoji: String, val name: String, val unlocked: Boolean, val hint: String)

    data class Stats(
        val images: Int = 0,
        val models: Int = 0,
        val games: Int = 0,
        val total: Int = 0,
        val streakDays: Int = 0,
        val badges: List<Badge> = emptyList(),
    )

    val stats: StateFlow<Stats> = db.generationDao().getAll()
        .map { rows ->
            val images = rows.count { it.type == "image" }
            val models = rows.count { it.type == "3d" }
            val games = rows.count { it.type == "game" }
            val total = rows.size
            val streak = streakOf(rows.map { it.createdAt })
            Stats(images, models, games, total, streak, badges(images, models, games, total, streak))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Stats())

    /** Consecutive days (ending today) with at least one creation. */
    private fun streakOf(times: List<Long>): Int {
        if (times.isEmpty()) return 0
        val days = times.map { dayIndex(it) }.toHashSet()
        var streak = 0
        var d = dayIndex(System.currentTimeMillis())
        // allow the streak to count from today or yesterday
        if (d !in days && (d - 1) in days) d -= 1
        while (d in days) { streak++; d-- }
        return streak
    }

    private fun dayIndex(millis: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis / 86_400_000L
    }

    private fun badges(images: Int, models: Int, games: Int, total: Int, streak: Int) = listOf(
        Badge("🎨", "First Spark", total >= 1, "Create anything"),
        Badge("🖼️", "Image Maker", images >= 10, "Make 10 images"),
        Badge("🧊", "3D Sculptor", models >= 5, "Make 5 3D models"),
        Badge("🎮", "Game Dev", games >= 1, "Build a game"),
        Badge("🔥", "On Fire", streak >= 3, "3-day streak"),
        Badge("⚡", "Prolific", total >= 50, "50 creations"),
        Badge("👑", "Legend", total >= 200, "200 creations"),
    )
}
