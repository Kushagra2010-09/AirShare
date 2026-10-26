package app.airshare.connection

import app.airshare.core.Formatting
import app.airshare.protocol.FileMeta

/** UI-facing models. The UI never sees Nearby types, endpoint internals or raw signal numbers. */
data class FilesSummary(
    val count: Int,
    val totalBytes: Long,
    val kinds: String,            // "4 photos, 1 video"
    val names: List<String>,      // first few names
    val thumbUris: List<String> = emptyList(),
) {
    val sizeText: String get() = Formatting.bytes(totalBytes)

    companion object {
        fun kindOf(mime: String): String = when {
            mime.startsWith("image/") -> "photo"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio file"
            mime == "application/pdf" -> "PDF"
            mime.startsWith("text/") || mime.contains("word") || mime.contains("sheet") ||
                mime.contains("presentation") || mime.contains("document") -> "document"
            else -> "file"
        }

        fun describe(metas: List<FileMeta>, thumbs: List<String> = emptyList()): FilesSummary {
            val groups = metas.groupingBy { kindOf(it.mime) }.eachCount().entries.sortedByDescending { it.value }
            val kinds = groups.joinToString(", ") { (k, n) -> if (n == 1) "1 $k" else "$n ${k}s" }
            return FilesSummary(metas.size, metas.sumOf { it.size }, kinds, metas.take(3).map { it.name }, thumbs)
        }
    }
}

data class ProgressUi(
    val currentFile: String,
    val fileIndex: Int,           // 1-based
    val fileCount: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long?,  // null until reliably measured
    val etaSeconds: Long?,        // null until enough data
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

enum class CandStatus { CONNECTING, WAITING, ACCEPTED, DECLINED, FAILED }

data class CandUi(
    val endpointId: String,
    val name: String,
    val model: String,
    val status: CandStatus,
    val code: String,             // short verification code shown on both phones
    val proximityLabel: String,   // confidence-aware wording, never a distance
    val recommended: Boolean,
    val note: String? = null,
)

enum class RecKind { NONE, ONLY_ONE, CLEAR, AMBIGUOUS, UNKNOWN }
enum class EndKind { CANCELLED, EXPIRED, FAILED }

sealed interface SendUi {
    data object Idle : SendUi
    data class Searching(
        val files: FilesSummary, val candidates: List<CandUi>, val rec: RecKind,
        val secondsLeft: Int, val proximityAvailable: Boolean,
    ) : SendUi
    data class Transferring(val files: FilesSummary, val peerName: String, val progress: ProgressUi) : SendUi
    data class Done(val files: FilesSummary, val peerName: String) : SendUi
    data class Ended(val kind: EndKind, val message: String) : SendUi
}

enum class InPhase { CONNECTING, AWAITING_INVITE, INVITED, ACCEPTED_WAIT, RECEIVING, DONE, FAILED, CANCELLED }

data class IncomingUi(
    val endpointId: String,
    val senderName: String,
    val files: FilesSummary?,
    val code: String,
    val phase: InPhase,
    val progress: ProgressUi? = null,
    val savedUri: String? = null,
    val savedMime: String? = null,
    val message: String? = null,
) {
    /** Shown to the user only once there is an actual invitation to talk about. */
    val visible: Boolean get() = phase.ordinal >= InPhase.INVITED.ordinal
}

enum class UnavailableReason { PERMISSIONS, BLUETOOTH_OFF, ERROR }

sealed interface ReceiveStatus {
    data object Off : ReceiveStatus
    data object Starting : ReceiveStatus
    data class Active(val name: String) : ReceiveStatus
    data class Paused(val reason: String) : ReceiveStatus
    data class Unavailable(val reason: UnavailableReason, val detail: String = "") : ReceiveStatus
}
