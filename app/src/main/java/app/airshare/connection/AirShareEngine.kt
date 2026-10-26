package app.airshare.connection

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import app.airshare.core.DeviceInfo
import app.airshare.core.FileNames
import app.airshare.core.InviteGate
import app.airshare.core.Permissions
import app.airshare.data.AppSettings
import app.airshare.data.HistoryEntry
import app.airshare.data.HistoryRepository
import app.airshare.notifications.Notifier
import app.airshare.protocol.FileMeta
import app.airshare.protocol.Msg
import app.airshare.protocol.Protocol
import app.airshare.proximity.BleBeacon
import app.airshare.proximity.Bucket
import app.airshare.proximity.ProximityEstimator
import app.airshare.proximity.Recommendation
import app.airshare.transfer.FileIo
import app.airshare.transfer.SaveResult
import app.airshare.transfer.SourceFile
import app.airshare.transfer.ThroughputMeter
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide coordinator for discovery, the two-way handshake and file transfer.
 * All mutable state is touched on the main thread (Nearby callbacks arrive there); disk work runs on IO.
 *
 * Wire protocol (see protocol/Messages.kt), per receiver connection:
 *   sender -> Invite    receiver -> Accept | Decline    sender -> Confirm | Reject | Cancel
 *   after Confirm: sender -> FileInfo + file payload (per file), receiver verifies size + SHA-256 before saving.
 * The Nearby connection (encrypted, with a 4-digit code shown on both phones) is auto-accepted at the transport
 * level ONLY as a tiny control channel; nothing is stored and no file payload is accepted until the user approves.
 */
class AirShareEngine(private val app: Context, private val history: HistoryRepository) {
    companion object {
        private const val INVITE_WAIT_MS = 15_000L
        private const val MAX_CANDIDATES = 5
        private val RETRY_DELAYS_MS = longArrayOf(5_000, 20_000, 60_000)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transport = NearbyTransport(app)
    private val beacon = BleBeacon(app)
    private val notifier = Notifier(app)
    private val estimator = ProximityEstimator()
    private val gate = InviteGate()
    private val rng = SecureRandom()

    val receiveStatus = MutableStateFlow<ReceiveStatus>(ReceiveStatus.Off)
    val sendState = MutableStateFlow<SendUi>(SendUi.Idle)
    val incoming = MutableStateFlow<List<IncomingUi>>(emptyList())

    /** Set by ShareActivity so a "Receiver ready" notification is only posted when the sender UI is not on screen. */
    @Volatile var sendUiVisible = false

    private var cfg = AppSettings()
    private var screenOff = false
    private fun now() = SystemClock.elapsedRealtime()
    private fun myName() = DeviceInfo.resolveName(app, cfg.displayName)
    private fun bytesOf(m: Msg) = Payload.fromBytes(Protocol.encode(m))

    private val tokenBytes: ByteArray by lazy { ByteArray(BleBeacon.TOKEN_BYTES).also { rng.nextBytes(it) } }
    private val myToken: String by lazy { BleBeacon.tokenHex(tokenBytes) }

    // ------------------------------------------------------------------ settings / lifecycle inputs

    fun onSettings(s: AppSettings) { cfg = s; reconcile() }
    fun onScreen(off: Boolean) { screenOff = off; if (cfg.pauseWhenScreenOff) reconcile() }
    fun onBluetoothChanged() = reconcile()
    fun retryReceiving() { retryCount = 0; reconcile(force = true) }

    // ------------------------------------------------------------------ RECEIVING (advertising)

    private var advertising = false
    private var advertisedName = ""
    private var beaconRunning = false
    private var retryCount = 0
    private var retryJob: Job? = null

    private fun reconcile(force: Boolean = false) {
        if (!cfg.receivingEnabled) { stopAdvertisingAll(); receiveStatus.value = ReceiveStatus.Off; return }
        if (cfg.pauseWhenScreenOff && screenOff) {
            stopAdvertisingAll(); receiveStatus.value = ReceiveStatus.Paused("Paused while the screen is off"); return
        }
        if (Permissions.missingRequired(app).isNotEmpty()) {
            stopAdvertisingAll(); receiveStatus.value = ReceiveStatus.Unavailable(UnavailableReason.PERMISSIONS); return
        }
        if (!Permissions.bluetoothOn(app)) {
            stopAdvertisingAll(); receiveStatus.value = ReceiveStatus.Unavailable(UnavailableReason.BLUETOOTH_OFF); return
        }
        val name = myName()
        if (advertising && !force && name == advertisedName) { syncBeacon(); return }
        startAdvertisingNow(name)
    }

    private fun startAdvertisingNow(name: String) {
        if (receiveStatus.value !is ReceiveStatus.Active) receiveStatus.value = ReceiveStatus.Starting
        transport.stopAdvertising(); advertising = false
        transport.startAdvertising("$myToken:$name", receiverLifecycle) { err ->
            if (!cfg.receivingEnabled) { transport.stopAdvertising(); return@startAdvertising }
            if (err == null) {
                advertising = true; advertisedName = name; retryCount = 0
                receiveStatus.value = ReceiveStatus.Active(name)
                syncBeacon()
            } else {
                receiveStatus.value = ReceiveStatus.Unavailable(UnavailableReason.ERROR, "Nearby error $err")
                scheduleRetry()
            }
        }
    }

    /** Bounded exponential backoff: three tries, then wait for the user ("Try again") instead of draining battery. */
    private fun scheduleRetry() {
        if (retryCount >= RETRY_DELAYS_MS.size) return
        val d = RETRY_DELAYS_MS[retryCount]
        retryJob?.cancel()
        retryJob = scope.launch { delay(d); retryCount++; reconcile(force = true) }
    }

    private fun syncBeacon() {
        val want = cfg.proximityBeacon && advertising
        if (want && !beaconRunning) beaconRunning = beacon.startAdvertising(tokenBytes)
        else if (!want && beaconRunning) { beacon.stopAdvertising(); beaconRunning = false }
    }

    private fun stopAdvertisingAll() {
        retryJob?.cancel()
        transport.stopAdvertising(); advertising = false
        beacon.stopAdvertising(); beaconRunning = false
        // Requests nobody has approved yet are dropped; approved/active transfers continue.
        ins.values.filter { it.phase.ordinal <= InPhase.INVITED.ordinal }.toList().forEach { closeIncoming(it.id, "Receiving was turned off") }
    }

    // ------------------------------------------------------------------ RECEIVER side: one object per sender connection

    private class In(val id: String, var senderName: String, val code: String) {
        var phase = InPhase.CONNECTING
        var invite: Msg.Invite? = null
        var admitted = true
        var timeoutJob: Job? = null
        val meter = ThroughputMeter()
        val infos = HashMap<Long, Msg.FileInfo>()
        val payloads = HashMap<Long, Payload>()
        val transferred = HashMap<Long, Long>()
        val completed = HashSet<Long>()
        val processed = HashSet<Long>()
        val usedIdx = HashSet<Int>()
        var saved = 0; var failed = 0
        var firstUri: String? = null; var firstMime: String? = null
        var message: String? = null
        var lastPublish = 0L
    }

    private val ins = LinkedHashMap<String, In>()

    private fun publishIncoming(force: Boolean = false) {
        val t = now()
        if (!force && t - lastIncomingPublish < 250) return
        lastIncomingPublish = t
        incoming.value = ins.values.filter { it.phase.ordinal >= InPhase.INVITED.ordinal }.map { toUi(it) }
    }
    private var lastIncomingPublish = 0L

    private fun toUi(i: In): IncomingUi {
        val inv = i.invite
        val files = inv?.let { FilesSummary.describe(it.files) }
        var progress: ProgressUi? = null
        if (inv != null && (i.phase == InPhase.RECEIVING || i.phase == InPhase.DONE)) {
            val total = inv.files.sumOf { it.size }
            val done = if (i.phase == InPhase.DONE) total else i.transferred.values.sum().coerceAtMost(total)
            val idx = (i.saved + i.failed).coerceAtMost(inv.files.size - 1)
            progress = ProgressUi(
                currentFile = inv.files.getOrNull(idx)?.name ?: "", fileIndex = idx + 1, fileCount = inv.files.size,
                doneBytes = done, totalBytes = total, speedBytesPerSec = i.meter.bytesPerSecond(),
                etaSeconds = i.meter.etaSeconds(now(), total - done),
            )
        }
        return IncomingUi(i.id, i.senderName, files, i.code, i.phase, progress, i.firstUri, i.firstMime, i.message)
    }

    private fun resetTimeout(i: In, ms: Long, onTimeout: () -> Unit) {
        i.timeoutJob?.cancel()
        i.timeoutJob = scope.launch { delay(ms); onTimeout() }
    }

    private fun releaseGate(i: In) { if (i.admitted) { i.admitted = false; gate.release() } }

    private fun removeIncoming(id: String) {
        val i = ins.remove(id) ?: return
        i.timeoutJob?.cancel(); releaseGate(i)
        notifier.cancel(Notifier.idFor(id))
        publishIncoming(true)
    }

    /** Closes a not-yet-running request. Visible requests stay in the UI as a terminal state until dismissed. */
    private fun closeIncoming(id: String, message: String) {
        val i = ins[id] ?: return
        i.timeoutJob?.cancel(); releaseGate(i)
        transport.disconnect(id)
        notifier.cancel(Notifier.idFor(id))
        if (i.phase.ordinal >= InPhase.INVITED.ordinal) { i.phase = InPhase.CANCELLED; i.message = message; publishIncoming(true) }
        else removeIncoming(id)
    }

    fun dismissIncoming(id: String) {
        val i = ins[id] ?: return
        if (i.phase == InPhase.DONE || i.phase == InPhase.FAILED || i.phase == InPhase.CANCELLED) removeIncoming(id)
    }

    fun acceptInvite(id: String) {
        val i = ins[id] ?: return
        val inv = i.invite ?: return
        if (i.phase != InPhase.INVITED) return
        transport.send(id, bytesOf(Msg.Accept(inv.inviteId, myName(), DeviceInfo.model())))
        i.phase = InPhase.ACCEPTED_WAIT
        resetTimeout(i, (cfg.inviteTimeoutSec + 30) * 1000L) { closeIncoming(id, "The sender didn't confirm in time") }
        notifier.cancel(Notifier.idFor(id))
        notifier.ongoing(Notifier.idFor(id), "Waiting for ${i.senderName}", "They'll confirm shortly…", endpoint = id)
        publishIncoming(true)
    }

    fun declineInvite(id: String) {
        val i = ins[id] ?: return
        val inv = i.invite
        if (inv != null) transport.send(id, bytesOf(Msg.Decline(inv.inviteId)))
        scope.launch { delay(500); transport.disconnect(id) }
        removeIncoming(id)
    }

    private val receiverLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (receiveStatus.value !is ReceiveStatus.Active || !gate.tryAdmit(now())) { transport.reject(endpointId); return }
            val name = FileNames.sanitize(info.endpointName).take(24).ifBlank { "Android Device" }
            val i = In(endpointId, name, info.authenticationDigits.orEmpty())
            ins[endpointId] = i
            transport.accept(endpointId, receiverPayloads)
            resetTimeout(i, INVITE_WAIT_MS) { if (i.phase.ordinal <= InPhase.AWAITING_INVITE.ordinal) closeIncoming(endpointId, "") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val i = ins[endpointId] ?: return
            if (result.status.isSuccess) { if (i.phase == InPhase.CONNECTING) i.phase = InPhase.AWAITING_INVITE }
            else removeIncoming(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            val i = ins[endpointId] ?: return
            when (i.phase) {
                InPhase.RECEIVING -> failIncoming(i, "Connection lost")
                InPhase.DONE, InPhase.FAILED, InPhase.CANCELLED -> {}
                InPhase.INVITED, InPhase.ACCEPTED_WAIT -> closeIncoming(endpointId, "The sender left")
                else -> removeIncoming(endpointId)
            }
        }
    }

    private val receiverPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val i = ins[endpointId]
            if (i == null) { transport.cancelPayload(payload.id); return }
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { Protocol.decode(it) }?.let { onIncomingMsg(i, it) }
                Payload.Type.FILE ->
                    // Files are only accepted AFTER the user accepted and the sender confirmed.
                    if (i.phase == InPhase.RECEIVING && i.payloads.size < Protocol.MAX_FILES) i.payloads[payload.id] = payload
                    else transport.cancelPayload(payload.id)
                else -> transport.cancelPayload(payload.id)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {
            val i = ins[endpointId] ?: return
            if (i.phase != InPhase.RECEIVING || !i.payloads.containsKey(u.payloadId)) return
            when (u.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> i.transferred[u.payloadId] = u.bytesTransferred
                PayloadTransferUpdate.Status.SUCCESS -> {
                    i.transferred[u.payloadId] = u.totalBytes; i.completed += u.payloadId; tryFinalize(i, u.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> { failIncoming(i, "Transfer interrupted"); return }
            }
            i.meter.add(now(), i.transferred.values.sum())
            publishIncoming()
            progressNotificationIn(i)
        }
    }

    private fun progressNotificationIn(i: In) {
        if (!cfg.showProgressNotification) return
        val inv = i.invite ?: return
        val t = now()
        if (t - i.lastPublish < 1000) return
        i.lastPublish = t
        val total = inv.files.sumOf { it.size }.coerceAtLeast(1)
        notifier.ongoing(Notifier.idFor(i.id), "Receiving from ${i.senderName}", "${i.saved + i.failed}/${inv.files.size} files",
            (i.transferred.values.sum() * 100 / total).toInt(), 100, i.id)
    }

    private fun onIncomingMsg(i: In, msg: Msg) {
        when (msg) {
            is Msg.Invite -> if (i.phase == InPhase.AWAITING_INVITE && i.invite == null) {
                i.invite = msg
                i.senderName = FileNames.sanitize(msg.senderName).take(24).ifBlank { i.senderName }
                i.phase = InPhase.INVITED
                resetTimeout(i, cfg.inviteTimeoutSec * 1000L) { closeIncoming(i.id, "Request expired") }
                publishIncoming(true)
                val s = FilesSummary.describe(msg.files)
                notifier.request(i.id, i.senderName, s.kinds, cfg.inviteTimeoutSec)
            }
            is Msg.Confirm -> if (i.phase == InPhase.ACCEPTED_WAIT && msg.inviteId == i.invite?.inviteId) {
                i.timeoutJob?.cancel(); i.phase = InPhase.RECEIVING
                i.meter.add(now(), 0)
                publishIncoming(true)
            }
            is Msg.Reject, is Msg.Cancel ->
                if (i.invite?.inviteId == msg.inviteId && i.phase.ordinal < InPhase.DONE.ordinal) {
                    cancelIncomingPayloads(i); closeIncoming(i.id, "The sender cancelled")
                }
            is Msg.FileInfo -> if (i.phase == InPhase.RECEIVING && msg.inviteId == i.invite?.inviteId) {
                val inv = i.invite ?: return
                val expected = inv.files.getOrNull(msg.index)
                if (expected == null || expected.size != msg.size || !i.usedIdx.add(msg.index) || i.infos.containsKey(msg.payloadId)) {
                    failIncoming(i, "The sender sent unexpected data"); return
                }
                i.infos[msg.payloadId] = msg
                tryFinalize(i, msg.payloadId)
            }
            else -> {}
        }
    }

    fun cancelIncoming(id: String) {
        val i = ins[id] ?: return
        if (i.phase == InPhase.RECEIVING) failIncoming(i, "You cancelled the transfer") else declineInvite(id)
    }

    private fun cancelIncomingPayloads(i: In) { i.payloads.keys.forEach { transport.cancelPayload(it) } }

    @Suppress("DEPRECATION")
    private fun tryFinalize(i: In, pid: Long) {
        val info = i.infos[pid] ?: return
        val payload = i.payloads[pid] ?: return
        if (pid !in i.completed || pid in i.processed) return
        i.processed += pid
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val pfd = payload.asFile()?.asParcelFileDescriptor()
                val r = if (pfd == null) SaveResult.Failed("Couldn't read the received file")
                else FileIo.saveReceived(app, pfd, info.name, info.mime, info.size, info.sha256)
                runCatching { payload.asFile()?.asJavaFile()?.delete() }   // raw Nearby copy exists as a real file on Android 9 only
                r
            }
            if (ins[i.id] !== i || i.phase != InPhase.RECEIVING) return@launch
            when (result) {
                is SaveResult.Saved -> { i.saved++; if (i.firstUri == null) { i.firstUri = result.uri; i.firstMime = info.mime } }
                is SaveResult.Failed -> { i.failed++; i.message = result.reason }
            }
            val total = i.invite?.files?.size ?: 0
            if (i.saved + i.failed >= total) finishIncoming(i) else publishIncoming(true)
        }
    }

    private fun finishIncoming(i: In) {
        val inv = i.invite ?: return
        i.timeoutJob?.cancel(); releaseGate(i)
        i.phase = if (i.failed == 0) InPhase.DONE else InPhase.FAILED
        if (i.failed != 0 && i.message == null) i.message = "Some files couldn't be saved"
        publishIncoming(true)
        val ok = i.failed == 0
        scope.launch {
            history.add(HistoryEntry(Protocol.newInviteId(), System.currentTimeMillis(), false, i.senderName, i.saved,
                inv.files.sumOf { it.size }, ok, inv.files.firstOrNull()?.name ?: "", i.firstUri, i.firstMime))
            delay(1500); transport.disconnect(i.id)
        }
        notifier.cancel(Notifier.idFor(i.id))
        notifier.finished(Notifier.idFor(i.id), if (ok) "Received from ${i.senderName}" else "Transfer incomplete",
            if (ok) "${i.saved} file(s) saved to Downloads/AirShare" else (i.message ?: ""), i.id)
    }

    private fun failIncoming(i: In, message: String) {
        if (i.phase == InPhase.DONE || i.phase == InPhase.FAILED) return
        i.timeoutJob?.cancel(); releaseGate(i)
        cancelIncomingPayloads(i)
        i.phase = InPhase.FAILED; i.message = message
        publishIncoming(true)
        notifier.cancel(Notifier.idFor(i.id))
        notifier.finished(Notifier.idFor(i.id), "Transfer failed", message, i.id)
        scope.launch { delay(500); transport.disconnect(i.id) }
    }

    // ------------------------------------------------------------------ SENDER side

    private class Cand(val id: String, val name: String, val token: String) {
        var status = CandStatus.CONNECTING
        var code = ""
        var displayName = name
        var model = ""
        var connected = false
        var note: String? = null
    }

    private class SendSess(val inviteId: String, val files: List<SourceFile>, val summary: FilesSummary, val startedAt: Long, val timeoutSec: Int) {
        val cands = LinkedHashMap<String, Cand>()
        var chosen: String? = null
        var ticker: Job? = null
        var transferJob: Job? = null
        var proximityOn = false
        var finished = false
        val total = files.sumOf { it.size }
        val sentTotal = HashMap<Long, Long>()
        val sentDone = HashMap<Long, Long>()
        val sentOk = HashSet<Long>()
        val payloadIdx = HashMap<Long, Int>()
        val pfds = ArrayList<ParcelFileDescriptor>()
        val meter = ThroughputMeter()
        var lastPublish = 0L
    }

    private var sess: SendSess? = null

    private fun parseEndpointName(raw: String): Pair<String, String>? {
        val k = raw.indexOf(':')
        if (k != 6) return null
        val token = raw.substring(0, 6)
        if (!token.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return token to FileNames.sanitize(raw.substring(7)).take(24).ifBlank { "Android Device" }
    }

    fun startSend(files: List<SourceFile>) {
        discardSession()
        val metas = files.map { FileMeta(it.name, it.size, it.mime) }
        if (files.isEmpty()) { sendState.value = SendUi.Ended(EndKind.FAILED, "None of the selected files could be read."); return }
        if (files.size > Protocol.MAX_FILES) { sendState.value = SendUi.Ended(EndKind.FAILED, "Please send at most ${Protocol.MAX_FILES} files at a time."); return }
        val thumbs = files.filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }.take(4).map { it.uri.toString() }
        val s = SendSess(Protocol.newInviteId(), files, FilesSummary.describe(metas, thumbs), now(), cfg.inviteTimeoutSec)
        if (Permissions.missingRequired(app).isNotEmpty()) { sendState.value = SendUi.Ended(EndKind.FAILED, "AirShare needs the Nearby permissions to find phones."); return }
        if (!Permissions.bluetoothOn(app)) { sendState.value = SendUi.Ended(EndKind.FAILED, "Turn on Bluetooth to find nearby phones."); return }
        if (!Permissions.locationSwitchOk(app)) { sendState.value = SendUi.Ended(EndKind.FAILED, "Android 11 and older need the Location switch turned on to find nearby phones. AirShare doesn't use your location."); return }
        sess = s
        synchronized(estimator) { estimator.clear() }
        publishSearching(s)
        transport.startDiscovery(discoveryCb) { err ->
            if (err != null && sess === s) finishSend(s, SendUi.Ended(EndKind.FAILED, "Couldn't start searching (code $err)."), null)
        }
        s.proximityOn = cfg.proximityBeacon && beacon.startScan { token, rssi ->
            synchronized(estimator) { estimator.addReading(token, rssi, now()) }
        }
        s.ticker = scope.launch {
            while (isActive) {
                delay(1000)
                if (sess !== s || s.chosen != null) break
                if (now() - s.startedAt > s.timeoutSec * 1000L) {
                    finishSend(s, SendUi.Ended(EndKind.EXPIRED, "Nobody confirmed in time."), Msg.Cancel(s.inviteId)); break
                }
                publishSearching(s)
            }
        }
    }

    private fun discardSession() {
        val s = sess ?: return
        finishSend(s, SendUi.Idle, Msg.Cancel(s.inviteId))
    }

    fun dismissSend() { if (sess == null) sendState.value = SendUi.Idle }

    fun cancelSend() {
        val s = sess ?: run { dismissSend(); return }
        finishSend(s, SendUi.Ended(EndKind.CANCELLED, "Cancelled."), Msg.Cancel(s.inviteId))
    }

    private fun proximityLabel(s: SendSess, c: Cand): String {
        if (!s.proximityOn) return "Proximity unavailable"
        val e = synchronized(estimator) { estimator.estimate(c.token, now()) }
        val sure = e.confidence >= 0.75
        return when (e.bucket) {
            Bucket.VERY_CLOSE -> if (sure) "Very close" else "Probably very close"
            Bucket.NEARBY -> if (sure) "Nearby" else "Probably nearby"
            Bucket.FARTHER -> "A bit farther away"
            Bucket.UNCLEAR -> "Signal unclear"
        }
    }

    private fun publishSearching(s: SendSess) {
        val t = now()
        val accepted = s.cands.values.filter { it.status == CandStatus.ACCEPTED }
        val rec = synchronized(estimator) { estimator.recommend(accepted.map { it.token }, t) }
        val recToken = when (rec) { is Recommendation.Clear -> rec.id; is Recommendation.OnlyOne -> rec.id; else -> null }
        val order: List<String> = when (rec) { is Recommendation.Ambiguous -> rec.ordered; else -> accepted.map { it.token } }
        val sorted = s.cands.values.sortedWith(compareBy({ it.status != CandStatus.ACCEPTED },
            { order.indexOf(it.token).let { i -> if (i < 0) Int.MAX_VALUE else i } }))
        val ui = sorted.map {
            CandUi(it.id, it.displayName, it.model, it.status, it.code, proximityLabel(s, it),
                recommended = it.status == CandStatus.ACCEPTED && it.token == recToken, note = it.note)
        }
        val kind = when (rec) {
            is Recommendation.Clear -> RecKind.CLEAR; is Recommendation.OnlyOne -> RecKind.ONLY_ONE
            is Recommendation.Ambiguous -> RecKind.AMBIGUOUS; is Recommendation.Unknown -> RecKind.UNKNOWN
            Recommendation.None -> RecKind.NONE
        }
        val left = ((s.timeoutSec * 1000L - (t - s.startedAt)) / 1000).toInt().coerceAtLeast(0)
        sendState.value = SendUi.Searching(s.summary, ui, kind, left, s.proximityOn)
    }

    private val discoveryCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val s = sess ?: return
            if (s.chosen != null || s.finished || s.cands.containsKey(endpointId) || s.cands.size >= MAX_CANDIDATES) return
            val (token, name) = parseEndpointName(info.endpointName) ?: return   // not an AirShare receiver
            val c = Cand(endpointId, name, token)
            s.cands[endpointId] = c
            transport.request(myName(), endpointId, senderLifecycle) {
                c.status = CandStatus.FAILED; c.note = "Couldn't connect"; if (sess === s) publishSearching(s)
            }
            publishSearching(s)
        }

        override fun onEndpointLost(endpointId: String) {
            val s = sess ?: return
            val c = s.cands[endpointId] ?: return
            if (c.status == CandStatus.CONNECTING) { c.status = CandStatus.FAILED; c.note = "Out of range"; publishSearching(s) }
        }
    }

    private val senderLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val s = sess; val c = s?.cands?.get(endpointId)
            if (s == null || c == null || s.chosen != null) { transport.reject(endpointId); return }
            // The peer must present the same advertised identity (token) it showed during discovery.
            if (parseEndpointName(info.endpointName)?.first != c.token) {
                c.status = CandStatus.FAILED; c.note = "Identity changed"; transport.reject(endpointId); publishSearching(s); return
            }
            c.code = info.authenticationDigits.orEmpty()
            transport.accept(endpointId, senderPayloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val s = sess; val c = s?.cands?.get(endpointId) ?: return
            if (s == null) return
            if (result.status.isSuccess) {
                c.connected = true; c.status = CandStatus.WAITING
                transport.send(endpointId, bytesOf(Msg.Invite(s.inviteId, myName(), s.files.map { FileMeta(it.name, it.size, it.mime) })))
            } else { c.status = CandStatus.FAILED; c.note = "Couldn't connect" }
            publishSearching(s)
        }

        override fun onDisconnected(endpointId: String) {
            val s = sess ?: return
            val c = s.cands[endpointId] ?: return
            c.connected = false
            if (s.chosen == endpointId) { if (!s.finished) failSend(s, "The connection was lost."); return }
            if (c.status == CandStatus.CONNECTING || c.status == CandStatus.WAITING || c.status == CandStatus.ACCEPTED) {
                c.status = CandStatus.FAILED; c.note = "Left"; publishSearching(s)
            }
        }
    }

    private val senderPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) { transport.cancelPayload(payload.id); return }
            val msg = payload.asBytes()?.let { Protocol.decode(it) } ?: return
            val s = sess ?: return
            val c = s.cands[endpointId] ?: return
            if (msg.inviteId != s.inviteId || s.chosen != null) return
            when (msg) {
                is Msg.Accept -> if (c.status == CandStatus.WAITING) {
                    c.status = CandStatus.ACCEPTED
                    c.displayName = FileNames.sanitize(msg.receiverName).take(24).ifBlank { c.name }
                    c.model = FileNames.sanitize(msg.model).take(60)
                    if (!sendUiVisible) notifier.receiverReady(c.displayName)
                    publishSearching(s)
                }
                is Msg.Decline -> if (c.status == CandStatus.WAITING) { c.status = CandStatus.DECLINED; publishSearching(s) }
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {
            val s = sess ?: return
            if (endpointId != s.chosen || !s.sentTotal.containsKey(u.payloadId)) return
            when (u.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> s.sentDone[u.payloadId] = u.bytesTransferred
                PayloadTransferUpdate.Status.SUCCESS -> { s.sentDone[u.payloadId] = s.sentTotal.getValue(u.payloadId); s.sentOk += u.payloadId }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> { failSend(s, "The transfer was interrupted."); return }
            }
            s.meter.add(now(), s.sentDone.values.sum())
            if (s.sentOk.size == s.files.size) completeSend(s, endpointId) else publishTransfer(s)
        }
    }

    /** The ONLY path that starts a transfer: explicit sender choice of one accepted, connected receiver. */
    fun confirmRecipient(endpointId: String) {
        val s = sess ?: return
        val c = s.cands[endpointId] ?: return
        if (s.chosen != null || s.finished || c.status != CandStatus.ACCEPTED || !c.connected) return
        s.chosen = endpointId
        s.ticker?.cancel()
        transport.stopDiscovery(); beacon.stopScan()
        s.cands.values.filter { it.id != endpointId && it.connected }.forEach {
            transport.send(it.id, bytesOf(Msg.Reject(s.inviteId)))
            scope.launch { delay(500); transport.disconnect(it.id) }
        }
        transport.send(endpointId, bytesOf(Msg.Confirm(s.inviteId)))
        notifier.cancel(Notifier.ID_READY)
        s.meter.add(now(), 0)
        publishTransfer(s, force = true)
        s.transferJob = scope.launch { runTransfer(s, c) }
    }

    private suspend fun runTransfer(s: SendSess, c: Cand) {
        try {
            for ((idx, f) in s.files.withIndex()) {
                if (sess !== s || s.finished) return
                val sha = withContext(Dispatchers.IO) { FileIo.sha256(app, f.uri) }
                val pfd = withContext(Dispatchers.IO) { FileIo.openPfd(app, f.uri) }
                if (sha == null || pfd == null) { failSend(s, "Couldn't read ${f.name}."); return }
                s.pfds += pfd
                val payload = Payload.fromFile(pfd)
                s.payloadIdx[payload.id] = idx; s.sentTotal[payload.id] = f.size; s.sentDone[payload.id] = 0
                transport.send(c.id, bytesOf(Msg.FileInfo(s.inviteId, idx, payload.id, f.name, f.size, f.mime, sha)))
                transport.send(c.id, payload)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failSend(s, "The transfer failed.") }
    }

    private fun publishTransfer(s: SendSess, force: Boolean = false) {
        val t = now()
        if (!force && t - s.lastPublish < 250) return
        s.lastPublish = t
        val c = s.chosen?.let { s.cands[it] } ?: return
        val okIdx = s.sentOk.mapNotNull { s.payloadIdx[it] }.toSet()
        val cur = s.files.indices.firstOrNull { it !in okIdx } ?: (s.files.size - 1)
        val done = s.sentDone.values.sum().coerceAtMost(s.total)
        val p = ProgressUi(s.files[cur].name, cur + 1, s.files.size, done, s.total, s.meter.bytesPerSecond(), s.meter.etaSeconds(t, s.total - done))
        sendState.value = SendUi.Transferring(s.summary, c.displayName, p)
        if (cfg.showProgressNotification) notifier.ongoing(Notifier.ID_SEND, "Sending to ${c.displayName}",
            "File ${p.fileIndex} of ${p.fileCount}", (p.fraction * 100).toInt(), 100)
    }

    private fun completeSend(s: SendSess, endpointId: String) {
        val c = s.cands[endpointId]
        val name = c?.displayName ?: "receiver"
        transport.send(endpointId, bytesOf(Msg.Done(s.inviteId)))
        scope.launch {
            history.add(HistoryEntry(s.inviteId, System.currentTimeMillis(), true, name, s.files.size, s.total, true,
                s.files.first().name, null, null))
        }
        notifier.finished(Notifier.ID_SEND, "Sent to $name", "${s.files.size} file(s) delivered")
        finishSend(s, SendUi.Done(s.summary, name), null, closeDelayMs = 1500, keepNotification = true)
    }

    private fun failSend(s: SendSess, message: String) {
        if (s.finished) return
        val name = s.chosen?.let { s.cands[it] }?.displayName ?: "receiver"
        scope.launch {
            history.add(HistoryEntry(s.inviteId, System.currentTimeMillis(), true, name, s.files.size, s.total, false, s.files.first().name, null, null))
        }
        notifier.finished(Notifier.ID_SEND, "Transfer failed", message)
        finishSend(s, SendUi.Ended(EndKind.FAILED, message), Msg.Cancel(s.inviteId), keepNotification = true)
    }

    private fun finishSend(s: SendSess, ui: SendUi, peersMsg: Msg?, closeDelayMs: Long = 600, keepNotification: Boolean = false) {
        if (s.finished) return
        s.finished = true
        s.ticker?.cancel(); s.transferJob?.cancel()
        transport.stopDiscovery(); beacon.stopScan()
        s.sentTotal.keys.filter { it !in s.sentOk }.forEach { transport.cancelPayload(it) }
        if (peersMsg != null) s.cands.values.filter { it.connected }.forEach { transport.send(it.id, bytesOf(peersMsg)) }
        val ids = s.cands.keys.toList(); val pfds = s.pfds.toList()
        scope.launch { delay(closeDelayMs); ids.forEach { transport.disconnect(it) }; pfds.forEach { runCatching { it.close() } } }
        if (sess === s) sess = null
        synchronized(estimator) { estimator.clear() }
        notifier.cancel(Notifier.ID_READY)
        if (!keepNotification) notifier.cancel(Notifier.ID_SEND)
        sendState.value = ui
    }
}
