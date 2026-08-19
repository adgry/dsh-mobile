package com.dshmobile.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date formatters are rebuilt when the device locale changes. Holding them in top-level vals
 * freezes whatever locale happened to be active at class-init, so dates keep rendering in the old
 * language after the user switches. Only touched from the UI thread, so the cache needs no lock.
 */
private class Formatters(val locale: Locale) {
    val timeOnly = SimpleDateFormat("HH:mm", locale)
    val dayAndTime = SimpleDateFormat("M月d日 HH:mm", locale)
    val fullDate = SimpleDateFormat("yyyy年M月d日", locale)
}

private var cachedFormatters: Formatters? = null

private fun formatters(): Formatters {
    val locale = Locale.getDefault()
    cachedFormatters?.takeIf { it.locale == locale }?.let { return it }
    return Formatters(locale).also { cachedFormatters = it }
}

fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val f = formatters()
    return if (sameDay) f.timeOnly.format(Date(millis)) else f.dayAndTime.format(Date(millis))
}

fun formatRelativeDay(millis: Long): String {
    if (millis <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val days = daysBetween(then, now)
    return when {
        days == 0 -> "今天"
        days == 1 -> "昨天"
        days in 2..6 -> "$days 天前"
        else -> formatters().fullDate.format(Date(millis))
    }
}

private fun daysBetween(from: Calendar, to: Calendar): Int {
    val a = from.clone() as Calendar
    val b = to.clone() as Calendar
    listOf(a, b).forEach {
        it.set(Calendar.HOUR_OF_DAY, 0)
        it.set(Calendar.MINUTE, 0)
        it.set(Calendar.SECOND, 0)
        it.set(Calendar.MILLISECOND, 0)
    }
    val diff = b.timeInMillis - a.timeInMillis
    return (diff / 86_400_000L).toInt()
}

/** "1.4s" / "12.6s" / "2分18秒" — durations shown next to a reply. */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return ""
    val seconds = millis / 1000.0
    return when {
        seconds < 10 -> String.format(Locale.US, "%.1fs", seconds)
        seconds < 60 -> String.format(Locale.US, "%.0fs", seconds)
        else -> {
            val total = millis / 1000
            "${total / 60}分${total % 60}秒"
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
}

fun formatTokenCount(count: Int): String = when {
    count < 1000 -> count.toString()
    else -> String.format(Locale.US, "%.1fk", count / 1000.0)
}
