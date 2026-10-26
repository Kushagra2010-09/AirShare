package app.airshare.transfer

/**
 * Measures transfer speed over a sliding window. Returns null until enough data exists so the UI
 * never shows a made-up number.
 */
class ThroughputMeter(private val windowMs: Long = 4_000L, private val minSpanMs: Long = 1_500L) {
    private data class Sample(val t: Long, val bytes: Long)
    private val samples = ArrayDeque<Sample>()
    private var startedAt = -1L

    fun add(nowMs: Long, totalBytesSoFar: Long) {
        if (startedAt < 0) startedAt = nowMs
        samples.addLast(Sample(nowMs, totalBytesSoFar))
        while (samples.size > 2 && nowMs - samples.first().t > windowMs) samples.removeFirst()
    }

    /** Bytes per second, or null when fewer than [minSpanMs] of data has been observed. */
    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val a = samples.first(); val b = samples.last()
        val span = b.t - a.t
        if (span < minSpanMs || b.bytes <= a.bytes) return null
        return (b.bytes - a.bytes) * 1000 / span
    }

    /** Seconds remaining, or null if speed is unknown or the transfer has been running < 3 s. */
    fun etaSeconds(nowMs: Long, remainingBytes: Long): Long? {
        val s = bytesPerSecond() ?: return null
        if (startedAt < 0 || nowMs - startedAt < 3_000L || s <= 0) return null
        return remainingBytes / s
    }
}
