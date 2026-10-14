package com.aicode.partyhub.security

import com.aicode.partyhub.net.Link
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 安卓侧的口令认证 + 加密信道，与网页端 SecureLink 行为一致：
 * 认证完成前不发送房间状态；完成后所有消息为 AES-256-GCM 顺序号封包。
 */
class SecureLink private constructor(
    private val raw: Link,
    private val roomId: String,
    private val handler: Handler,
) {
    interface Handler {
        fun onReady(link: SecureLink)
        fun onMessage(link: SecureLink, text: String)
        fun onClosed(link: SecureLink, reason: String)
    }

    val id: String = UUID.randomUUID().toString()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "secure-link-$id").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "secure-timer-$id").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private var cipher: SessionCipher? = null
    private var hostSession: RoomAuthHost? = null
    private var clientSession: RoomAuthClient? = null
    private var timeout: java.util.concurrent.ScheduledFuture<*>? = null

    private fun begin(password: String) {
        timeout = scheduler.schedule({ fail("认证超时") }, 25, TimeUnit.SECONDS)
        executor.execute {
            try {
                val host = RoomAuthHost(roomId, password)
                hostSession = host
                if (!closed.get()) raw.send(json.encodeToString(AuthChallenge.serializer(), host.challenge()))
            } catch (error: Exception) {
                fail("认证初始化失败")
            }
        }
    }

    private fun beginGuest(password: String) {
        timeout = scheduler.schedule({ fail("认证超时") }, 25, TimeUnit.SECONDS)
        executor.execute {
            try {
                clientSession = RoomAuthClient(roomId, password)
            } catch (error: Exception) {
                fail("认证初始化失败")
            }
        }
    }
    fun handleRawMessage(text: String) {
        executor.execute {
            if (closed.get()) return@execute
            try {
                val active = cipher
                if (active == null) {
                    val probe = json.parseToJsonElement(text).jsonObject
                    when (probe["type"]?.jsonPrimitive?.contentOrNull) {
                        "challenge" -> {
                            val client = clientSession ?: error("认证状态异常")
                            val challenge = json.decodeFromJsonElement(AuthChallenge.serializer(), probe)
                            val proof = client.answer(challenge)
                            raw.send(json.encodeToString(ClientProof.serializer(), proof))
                        }
                        "proof" -> {
                            val host = hostSession ?: error("认证状态异常")
                            val proof = json.decodeFromJsonElement(ClientProof.serializer(), probe)
                            val result = host.verify(proof)
                            cipher = result.cipher
                            raw.send(json.encodeToString(ServerProof.serializer(), result.proof))
                            ready()
                        }
                        "verified" -> {
                            val client = clientSession ?: error("认证状态异常")
                            val proof = json.decodeFromJsonElement(ServerProof.serializer(), probe)
                            cipher = client.finish(proof)
                            ready()
                        }
                        else -> error("认证阶段出现非法消息")
                    }
                } else {
                    val packet = json.decodeFromJsonElement(EncryptedPacket.serializer(), json.parseToJsonElement(text).jsonObject)
                    val plain = active.open(packet)
                    if (!closed.get()) handler.onMessage(this, plain)
                }
            } catch (error: Exception) {
                fail(error.message ?: "认证或解密失败")
            }
        }
    }

    private fun ready() {
        timeout?.cancel(false)
        if (closed.get()) return
        handler.onReady(this)
    }

    fun send(text: String) {
        if (closed.get()) return
        val active = cipher ?: throw IllegalStateException("尚未完成房间认证")
        executor.execute {
            if (closed.get()) return@execute
            try {
                val packet = active.seal(text)
                raw.send(json.encodeToString(EncryptedPacket.serializer(), packet))
            } catch (error: Exception) {
                fail(error.message ?: "加密发送失败")
            }
        }
    }

    fun rawClosed(reason: String?) {
        finish(reason ?: "连接已关闭")
    }

    fun close() {
        if (closed.get()) return
        try { raw.close() } catch (_: Exception) {}
        finish("已离开房间")
    }

    private fun fail(reason: String) {
        try { raw.close() } catch (_: Exception) {}
        finish(reason)
    }

    private fun finish(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        timeout?.cancel(false)
        scheduler.shutdownNow()
        executor.shutdown()
        handler.onClosed(this, reason)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

        fun host(raw: Link, roomId: String, password: String, handler: Handler): SecureLink {
            val link = SecureLink(raw, roomId, handler)
            link.begin(password)
            return link
        }

        fun guest(raw: Link, roomId: String, password: String, handler: Handler): SecureLink {
            val link = SecureLink(raw, roomId, handler)
            link.beginGuest(password)
            return link
        }
    }
}
