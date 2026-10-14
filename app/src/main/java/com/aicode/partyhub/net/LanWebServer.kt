package com.aicode.partyhub.net

import android.content.Context
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.seconds

class LanWebServer private constructor(
    private val loadAssets: () -> Map<String, () -> InputStream>,
    private val listener: LinkListener
) {
    constructor(context: Context, listener: LinkListener) : this({
        val manager = context.applicationContext.assets
        val files = mutableMapOf<String, () -> InputStream>()
        fun visit(path: String, depth: Int) {
            if (depth > 8 || files.size >= 512) return
            for (name in manager.list(if (path.isEmpty()) "web" else "web/$path").orEmpty()) {
                if (!name.matches(Regex("[A-Za-z0-9_.-]+")) || name.startsWith('.')) continue
                val relative = if (path.isEmpty()) name else "$path/$name"
                if (assetType(relative) != null) files[relative] = { manager.open("web/$relative") }
                else visit(relative, depth + 1)
            }
        }
        visit("", 0)
        files
    }, listener)

    internal constructor(assets: Map<String, () -> InputStream>, listener: LinkListener) : this({ assets }, listener)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val links = mutableSetOf<WebLink>()
    private val slots = Semaphore(MAX_LINKS)
    private val staticSlots = Semaphore(MAX_LINKS)
    private val reservationKey = AttributeKey<Reservation>("room-slot")
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var closed = false
    private var port = 0
    private val requestRate = WindowLimit(240, 10_000)

    fun start(): Int = synchronized(lock) {
        check(!closed) { "Server is closed" }
        if (port != 0) return@synchronized port
        val assets = loadAssets()
        val server = embeddedServer(CIO, configure = {
            connector { host = "0.0.0.0"; port = 0 }
            connectionIdleTimeoutSeconds = 10
        }) {
            install(WebSockets) {
                maxFrameSize = MAX_FRAME_BYTES.toLong()
                pingPeriod = 15.seconds
                timeout = 15.seconds
                masking = false
            }
            intercept(ApplicationCallPipeline.Plugins) {
                val headers = call.request.headers
                val host = headers[HttpHeaders.Host]
                val local = call.request.local.localAddress
                val localPort = call.request.local.localPort
                val uri = call.request.uri
                val rawPath = uri.substringBefore('?')
                val validPath = rawPath == "/" || (rawPath.startsWith('/') && rawPath.removePrefix("/").split('/').all {
                    it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) && it != ".."
                })
                val status = when {
                    !requestRate.take() -> HttpStatusCode.TooManyRequests
                    !validPath -> HttpStatusCode.BadRequest
                    uri.length > 2048 || headers.entries().sumOf { it.key.length + it.value.sumOf(String::length) } > 8192 -> HttpStatusCode.RequestHeaderFieldTooLarge
                    headers.getAll(HttpHeaders.Host)?.size != 1 || !isLocalHost(host, local, localPort) -> HttpStatusCode.Forbidden
                    call.request.httpMethod != HttpMethod.Get && call.request.httpMethod != HttpMethod.Head -> HttpStatusCode.MethodNotAllowed
                    headers[HttpHeaders.TransferEncoding] != null || headers[HttpHeaders.ContentLength]?.let { it.toLongOrNull() != 0L } == true -> HttpStatusCode.PayloadTooLarge
                    rawPath == "/room" && (headers.getAll(HttpHeaders.Origin)?.size != 1 || !isLocalOrigin(host, headers[HttpHeaders.Origin], local, localPort)) -> HttpStatusCode.Forbidden
                    rawPath == "/room" && synchronized(lock) { closed } -> HttpStatusCode.ServiceUnavailable
                    else -> null
                }
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.response.headers.append("Referrer-Policy", "no-referrer")
                call.response.headers.append("X-Frame-Options", "DENY")
                if (status != null) {
                    call.response.headers.append(HttpHeaders.Connection, "close")
                    call.respond(status)
                    finish()
                } else if (rawPath == "/room") {
                    if (!slots.tryAcquire()) {
                        call.respond(HttpStatusCode.ServiceUnavailable)
                        finish()
                    } else {
                        val reservation = Reservation()
                        call.attributes.put(reservationKey, reservation)
                        scope.launch { delay(10_000); reservation.expire() }
                    }
                }
            }
            routing {
                webSocket("/room") {
                    val reservation = call.attributes[reservationKey]
                    val link = WebLink(this)
                    val admitted = synchronized(lock) {
                        if (closed || !reservation.claim()) false else links.add(link)
                    }
                    if (!admitted) {
                        reservation.release()
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Connection limit"))
                        return@webSocket
                    }
                    try { link.run() } finally {
                        synchronized(lock) { links.remove(link) }
                        reservation.release()
                    }
                }
                route("/{path...}") {
                    handle {
                        val path = call.request.uri.substringBefore('?').let { if (it == "/") "index.html" else it.removePrefix("/") }
                        val safe = path.length <= 512 && path.split('/').all {
                            it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) && it != ".."
                        }
                        val source = if (safe) assets[path] else null
                        val type = assetType(path)
                        if (source == null || type == null) {
                            call.respond(HttpStatusCode.NotFound)
                        } else if (!staticSlots.tryAcquire()) {
                            call.respond(HttpStatusCode.ServiceUnavailable)
                        } else {
                            try {
                                val bytes = try {
                                    withContext(Dispatchers.IO) {
                                        source().use { input ->
                                            val output = ByteArrayOutputStream()
                                            val buffer = ByteArray(8192)
                                            while (true) {
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                if (output.size() + count > 16 * 1024 * 1024) throw java.io.IOException("Asset too large")
                                                output.write(buffer, 0, count)
                                            }
                                            output.toByteArray()
                                        }
                                    }
                                } catch (_: java.io.IOException) { null }
                                if (bytes == null) call.respond(HttpStatusCode.NotFound)
                                else {
                                    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                                    call.respond(object : io.ktor.http.content.OutgoingContent.ByteArrayContent() {
                                        override val contentType = type
                                        override val contentLength = bytes.size.toLong()
                                        override fun bytes() = if (call.request.httpMethod == HttpMethod.Head) byteArrayOf() else bytes
                                    })
                                }
                            } finally { staticSlots.release() }
                        }
                    }
                }
            }
        }
        try {
            server.start(wait = false)
            port = runBlocking(Dispatchers.IO) { server.engine.resolvedConnectors().single().port }
            engine = server
            port
        } catch (e: Exception) {
            server.stop(0, 1000)
            throw e
        }
    }

    fun close() {
        val server = synchronized(lock) {
            if (closed) return
            closed = true
            links.toList().forEach { it.close() }
            engine.also { engine = null }
        }
        scope.launch { try { server?.stop(0, 2000) } finally { scope.cancel() } }
    }

    private inner class Reservation {
        private val state = AtomicInteger(0)
        fun claim(): Boolean = state.compareAndSet(0, 1)
        fun expire() { if (state.compareAndSet(0, 2)) slots.release() }
        fun release() { if (state.getAndSet(2) != 2) slots.release() }
    }

    private class WindowLimit(private val limit: Int, private val millis: Long) {
        private var start = System.nanoTime()
        private var count = 0
        @Synchronized fun take(): Boolean {
            val now = System.nanoTime()
            if ((now - start) / 1_000_000 >= millis) { start = now; count = 0 }
            return ++count <= limit
        }
    }

    private inner class WebLink(private val session: DefaultWebSocketServerSession) : Link {
        override val id = UUID.randomUUID().toString()
        private val stopped = AtomicBoolean()
        private val outgoing = Channel<ByteArray>(SEND_QUEUE_CAPACITY)
        private val rate = WindowLimit(240, 1000)
        @Volatile private var reason: String? = null

        override fun send(text: String) {
            if (stopped.get()) return
            val bytes = try { encodeUtf8(text) } catch (_: Exception) {
                stop("bad frame: size or UTF-8"); return
            }
            if (outgoing.trySend(bytes).isFailure) stop("send queue full")
        }

        override fun close() = stop(null)

        private fun stop(why: String?) {
            if (!stopped.compareAndSet(false, true)) return
            reason = why
            outgoing.close()
            session.cancel(CancellationException(why ?: "Link closed"))
        }

        suspend fun run() = coroutineScope {
            var writer: Job? = null
            try {
                listener.onOpen(this@WebLink)
                writer = launch {
                    for (bytes in outgoing) withTimeout(15_000) { session.send(Frame.Text(true, bytes)) }
                }
                for (frame in session.incoming) {
                    if (!rate.take() || frame !is Frame.Text || frame.data.size > MAX_FRAME_BYTES) {
                        stop("bad frame: type, size or rate"); break
                    }
                    val text = try { decodeUtf8(frame.data) } catch (_: Exception) {
                        stop("bad frame: invalid UTF-8"); break
                    }
                    if (!stopped.get()) listener.onMessage(this@WebLink, text)
                }
                if (!stopped.get()) {
                    val close = session.closeReason.await()
                    if (close != null && close.code != 1000.toShort() && close.code != 1001.toShort()) {
                        reason = "websocket: ${close.code} ${close.message}"
                    }
                }
            } catch (e: Exception) {
                if (!stopped.get()) reason = "websocket: ${e.message}"
            } finally {
                stopped.set(true)
                outgoing.close()
                writer?.cancel()
                listener.onClosed(this@WebLink, reason)
            }
        }
    }
}

internal fun isLocalHost(host: String?, local: String, port: Int): Boolean {
    if (host == null) return false
    return try {
        val uri = URI("http://$host")
        val name = uri.host?.removeSurrounding("[", "]") ?: return false
        if (uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.port != port) return false
        if (name == "localhost") return InetAddress.getByName(local).isLoopbackAddress
        if (!name.matches(Regex("[0-9.]+|[0-9a-fA-F:]+"))) return false
        InetAddress.getByName(name) == InetAddress.getByName(local.substringBefore('%'))
    } catch (_: Exception) { false }
}

internal fun isLocalOrigin(host: String?, origin: String?, local: String, port: Int): Boolean {
    if (!isLocalHost(host, local, port) || origin == null) return false
    return try {
        val uri = URI(origin)
        uri.scheme == "http" && uri.rawAuthority == host && uri.rawUserInfo == null &&
            uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null
    } catch (_: Exception) { false }
}

private fun assetType(path: String): ContentType? = when (path.substringAfterLast('.', "").lowercase()) {
    "html" -> ContentType.Text.Html
    "js", "mjs" -> ContentType.Application.JavaScript
    "css" -> ContentType.Text.CSS
    "json", "webmanifest" -> ContentType.Application.Json
    "wasm" -> ContentType("application", "wasm")
    "png", "jpeg", "jpg", "gif", "webp", "ico", "svg" -> ContentType.parse(when (path.substringAfterLast('.')) {
        "svg" -> "image/svg+xml"; "ico" -> "image/x-icon"; "jpg" -> "image/jpeg"; else -> "image/${path.substringAfterLast('.')}"
    })
    "woff", "woff2" -> ContentType("font", path.substringAfterLast('.'))
    else -> null
}
