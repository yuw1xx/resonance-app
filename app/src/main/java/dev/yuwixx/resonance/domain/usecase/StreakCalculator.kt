package dev.yuwixx.resonance.domain.usecase

import java.text.SimpleDateFormat
import java.util.Locale

/** Takes today and yesterday as parameters so it can be tested without a clock. */
object StreakCalculator {
    // A streak must include today or yesterday; otherwise it is zero.
    fun computeStreak(dates: List<String>, today: String, yesterday: String): Int {
        if (dates.isEmpty()) return 0
        if (dates[0] != today && dates[0] != yesterday) return 0

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var streak = 1
        for (i in 1 until dates.size) {
            val d1 = sdf.parse(dates[i - 1]) ?: break
            val d2 = sdf.parse(dates[i]) ?: break
            val diffDays = ((d1.time - d2.time) / (24L * 60 * 60 * 1000)).toInt()
            if (diffDays == 1) streak++ else break
        }
        return streak
    }

    // Scans the whole distinct-dates list for the longest consecutive run ever, regardless of
    // recency.
    fun computeLongestStreak(dates: List<String>): Int {
        if (dates.isEmpty()) return 0
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var longest = 1
        var current = 1
        for (i in 1 until dates.size) {
            val d1 = sdf.parse(dates[i - 1]) ?: break
            val d2 = sdf.parse(dates[i]) ?: break
            val diffDays = ((d1.time - d2.time) / (24L * 60 * 60 * 1000)).toInt()
            current = if (diffDays == 1) current + 1 else 1
            if (current > longest) longest = current
        }
        return longest
    }
}
