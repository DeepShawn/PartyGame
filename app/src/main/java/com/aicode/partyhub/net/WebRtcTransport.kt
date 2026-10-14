package com.aicode.partyhub.net

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcTransport(
    context: Context,
    private val listener: LinkListener,
    private val onStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<() -> Unit>(256)
    private val stopped = AtomicBoolean()
    private val started = AtomicBoolean()
    private var factory: PeerConnectionFactory? = null
    private var signaling: PeerSignalingClient? = null
    private var ready = false
    private var localId = ""
    private val links = mutableMapOf<PeerConnectionKey, RtcLink>()
    private val candidates = CandidateInbox<IceCandidate>()

    init {
        scope.launch {
            for (event in events) {
                if (!stopped.get()) {
                    try { event() } catch (e: Exception) { onStatus("rtc: ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(25)
                if (!stopped.get()) {
                    val now = System.nanoTime()
                    links.values.toList().forEach { link ->
                        if (!link.opened && now - link.created > 30_000_000_000L) link.finish("ice: negotiation timeout (STUN cannot traverse every NAT)")
                        else if (link.disconnectedAt != 0L && now - link.disconnectedAt > 15_000_000_000L) link.finish("ice: disconnected timeout")
                        else link.flush()
                    }
                }
            }
        }
    }

    private fun post(action: () -> Unit) {
        if (!stopped.get() && events.trySend(action).isFailure) shutdown("rtc: event queue full")
    }

    fun start(peerId: String, onReady: () -> Unit) {
        require(PeerProtocol.validPeerId(peerId)) { "Invalid peer ID" }
        check(!stopped.get() && started.compareAndSet(false, true)) { "Transport already started or closed" }
        post {
            try {
                synchronized(initializationLock) {
                    if (!initialized) {
                        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .setEnableInternalTracer(false).createInitializationOptions())
                        initialized = true
                    }
                }
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                localId = peerId
                signaling = PeerSignalingClient(
                    onSignal = { message -> post { handleSignal(message) } },
                    onStatus = { status -> post {
                        onStatus(status)
                        if (status != "signal: OPEN" && !status.startsWith("signal: ignored")) {
                            ready = false
                            links.values.filter { !it.opened }.toList().forEach { it.finish(status) }
                        }
                    } }
                ).also { it.start(peerId) { post { ready = true; onReady() } } }
            } catch (e: Exception) { shutdown("rtc: initialization ${e.message}") }
            catch (e: UnsatisfiedLinkError) { shutdown("rtc: native library unavailable") }
        }
    }

    fun connect(peerId: String) {
        require(PeerProtocol.validPeerId(peerId)) { "Invalid remote peer ID" }
        post {
            if (!ready) { onStatus("signal: not ready"); return@post }
            if (peerId == localId) { onStatus("signal: cannot connect to self"); return@post }
            if (links.size >= MAX_LINKS) { onStatus("rtc: connection limit"); return@post }
            val connectionId = "dc_${UUID.randomUUID()}"
            val link = createLink(PeerConnectionKey(peerId, connectionId), connectionId, true) ?: return@post
            val dc = link.pc!!.createDataChannel(link.label, DataChannel.Init().apply { ordered = true })
            if (dc == null) link.finish("rtc: createDataChannel failed")
            else {
                link.attach(dc)
                link.pc!!.createOffer(link.createObserver(true), MediaConstraints())
            }
        }
    }

    private fun createLink(key: PeerConnectionKey, label: String, originator: Boolean): RtcLink? {
        val link = RtcLink(key, label, originator)
        links[key] = link
        try {
            val config = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder(PeerProtocol.stunUrl).createIceServer())).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            link.pc = factory!!.createPeerConnection(config, link.observer)
            if (link.pc == null) { link.finish("rtc: createPeerConnection failed"); return null }
            return link
        } catch (e: Exception) { link.finish("rtc: ${e.message}"); return null }
    }

    private fun handleSignal(message: JsonObject) {
        val type = (message["type"] as? JsonPrimitive)?.contentOrNull ?: return
        val src = (message["src"] as? JsonPrimitive)?.contentOrNull ?: return
        if (!PeerProtocol.validPeerId(src) || src == localId) return
        if (type == "LEAVE" || type == "EXPIRE") {
            links.values.filter { it.key.peer == src && (type == "LEAVE" || !it.opened) }.toList()
                .forEach { it.finish("signal: $type") }
            onStatus("signal: $type")
            return
        }
        val payload = message["payload"] as? JsonObject ?: return
        val connectionId = (payload["connectionId"] as? JsonPrimitive)?.contentOrNull ?: return
        if (connectionId.length !in 1..128 || payload["type"] != JsonPrimitive("data")) return
        val key = PeerConnectionKey(src, connectionId)
        try {
            when (type) {
                "OFFER" -> {
                    if (!ready || links.size >= MAX_LINKS || links.containsKey(key)) return
                    if (payload["serialization"] != JsonPrimitive("json") || payload["reliable"] != JsonPrimitive(true)) {
                        onStatus("signal: only reliable JSON data offers are supported"); return
                    }
                    val label = (payload["label"] as? JsonPrimitive)?.contentOrNull ?: connectionId
                    require(label.length <= 128)
                    val sdp = description(payload, SessionDescription.Type.OFFER)
                    createLink(key, label, false)?.remote(sdp)
                }
                "ANSWER" -> {
                    val link = links[key] ?: return
                    if (!link.originator || link.remoteStarted) return
                    link.remote(description(payload, SessionDescription.Type.ANSWER))
                }
                "CANDIDATE" -> {
                    val value = payload["candidate"] as? JsonObject ?: return
                    val sdp = value.getValue("candidate").jsonPrimitive.content
                    if (sdp.isEmpty()) return
                    require(sdp.length <= 4096)
                    val mid = (value["sdpMid"] as? JsonPrimitive)?.contentOrNull
                    require(mid == null || mid.length <= 128)
                    val index = value["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
                    require(index in 0..255)
                    val candidate = IceCandidate(mid, index, sdp)
                    val link = links[key]
                    if (link?.remoteReady == true) link.addCandidate(candidate)
                    else if (!candidates.add(key, candidate)) {
                        link?.finish("ice: candidate queue full")
                        onStatus("ice: candidate queue full")
                    }
                }
            }
        } catch (_: Exception) {
            links[key]?.finish("signal: malformed $type")
            onStatus("signal: malformed $type")
        }
    }

    private fun description(payload: JsonObject, expected: SessionDescription.Type): SessionDescription {
        val value = payload.getValue("sdp").jsonObject
        require(value["type"] == JsonPrimitive(expected.canonicalForm()))
        val sdp = value.getValue("sdp").jsonPrimitive.content
        require(sdp.length in 1..48_000)
        return SessionDescription(expected, sdp)
    }

    fun close() = shutdown(null)

    private fun shutdown(reason: String?) {
        if (!stopped.compareAndSet(false, true)) return
        scope.launch {
            try {
                ready = false
                signaling?.close(); signaling = null
                links.values.toList().forEach { it.finish(reason) }
                factory?.dispose(); factory = null
                candidates.clear()
                if (reason != null) onStatus(reason)
            } finally { events.close(); scope.cancel() }
        }
    }

    private inner class RtcLink(val key: PeerConnectionKey, val label: String, val originator: Boolean) : Link {
        override val id = UUID.randomUUID().toString()
        var pc: PeerConnection? = null
        private var channel: DataChannel? = null
        private val done = AtomicBoolean()
        private val outgoing = ArrayDeque<ByteArray>()
        private val outboundLock = Any()
        val created = System.nanoTime()
        var opened = false
        var remoteStarted = false
        var remoteReady = false
        var disconnectedAt = 0L

        override fun send(text: String) {
            if (done.get() || stopped.get()) return
            val bytes = try { PeerJson.encode(text) } catch (_: Exception) {
                post { finish("data: invalid JSON encoding or message exceeds 16299 bytes") }; return
            }
            val accepted = synchronized(outboundLock) {
                if (done.get()) true
                else if (outgoing.size >= SEND_QUEUE_CAPACITY) false
                else { outgoing.addLast(bytes); true }
            }
            if (!accepted) post { finish("data: send queue full") }
        }

        override fun close() { post { finish(null) } }

        fun flush() {
            if (done.get() || !opened) return
            val dc = channel ?: return
            while (dc.bufferedAmount() < 256 * 1024) {
                val bytes = synchronized(outboundLock) { outgoing.removeFirstOrNull() } ?: break
                if (!dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))) {
                    finish("data: send failed"); break
                }
            }
        }

        fun attach(dc: DataChannel) {
            if (done.get() || channel != null || dc.label() != label) {
                dc.close(); dc.dispose()
                if (!done.get()) finish("data: unexpected channel")
                return
            }
            channel = dc
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() { post { stateChanged() } }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.data.remaining() > PeerJson.MAX_BYTES || !buffer.binary) {
                        post { finish("data: expected PeerJS JSON binary frame below 16300 bytes") }; return
                    }
                    val copy = ByteArray(buffer.data.remaining())
                    buffer.data.duplicate().get(copy)
                    post {
                        if (done.get()) return@post
                        stateChanged()
                        if (!opened) { finish("data: message before open"); return@post }
                        val text = try { PeerJson.decode(copy, true) } catch (_: Exception) {
                            finish("data: malformed PeerJS JSON"); return@post
                        }
                        if (text == null) finish(null) else listener.onMessage(this@RtcLink, text)
                    }
                }
            })
            stateChanged()
        }

        private fun stateChanged() {
            if (done.get()) return
            when (channel?.state()) {
                DataChannel.State.OPEN -> if (!opened) { opened = true; listener.onOpen(this); onStatus("data: OPEN") }
                DataChannel.State.CLOSED -> finish(null)
                else -> Unit
            }
        }

        fun remote(sdp: SessionDescription) {
            if (done.get() || remoteStarted) return
            remoteStarted = true
            pc!!.setRemoteDescription(object : SdpCallbacks() {
                override fun onSetSuccess() { post {
                    if (done.get()) return@post
                    remoteReady = true
                    candidates.take(key).forEach { addCandidate(it) }
                    if (!originator && !done.get()) pc!!.createAnswer(createObserver(false), MediaConstraints())
                } }
                override fun onSetFailure(error: String) { post { finish("rtc: remote SDP $error") } }
            }, sdp)
        }

        fun createObserver(offer: Boolean): SdpObserver = object : SdpCallbacks() {
            override fun onCreateSuccess(sdp: SessionDescription) { post {
                if (done.get()) return@post
                pc!!.setLocalDescription(object : SdpCallbacks() {
                    override fun onSetSuccess() { post {
                        if (done.get()) return@post
                        val message = if (offer) PeerProtocol.offer(key.peer, key.connectionId, label, sdp.description)
                        else PeerProtocol.answer(key.peer, key.connectionId, sdp.description)
                        if (signaling?.send(message) != true) finish("signal: SDP send failed")
                    } }
                    override fun onSetFailure(error: String) { post { finish("rtc: local SDP $error") } }
                }, sdp)
            } }
            override fun onCreateFailure(error: String) { post { finish("rtc: SDP creation $error") } }
        }

        @Suppress("DEPRECATION")
        fun addCandidate(candidate: IceCandidate) {
            if (!done.get() && !pc!!.addIceCandidate(candidate)) finish("ice: rejected candidate")
        }

        fun finish(reason: String?) {
            if (!done.compareAndSet(false, true)) return
            links.remove(key)
            candidates.take(key)
            synchronized(outboundLock) { outgoing.clear() }
            channel?.let { it.unregisterObserver(); it.close(); it.dispose() }
            channel = null
            pc?.let { it.close(); it.dispose() }
            pc = null
            if (reason != null) onStatus(reason)
            listener.onClosed(this, reason)
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { post {
                if (done.get()) return@post
                when (state) {
                    PeerConnection.IceConnectionState.FAILED -> finish("ice: failed (STUN cannot traverse every NAT)")
                    PeerConnection.IceConnectionState.CLOSED -> finish("ice: closed")
                    PeerConnection.IceConnectionState.DISCONNECTED -> if (disconnectedAt == 0L) { disconnectedAt = System.nanoTime(); onStatus("ice: disconnected") }
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> { disconnectedAt = 0; onStatus("ice: connected") }
                    else -> Unit
                }
            } }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) { post {
                if (!done.get() && candidate.sdp.isNotEmpty() && signaling?.send(PeerProtocol.candidate(
                        key.peer, key.connectionId, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)) != true && !opened) finish("signal: candidate send failed")
            } }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dc: DataChannel) { post { attach(dc) } }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }
    }

    private open class SdpCallbacks : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private val initializationLock = Any()
        private var initialized = false
    }
}

internal data class PeerConnectionKey(val peer: String, val connectionId: String)

internal class CandidateInbox<T>(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val pending = mutableMapOf<PeerConnectionKey, Pair<Long, MutableList<T>>>()
    fun add(key: PeerConnectionKey, candidate: T): Boolean {
        expire()
        if (!pending.containsKey(key) && pending.size >= MAX_LINKS) return false
        val entry = pending.getOrPut(key) { clock() to mutableListOf() }
        if (entry.second.size >= 64) return false
        entry.second.add(candidate)
        return true
    }
    fun take(key: PeerConnectionKey): List<T> { expire(); return pending.remove(key)?.second ?: emptyList() }
    fun clear() = pending.clear()
    private fun expire() { val now = clock(); pending.entries.removeAll { now - it.value.first >= 30_000 } }
}
