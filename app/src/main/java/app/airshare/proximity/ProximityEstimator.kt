package app.airshare.proximity

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tunable thresholds. IMPORTANT: these defaults are starting points, NOT validated values.
 * They must be calibrated with real phones (different models, pockets, tables, walls) before being trusted.
 */
data class ProximityConfig(
    val windowSize: Int = 12,            // readings kept per receiver
    val maxAgeMs: Long = 5_000L,         // readings older than this are dropped
    val minSamples: Int = 4,             // below this there is no trustworthy estimate
    val maxStdDevDb: Double = 7.0,       // above this the signal is considered unstable
    val minConfidence: Double = 0.5,     // below this an estimate is not used for recommendations
    val clearMarginDb: Double = 8.0,     // best must beat runner-up by this much to be "clearly" closest
    val veryCloseRssi: Double = -58.0,   // coarse buckets for display wording only
    val nearbyRssi: Double = -72.0,
)

enum class Bucket { VERY_CLOSE, NEARBY, FARTHER, UNCLEAR }

data class ProximityEstimate(
    val smoothedRssi: Double?,
    val confidence: Double,      // 0..1
    val samples: Int,
    val stable: Boolean,
    val bucket: Bucket,
) {
    val trustworthy: Boolean get() = smoothedRssi != null
}

sealed class Recommendation {
    /** Exactly one receiver has accepted; nothing to disambiguate. */
    data class OnlyOne(val id: String) : Recommendation()
    /** One receiver is meaningfully closer than all others, with trustworthy measurements. */
    data class Clear(val id: String) : Recommendation()
    /** Several receivers, similar or unreliable measurements: the sender must choose. Sorted best-first where known. */
    data class Ambiguous(val ordered: List<String>) : Recommendation()
    /** No trustworthy proximity information at all. */
    data class Unknown(val ids: List<String>) : Recommendation()
    object None : Recommendation()
}

/** Rolling-window RSSI filter: median/MAD outlier rejection, then mean of what remains. Does NOT output distances. */
class ProximityEstimator(private val config: ProximityConfig = ProximityConfig()) {
    private data class Reading(val t: Long, val rssi: Int)
    private val readings = HashMap<String, ArrayDeque<Reading>>()

    fun addReading(id: String, rssi: Int, nowMs: Long) {
        // 127 is the Android "RSSI not available" sentinel; anything >= 0 dBm is not a real reading.
        if (rssi >= 0 || rssi < -127) return
        val q = readings.getOrPut(id) { ArrayDeque() }
        q.addLast(Reading(nowMs, rssi))
        while (q.size > config.windowSize) q.removeFirst()
    }

    fun forget(id: String) { readings.remove(id) }
    fun clear() = readings.clear()

    fun estimate(id: String, nowMs: Long): ProximityEstimate {
        val q = readings[id]
        val fresh = q?.filter { nowMs - it.t <= config.maxAgeMs }?.map { it.rssi.toDouble() }.orEmpty()
        if (fresh.size < config.minSamples) return ProximityEstimate(null, 0.0, fresh.size, false, Bucket.UNCLEAR)

        val med = median(fresh)
        val mad = median(fresh.map { abs(it - med) })
        val limit = 2.5 * mad + 3.0              // always tolerate +-3 dB even when MAD is ~0
        val kept = fresh.filter { abs(it - med) <= limit }
        if (kept.size < config.minSamples) return ProximityEstimate(null, 0.0, kept.size, false, Bucket.UNCLEAR)

        val mean = kept.average()
        val sd = sqrt(kept.sumOf { (it - mean) * (it - mean) } / kept.size)
        val stable = sd <= config.maxStdDevDb
        val countScore = (kept.size.toDouble() / config.windowSize).coerceAtMost(1.0)
        val spreadScore = (1.0 - sd / (config.maxStdDevDb * 1.5)).coerceIn(0.0, 1.0)
        val rejectPenalty = kept.size.toDouble() / fresh.size
        val confidence = (0.5 * countScore + 0.5 * spreadScore) * rejectPenalty
        val ok = stable && confidence >= config.minConfidence
        val bucket = when {
            !ok -> Bucket.UNCLEAR
            mean >= config.veryCloseRssi -> Bucket.VERY_CLOSE
            mean >= config.nearbyRssi -> Bucket.NEARBY
            else -> Bucket.FARTHER
        }
        return ProximityEstimate(if (ok) mean else null, confidence, kept.size, stable, bucket)
    }

    /** Applies the selection principle to the receivers that have ACCEPTED. */
    fun recommend(acceptedIds: List<String>, nowMs: Long): Recommendation {
        if (acceptedIds.isEmpty()) return Recommendation.None
        if (acceptedIds.size == 1) return Recommendation.OnlyOne(acceptedIds[0])
        val est = acceptedIds.associateWith { estimate(it, nowMs) }
        val known = acceptedIds.filter { est.getValue(it).trustworthy }
            .sortedByDescending { est.getValue(it).smoothedRssi!! }
        if (known.isEmpty()) return Recommendation.Unknown(acceptedIds)
        val ordered = known + acceptedIds.filterNot { it in known }
        // Every responder must be measurable, otherwise we cannot claim one is clearly closest.
        if (known.size == acceptedIds.size) {
            val best = est.getValue(known[0]).smoothedRssi!!
            val second = est.getValue(known[1]).smoothedRssi!!
            if (best - second >= config.clearMarginDb) return Recommendation.Clear(known[0])
        }
        return Recommendation.Ambiguous(ordered)
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
