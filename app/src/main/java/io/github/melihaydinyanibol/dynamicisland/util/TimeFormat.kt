package io.github.melihaydinyanibol.dynamicisland.util

object TimeFormat {
    /** Formats a duration as `m:ss`, or `h:mm:ss` once it reaches an hour. */
    fun duration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) / 1000)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
