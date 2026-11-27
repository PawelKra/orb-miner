package com.example.spheresweeper.ui

/** Formats a duration as mm:ss, or h:mm:ss from one hour up. */
fun formatDuration(millis: Long): String = formatSeconds(millis / 1000L)

fun formatSeconds(totalSeconds: Long): String {
    val total = if (totalSeconds < 0L) 0L else totalSeconds
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    val sb = StringBuilder(8)
    if (hours > 0L) {
        sb.append(hours).append(':')
    }
    appendTwoDigits(sb, minutes)
    sb.append(':')
    appendTwoDigits(sb, seconds)
    return sb.toString()
}

private fun appendTwoDigits(sb: StringBuilder, value: Long) {
    if (value < 10L) sb.append('0')
    sb.append(value)
}
