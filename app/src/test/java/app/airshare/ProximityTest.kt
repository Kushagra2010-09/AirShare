package app.airshare

import app.airshare.proximity.Bucket
import app.airshare.proximity.ProximityEstimator
import app.airshare.proximity.Recommendation
import app.airshare.transfer.ThroughputMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {
    private fun feed(e: ProximityEstimator, id: String, values: List<Int>, start: Long = 0) {
        values.forEachIndexed { i, v -> e.addReading(id, v, start + i * 200L) }
    }

    @Test fun tooFewSamplesIsUnclear() {
        val e = ProximityEstimator()
        feed(e, "a", listOf(-50, -51))
        val est = e.estimate("a", 500)
        assertNull(est.smoothedRssi); assertEquals(Bucket.UNCLEAR, est.bucket)
    }

    @Test fun outlierIsRejected() {
        val e = ProximityEstimator()
        feed(e, "a", listOf(-50, -51, -49, -50, -90, -50, -51, -49))
        val est = e.estimate("a", 1600)
        assertNotNull(est.smoothedRssi)
        assertTrue(est.smoothedRssi!! > -53)       // -90 spike did not drag the mean down
        assertEquals(Bucket.VERY_CLOSE, est.bucket)
    }

    @Test fun noisyBouncingSignalIsNotTrusted() {
        val e = ProximityEstimator()
        feed(e, "a", listOf(-40, -85, -45, -90, -42, -88, -41, -86, -44, -87))
        assertNull(e.estimate("a", 2000).smoothedRssi)
    }

    @Test fun oldReadingsExpire() {
        val e = ProximityEstimator()
        feed(e, "a", listOf(-50, -50, -51, -50, -49))
        assertNotNull(e.estimate("a", 1000).smoothedRssi)
        assertNull(e.estimate("a", 60_000).smoothedRssi)
    }

    @Test fun invalidReadingsIgnored() {
        val e = ProximityEstimator()
        feed(e, "a", listOf(127, 0, 10, -200))
        assertEquals(0, e.estimate("a", 100).samples)
    }

    @Test fun clearWinnerIsHighlighted() {
        val e = ProximityEstimator()
        feed(e, "near", List(10) { -50 + (it % 3) })
        feed(e, "far", List(10) { -75 + (it % 3) })
        assertEquals(Recommendation.Clear("near"), e.recommend(listOf("far", "near"), 2000))
    }

    @Test fun similarSignalsAreAmbiguous() {
        val e = ProximityEstimator()
        feed(e, "a", List(10) { -55 + (it % 2) })
        feed(e, "b", List(10) { -58 + (it % 2) })
        val r = e.recommend(listOf("a", "b"), 2000)
        assertTrue(r is Recommendation.Ambiguous)
        assertEquals("a", (r as Recommendation.Ambiguous).ordered.first())
    }

    @Test fun unmeasuredResponderPreventsClearClaim() {
        val e = ProximityEstimator()
        feed(e, "a", List(10) { -45 + (it % 2) })
        assertTrue(e.recommend(listOf("a", "b"), 2000) is Recommendation.Ambiguous)
    }

    @Test fun noMeasurementsIsUnknownAndSingleResponderIsOnlyOne() {
        val e = ProximityEstimator()
        assertTrue(e.recommend(listOf("a", "b"), 0) is Recommendation.Unknown)
        assertEquals(Recommendation.OnlyOne("a"), e.recommend(listOf("a"), 0))
        assertEquals(Recommendation.None, e.recommend(emptyList(), 0))
    }

    @Test fun throughputIsNullUntilEnoughData() {
        val m = ThroughputMeter()
        m.add(0, 0); m.add(500, 1_000_000)
        assertNull(m.bytesPerSecond())
        m.add(2000, 4_000_000)
        assertEquals(2_000_000L, m.bytesPerSecond())
        assertNull(m.etaSeconds(2000, 1_000_000))     // < 3 s observed
        m.add(3500, 7_000_000)
        assertNotNull(m.etaSeconds(3500, 6_000_000))
        assertFalse(m.bytesPerSecond()!! <= 0)
    }
}
