package com.aicode.partyhub.net

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

class PeerSignalingClient internal constructor(
    private val onSignal: (JsonObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val endpoint: (String, String) -> HttpUrl
) {
    constructor(onSignal: (JsonObject) -> Unit, onStatus: (String) -> Unit) : this(onSignal, onStatus, PeerProtocol::socketUrl)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var ready = false
    private var started = false
    private var closed = false
    private var heartbeat: Job? = null
    private var deadline: Job? = null

    fun start(peerId: String, onReady: () -> Unit) {
        require(PeerProtocol.validPeerId(peerId)) { "Invalid peer ID" }
        synchronized(lock) {
            check(!started && !closed) { "Signaling client already started or closed" }
            started = true
            val request = Request.Builder().url(endpoint(peerId, UUID.randomUUID().toString())).build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(lock) {
                        if (closed) { webSocket.cancel(); return }
                        heartbeat = scope.launch {
                            while (isActive) {
                                delay(PeerProtocol.heartbeatMillis)
                                if (!webSocket.send(PeerProtocol.heartbeat.toString())) {
                                    fail("socket send failed"); break
                                }
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (synchronized(lock) { closed }) return
                    val message = try {
                        encodeUtf8(text)
                        Json.parseToJsonElement(text).jsonObject
                    } catch (_: Exception) { fail("malformed server message"); return }
                    val type = (message["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (type == null) { fail("malformed server message"); return }
                    when (type) {
                        "OPEN" -> {
                            val notify = synchronized(lock) {
                                if (closed || ready) false else { ready = true; deadline?.cancel(); true }
                            }
                            if (notify) { onStatus("signal: OPEN"); onReady() }
                        }
                        "ID-TAKEN", "INVALID-KEY", "ERROR" -> fail(type)
                        "OFFER", "ANSWER", "CANDIDATE", "LEAVE", "EXPIRE" -> if (synchronized(lock) { ready && !closed }) onSignal(message)
                        "HEARTBEAT" -> Unit
                        else -> onStatus("signal: ignored unknown message")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = fail("unexpected binary signaling frame")
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail("socket closed ($code)")
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail("socket failure: ${t.javaClass.simpleName}")
            })
            deadline = scope.launch { delay(20_000); fail("OPEN timeout") }
        }
    }

    fun send(message: JsonObject): Boolean = synchronized(lock) {
        if (closed || !ready) return@synchronized false
        val text = message.toString()
        val bytes = try { encodeUtf8(text) } catch (_: Exception) { return@synchronized false }
        val ws = socket ?: return@synchronized false
        if (ws.queueSize() + bytes.size > 256 * 1024 || !ws.send(text)) {
            fail("send queue full")
            false
        } else true
    }

    private fun fail(reason: String) {
        if (shutdown()) onStatus("signal: $reason")
    }

    private fun shutdown(): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        closed = true
        ready = false
        heartbeat?.cancel(); deadline?.cancel(); scope.cancel()
        socket?.cancel(); socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        true
    }

    fun close() { shutdown() }
}

internal object PeerProtocol {
    const val heartbeatMillis = 5000L
    const val stunUrl = "stun:stun.l.google.com:19302"
    val heartbeat = buildJsonObject { put("type", "HEARTBEAT") }

    fun validPeerId(id: String): Boolean = id.length in 1..128 && id.matches(Regex("[A-Za-z0-9]+(?:[ _-][A-Za-z0-9]+)*"))

    fun socketUrl(id: String, token: String): HttpUrl = HttpUrl.Builder().scheme("https")
        .host("0.peerjs.com").port(443).addPathSegment("peerjs")
        .addQueryParameter("key", "peerjs").addQueryParameter("id", id)
        .addQueryParameter("token", token).addQueryParameter("version", "1.5.5").build()

    fun offer(dst: String, connectionId: String, label: String, sdp: String): JsonObject = envelope("OFFER", dst, connectionId) {
        putJsonObject("sdp") { put("type", "offer"); put("sdp", sdp) }
        put("label", label); put("reliable", true); put("serialization", "json")
    }

    fun answer(dst: String, connectionId: String, sdp: String): JsonObject = envelope("ANSWER", dst, connectionId) {
        putJsonObject("sdp") { put("type", "answer"); put("sdp", sdp) }
    }

    fun candidate(dst: String, connectionId: String, candidate: String, mid: String?, index: Int): JsonObject = envelope("CANDIDATE", dst, connectionId) {
        putJsonObject("candidate") {
            put("candidate", candidate); put("sdpMid", mid?.let(::JsonPrimitive) ?: JsonNull); put("sdpMLineIndex", index)
        }
    }

    private fun envelope(type: String, dst: String, connectionId: String, payload: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", type); put("dst", dst)
        putJsonObject("payload") { put("type", "data"); put("connectionId", connectionId); payload() }
    }
}

internal object PeerJson {
    // peerjs 1.5.5 Chunker.chunkedMTU = 16300，JSON 序列化器拒绝 >= 16300 字节的负载。
    const val MAX_BYTES = 16299

    /**
     * 与浏览器 `conn.send(text)` 逐字节一致：peerjs 对字符串执行 JSON.stringify，
     * 即把文本包成带引号的 JSON 字符串（保留内部转义），因此这里不能再做解析重排。
     */
    fun encode(text: String): ByteArray {
        require(text.length <= MAX_BYTES) { "PeerJS JSON message too large" }
        return encodeUtf8(JsonPrimitive(text).toString(), MAX_BYTES)
    }

    fun decode(bytes: ByteArray, binary: Boolean): String? {
        require(binary && bytes.size <= MAX_BYTES) { "Expected PeerJS JSON binary frame below 16300 bytes" }
        val value = Json.parseToJsonElement(decodeUtf8(bytes))
        if (value is JsonObject && (value["__peerData"] as? JsonObject)?.get("type") == JsonPrimitive("close")) return null
        return if (value is JsonPrimitive && value.isString) value.content else value.toString()
    }
}
