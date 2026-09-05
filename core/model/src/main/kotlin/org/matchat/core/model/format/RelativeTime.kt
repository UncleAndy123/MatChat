package org.matchat.core.model.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Display-ready time strings. Pure and injectable (nowMs / zone are parameters)
 * so a ViewModel can format without importing Android and a unit test can pin the
 * clock. Uses java.time, which the app desugars for API < 26 (PLAN.md §3).
 */
object RelativeTime {

    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val monthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    /** Room-list timestamp (S8): clock today, weekday this week, else month/day. */
    fun roomListLabel(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> then.format(clock)
            days in 1..6 -> then.format(weekday)
            else -> then.format(monthDay)
        }
    }

    /** Timeline message time (S9), always the wall clock. */
    fun clockTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(clock)

    /** Timeline day-separator label (S9). */
    fun daySeparator(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        return when (days) {
            0L -> "Today"
            1L -> "Yesterday"
            else -> then.format(monthDay)
        }
    }
}
