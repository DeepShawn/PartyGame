package com.aicode.partyhub.net

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class RecordingLinks : LinkListener {
    val opened = LinkedBlockingQueue<Link>()
    val messages = LinkedBlockingQueue<Pair<Link, String>>()
    val closed = LinkedBlockingQueue<Pair<Link, String?>>()
    override fun onOpen(link: Link) { opened.add(link) }
    override fun onMessage(link: Link, text: String) { messages.add(link to text) }
    override fun onClosed(link: Link, reason: String?) { closed.add(link to reason) }
    fun open(): Link = checkNotNull(opened.poll(5, TimeUnit.SECONDS)) { "Link did not open" }
    fun message(): Pair<Link, String> = checkNotNull(messages.poll(5, TimeUnit.SECONDS)) { "No message" }
    fun closure(): Pair<Link, String?> = checkNotNull(closed.poll(5, TimeUnit.SECONDS)) { "Link did not close" }
}

class TcpTransportTest {
    @Test fun realLanSocketUsesBigEndianLengthAndUtf8() {
        val listener = RecordingLinks()
        val server = TcpTransport(listener)
        val address = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?: InetAddress.getLoopbackAddress()
        try {
            Socket(address, server.host()).use { socket ->
                socket.soTimeout = 5000
                val link = listener.open()
                assertEquals(link.id, UUID.fromString(link.id).toString())
                val text = "{\"message\":\"你好 🎲\"}"
                val bytes = text.toByteArray(Charsets.UTF_8)
                DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
                assertEquals(link to text, listener.message())
                link.send(text)
                val input = DataInputStream(socket.getInputStream())
                assertEquals(bytes.size, input.readInt())
                val response = ByteArray(bytes.size)
                input.readFully(response)
                assertArrayEquals(bytes, response)
                println("TCP real socket ${address.hostAddress}: bidirectional UTF-8 OK")
            }
            assertNull(listener.closure().second)
        } finally { server.close() }
    }

    @Test fun connectsAsynchronouslyAndGeneratesIndependentLinkIds() {
        val serverEvents = RecordingLinks()
        val clientEvents = RecordingLinks()
        val server = TcpTransport(serverEvents)
        val client = TcpTransport(clientEvents)
        try {
            client.connect("127.0.0.1", server.host())
            val incoming = serverEvents.open()
            val outgoing = clientEvents.open()
            assertNotEquals(incoming.id, outgoing.id)
            outgoing.send("abc")
            assertEquals("abc", serverEvents.message().second)
            incoming.send("def")
            assertEquals("def", clientEvents.message().second)
            client.close()
            assertEquals(outgoing.id, clientEvents.closure().first.id)
            assertEquals(incoming.id, serverEvents.closure().first.id)
        } finally { client.close(); server.close() }
    }

    @Test fun acceptsExactly64KiBAndEmptyFrames() {
        val events = RecordingLinks()
        val server = TcpTransport(events)
        try {
            Socket("127.0.0.1", server.host()).use { socket ->
                events.open()
                val out = DataOutputStream(socket.getOutputStream())
                for (text in listOf("", "x".repeat(65536))) {
                    out.writeInt(text.length); out.write(text.toByteArray()); out.flush()
                    assertEquals(text, events.message().second)
                }
            }
        } finally { server.close() }
    }

    @Test fun malformedLengthsAndUtf8CloseOnlyTheirLink() {
        val events = RecordingLinks()
        val server = TcpTransport(events)
        try {
            val port = server.host()
            for ((size, bytes) in listOf(-1 to byteArrayOf(), 65537 to byteArrayOf(), 2 to byteArrayOf(0xc3.toByte(), 0x28))) {
                Socket("127.0.0.1", port).use { socket ->
                    val link = events.open()
                    DataOutputStream(socket.getOutputStream()).apply { writeInt(size); write(bytes); flush() }
                    val closed = events.closure()
                    assertEquals(link.id, closed.first.id)
                    assertTrue(closed.second!!.contains("frame"))
                }
            }
            assertTrue(events.messages.isEmpty())
            Socket("127.0.0.1", port).use { events.open() }
        } finally { server.close() }
    }

    @Test fun truncatedHeaderAndPayloadAreBadFrames() {
        for (bytes in listOf(byteArrayOf(0, 0), byteArrayOf(0, 0, 0, 3, 1))) {
            val events = RecordingLinks()
            val server = TcpTransport(events)
            try {
                Socket("127.0.0.1", server.host()).use { socket ->
                    events.open()
                    socket.getOutputStream().write(bytes)
                    socket.shutdownOutput()
                    assertTrue(events.closure().second!!.contains("frame"))
                }
            } finally { server.close() }
        }
    }

    @Test fun oversizeSendAndRepeatedCloseNotifyOnce() {
        val events = RecordingLinks()
        val server = TcpTransport(events)
        try {
            Socket("127.0.0.1", server.host()).use {
                val link = events.open()
                link.send("x".repeat(65537))
                assertNotNull(events.closure().second)
                link.close(); link.close(); server.close()
                assertNull(events.closed.poll(150, TimeUnit.MILLISECONDS))
            }
        } finally { server.close() }
    }

    @Test fun stalledConsumerCannotCreateAnUnboundedSendQueue() {
        val opened = LinkedBlockingQueue<Link>()
        val closed = LinkedBlockingQueue<String>()
        val release = java.util.concurrent.CountDownLatch(1)
        val server = TcpTransport(object : LinkListener {
            override fun onOpen(link: Link) { opened.add(link); release.await(5, TimeUnit.SECONDS) }
            override fun onMessage(link: Link, text: String) = Unit
            override fun onClosed(link: Link, reason: String?) { closed.add(reason ?: "normal") }
        })
        try {
            Socket("127.0.0.1", server.host()).use {
                val link = checkNotNull(opened.poll(5, TimeUnit.SECONDS))
                repeat(33) { link.send("frame") }
                release.countDown()
                assertEquals("send queue full", closed.poll(5, TimeUnit.SECONDS))
            }
        } finally { release.countDown(); server.close() }
    }

    @Test fun refusedConnectReportsClosedLinkWithoutOpen() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val events = RecordingLinks()
        val client = TcpTransport(events)
        try {
            client.connect("127.0.0.1", port)
            assertNotNull(events.closure().second)
            assertTrue(events.opened.isEmpty())
        } finally { client.close() }
    }
}
