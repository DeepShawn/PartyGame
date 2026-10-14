package com.aicode.partyhub.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TcpTransport(private val listener: LinkListener) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val links = mutableSetOf<TcpLink>()
    private var server: ServerSocket? = null
    private var closed = false

    fun host(): Int = synchronized(lock) {
        check(!closed) { "Transport is closed" }
        server?.let { return@synchronized it.localPort }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0), MAX_LINKS)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        server = socket
        scope.launch {
            try {
                while (isActive) {
                    val accepted = socket.accept()
                    synchronized(lock) {
                        if (closed || links.size >= MAX_LINKS) accepted.close()
                        else launchLink(TcpLink(accepted))
                    }
                }
            } catch (_: java.io.IOException) {
                close()
            }
        }
        socket.localPort
    }

    fun connect(host: String, port: Int) {
        require(host.isNotBlank() && host.length <= 253 && port in 1..65535)
        synchronized(lock) {
            check(!closed) { "Transport is closed" }
            check(links.size < MAX_LINKS) { "Connection limit reached" }
            launchLink(TcpLink(Socket()), host, port)
        }
    }

    private fun launchLink(link: TcpLink, host: String? = null, port: Int = 0) {
        links.add(link)
        scope.launch { link.run(host, port) }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { server?.close() }
            server = null
            links.toList().forEach { it.close() }
            if (links.isEmpty()) scope.cancel()
        }
    }

    private inner class TcpLink(private val socket: Socket) : Link {
        override val id: String = UUID.randomUUID().toString()
        private val stopped = AtomicBoolean()
        private val outgoing = Channel<ByteArray>(SEND_QUEUE_CAPACITY)
        @Volatile private var reason: String? = null

        override fun send(text: String) {
            if (stopped.get()) return
            val bytes = try { encodeUtf8(text) } catch (_: Exception) {
                stop("bad frame: invalid UTF-8 or size")
                return
            }
            if (outgoing.trySend(bytes).isFailure) stop("send queue full")
        }

        override fun close() = stop(null)

        private fun stop(why: String?) {
            if (!stopped.compareAndSet(false, true)) return
            reason = why
            outgoing.close()
            runCatching { socket.close() }
        }

        suspend fun run(host: String?, port: Int) = coroutineScope {
            var writer: Job? = null
            try {
                if (stopped.get()) return@coroutineScope
                if (host != null) socket.connect(InetSocketAddress(host, port), 10_000)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                if (stopped.get()) return@coroutineScope
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                listener.onOpen(this@TcpLink)
                writer = launch(Dispatchers.IO) {
                    try {
                        for (bytes in outgoing) {
                            output.writeInt(bytes.size)
                            output.write(bytes)
                            output.flush()
                        }
                    } catch (e: Exception) {
                        stop("tcp write: ${e.message}")
                    }
                }
                while (!stopped.get()) {
                    socket.soTimeout = 0
                    val first = input.read()
                    if (first < 0) break
                    socket.soTimeout = 15_000
                    val size: Int
                    val bytes: ByteArray
                    try {
                        size = (first shl 24) or (input.readUnsignedByte() shl 16) or
                            (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                        require(size in 0..MAX_FRAME_BYTES)
                        bytes = ByteArray(size)
                        input.readFully(bytes)
                    } catch (e: Exception) {
                        stop("bad frame: ${if (e is EOFException) "truncated" else "length or timeout"}")
                        break
                    }
                    val text = try { decodeUtf8(bytes) } catch (_: Exception) {
                        stop("bad frame: invalid UTF-8")
                        break
                    }
                    if (!stopped.get()) listener.onMessage(this@TcpLink, text)
                }
            } catch (e: Exception) {
                stop("tcp: ${e.message}")
            } finally {
                stop(null)
                writer?.cancelAndJoin()
                try { listener.onClosed(this@TcpLink, reason) } finally {
                    synchronized(lock) {
                        links.remove(this@TcpLink)
                        if (closed && links.isEmpty()) scope.cancel()
                    }
                }
            }
        }
    }
}
