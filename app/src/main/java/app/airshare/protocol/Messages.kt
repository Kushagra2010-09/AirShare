package app.airshare.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class FileMeta(val name: String, val size: Long, val mime: String)

/**
 * Control messages exchanged over an (already encrypted by Nearby) connection.
 * Binary, length-bounded and strictly validated, because the peer is untrusted until the user approves it.
 */
sealed class Msg {
    abstract val inviteId: String
    data class Invite(override val inviteId: String, val senderName: String, val files: List<FileMeta>) : Msg()
    data class Accept(override val inviteId: String, val receiverName: String, val model: String) : Msg()
    data class Decline(override val inviteId: String) : Msg()
    data class Confirm(override val inviteId: String) : Msg()
    data class Reject(override val inviteId: String) : Msg()
    data class Cancel(override val inviteId: String) : Msg()
    data class FileInfo(
        override val inviteId: String, val index: Int, val payloadId: Long,
        val name: String, val size: Long, val mime: String, val sha256: String,
    ) : Msg()
    data class Done(override val inviteId: String) : Msg()
}

object Protocol {
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = 16 * 1024
    const val MAX_FILES = 64
    const val MAX_STRING = 200
    private const val ID_LEN = 16

    private const val T_INVITE = 1; private const val T_ACCEPT = 2; private const val T_DECLINE = 3
    private const val T_CONFIRM = 4; private const val T_REJECT = 5; private const val T_CANCEL = 6
    private const val T_FILEINFO = 7; private const val T_DONE = 8

    fun newInviteId(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val b = ByteArray(ID_LEN / 2); random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun validId(s: String) = s.length == ID_LEN && s.all { it in '0'..'9' || it in 'a'..'f' }

    fun encode(m: Msg): ByteArray {
        val bos = ByteArrayOutputStream()
        val o = DataOutputStream(bos)
        o.writeByte(VERSION)
        fun str(s: String) { o.writeUTF(s.take(MAX_STRING)) }
        when (m) {
            is Msg.Invite -> { o.writeByte(T_INVITE); str(m.inviteId); str(m.senderName)
                o.writeShort(m.files.size)
                m.files.forEach { str(it.name); o.writeLong(it.size); str(it.mime) } }
            is Msg.Accept -> { o.writeByte(T_ACCEPT); str(m.inviteId); str(m.receiverName); str(m.model) }
            is Msg.Decline -> { o.writeByte(T_DECLINE); str(m.inviteId) }
            is Msg.Confirm -> { o.writeByte(T_CONFIRM); str(m.inviteId) }
            is Msg.Reject -> { o.writeByte(T_REJECT); str(m.inviteId) }
            is Msg.Cancel -> { o.writeByte(T_CANCEL); str(m.inviteId) }
            is Msg.FileInfo -> { o.writeByte(T_FILEINFO); str(m.inviteId); o.writeInt(m.index)
                o.writeLong(m.payloadId); str(m.name); o.writeLong(m.size); str(m.mime); str(m.sha256) }
            is Msg.Done -> { o.writeByte(T_DONE); str(m.inviteId) }
        }
        o.flush()
        return bos.toByteArray()
    }

    /** Returns null for anything malformed, oversized, of an unknown version/type or with an invalid id. */
    fun decode(bytes: ByteArray): Msg? {
        if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) return null
        return try {
            val i = DataInputStream(ByteArrayInputStream(bytes))
            if (i.readUnsignedByte() != VERSION) return null
            val type = i.readUnsignedByte()
            val id = i.readUTF()
            if (!validId(id)) return null
            fun str(): String { val s = i.readUTF(); if (s.length > MAX_STRING) throw IllegalArgumentException(); return s }
            val msg: Msg = when (type) {
                T_INVITE -> {
                    val sender = str()
                    val n = i.readUnsignedShort()
                    if (n < 1 || n > MAX_FILES) return null
                    val files = List(n) {
                        val name = str(); val size = i.readLong(); val mime = str()
                        if (size < 0) return null
                        FileMeta(name, size, mime)
                    }
                    Msg.Invite(id, sender, files)
                }
                T_ACCEPT -> Msg.Accept(id, str(), str())
                T_DECLINE -> Msg.Decline(id)
                T_CONFIRM -> Msg.Confirm(id)
                T_REJECT -> Msg.Reject(id)
                T_CANCEL -> Msg.Cancel(id)
                T_FILEINFO -> {
                    val idx = i.readInt(); val pid = i.readLong(); val name = str()
                    val size = i.readLong(); val mime = str(); val sha = str()
                    if (idx < 0 || idx >= MAX_FILES || size < 0) return null
                    if (sha.length != 64 || !sha.all { it in '0'..'9' || it in 'a'..'f' }) return null
                    Msg.FileInfo(id, idx, pid, name, size, mime, sha)
                }
                T_DONE -> Msg.Done(id)
                else -> return null
            }
            if (i.available() != 0) null else msg
        } catch (e: Exception) { null }
    }
}
