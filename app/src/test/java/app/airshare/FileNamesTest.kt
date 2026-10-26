package app.airshare

import app.airshare.core.FileNames
import app.airshare.core.Formatting
import app.airshare.core.InviteGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test fun stripsPathTraversal() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.txt", FileNames.sanitize("..\\..\\evil.txt"))
        assertEquals("file", FileNames.sanitize(".."))
        assertEquals("file", FileNames.sanitize("/"))
        assertEquals("file", FileNames.sanitize(null))
    }

    @Test fun replacesIllegalCharactersAndHiddenDots() {
        assertEquals("a_b_c.txt", FileNames.sanitize("a:b*c.txt"))
        assertEquals("hidden", FileNames.sanitize(".hidden"))
        assertEquals("a_b.txt", FileNames.sanitize("a\u0000b.txt"))
    }

    @Test fun truncatesLongNamesKeepingExtension() {
        val n = FileNames.sanitize("x".repeat(500) + ".jpg")
        assertTrue(n.length <= 120); assertTrue(n.endsWith(".jpg"))
    }

    @Test fun uniqueNamesAvoidCollisions() {
        assertEquals("a.jpg", FileNames.unique("a.jpg", emptySet()))
        assertEquals("a (1).jpg", FileNames.unique("a.jpg", setOf("A.JPG")))
        assertEquals("a (2).jpg", FileNames.unique("a.jpg", setOf("a.jpg", "a (1).jpg")))
        assertEquals("noext (1)", FileNames.unique("noext", setOf("noext")))
    }

    @Test fun formatsSizes() {
        assertEquals("512 B", Formatting.bytes(512))
        assertEquals("1.5 KB", Formatting.bytes(1536))
        assertEquals("2.0 MB", Formatting.bytes(2L * 1024 * 1024))
    }

    @Test fun inviteGateLimitsPendingAndRate() {
        val g = InviteGate(maxPending = 2, maxPerWindow = 3, windowMs = 1000)
        assertTrue(g.tryAdmit(0)); assertTrue(g.tryAdmit(1))
        assertFalse(g.tryAdmit(2))            // 2 pending already
        g.release(); assertTrue(g.tryAdmit(3)) // third arrival in window
        g.release(); assertFalse(g.tryAdmit(4)) // rate limit (3 in window)
        assertTrue(g.tryAdmit(1500))           // window has slid
    }
}
