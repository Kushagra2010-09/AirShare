package app.airshare

import app.airshare.protocol.FileMeta
import app.airshare.protocol.Msg
import app.airshare.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val id = "0123456789abcdef"

    @Test fun inviteRoundTrips() {
        val m = Msg.Invite(id, "Rahul's Phone", listOf(FileMeta("a.jpg", 1234, "image/jpeg"), FileMeta("b.pdf", 99, "application/pdf")))
        assertEquals(m, Protocol.decode(Protocol.encode(m)))
    }

    @Test fun allSimpleMessagesRoundTrip() {
        val all = listOf(
            Msg.Accept(id, "Mom", "Pixel 8"), Msg.Decline(id), Msg.Confirm(id), Msg.Reject(id),
            Msg.Cancel(id), Msg.Done(id),
            Msg.FileInfo(id, 2, 987654321L, "x.mp4", 5_000_000_000L, "video/mp4", "a".repeat(64)),
        )
        all.forEach { assertEquals(it, Protocol.decode(Protocol.encode(it))) }
    }

    @Test fun rejectsGarbageAndTruncation() {
        assertNull(Protocol.decode(ByteArray(0)))
        assertNull(Protocol.decode(byteArrayOf(1, 2, 3)))
        val good = Protocol.encode(Msg.Confirm(id))
        assertNull(Protocol.decode(good.copyOf(good.size - 3)))
        assertNull(Protocol.decode(good + byteArrayOf(0)))        // trailing bytes
    }

    @Test fun rejectsWrongVersionAndBadId() {
        val good = Protocol.encode(Msg.Confirm(id)).also { it[0] = 9 }
        assertNull(Protocol.decode(good))
        assertNull(Protocol.decode(Protocol.encode(Msg.Confirm("not-a-valid-id!!!"))))
    }

    @Test fun rejectsOversizedAndBadHash() {
        assertNull(Protocol.decode(ByteArray(Protocol.MAX_MESSAGE_BYTES + 1) { 1 }))
        val bad = Msg.FileInfo(id, 0, 1, "n", 1, "m", "xyz")
        assertNull(Protocol.decode(Protocol.encode(bad)))
    }

    @Test fun rejectsTooManyFilesAndNegativeSize() {
        val many = Msg.Invite(id, "s", List(Protocol.MAX_FILES + 1) { FileMeta("f$it", 1, "x/y") })
        assertNull(Protocol.decode(Protocol.encode(many)))
        val neg = Msg.Invite(id, "s", listOf(FileMeta("f", -1, "x/y")))
        assertNull(Protocol.decode(Protocol.encode(neg)))
    }

    @Test fun inviteIdsAreValidAndUnique() {
        val a = Protocol.newInviteId(); val b = Protocol.newInviteId()
        assertNotNull(Protocol.decode(Protocol.encode(Msg.Cancel(a))))
        assertTrue(a != b)
    }
}
