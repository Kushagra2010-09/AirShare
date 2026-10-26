package app.airshare.core

import java.util.Locale

object Formatting {
    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSec: Long): String = bytes(bytesPerSec) + "/s"

    fun eta(seconds: Long): String = when {
        seconds < 60 -> "${seconds.coerceAtLeast(1)} s left"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s left"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min left"
    }
}
