package com.aicode.partyhub.net

import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LanWebServerTest {
    private val assets = mapOf(
        "index.html" to "<!doctype html><title>Party</title>".toByteArray(),
        "assets/app.js" to "console.log('party')".toByteArray()
    )

    @Test fun localOriginCannotBeForgedWithHostDnsOrPorts() {
        assertTrue(isLocalOrigin("192.168.1.4:8080", "http://192.168.1.4:8080", "192.168.1.4", 8080))
        assertTrue(isLocalOrigin("127.0.0.1:8080", "http://127.0.0.1:8080", "127.0.0.1", 8080))
        assertTrue(isLocalOrigin("[::1]:8080", "http://[::1]:8080", "::1", 8080))
        for (origin in listOf(null, "null", "https://evil.example", "http://192.168.1.4:8081", "http://user@192.168.1.4:8080", "http://192.168.1.4:8080/path")) {
            assertFalse(isLocalOrigin("192.168.1.4:8080", origin, "192.168.1.4", 8080))
        }
        assertFalse(isLocalOrigin("evil.example:8080", "http://evil.example:8080", "127.0.0.1", 8080))
        assertFalse(isLocalOrigin("192.168.1.9:8080", "http://192.168.1.9:8080", "192.168.1.4", 8080))
    }

    @Test fun realHttpServesOnlyPackagedAllowlist() {
        val server = LanWebServer(assets.mapValues { (_, bytes) -> { bytes.inputStream() } }, RecordingLinks())
        val client = OkHttpClient()
        try {
            val port = server.start()
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("<title>Party</title>"))
                assertEquals("nosniff", it.header("X-Content-Type-Options"))
            }
            for (path in listOf("/../secret", "/%2e%2e/secret", "/assets/%252e%252e/secret", "/etc/passwd", "/assets/", "/admin", "/.env")) {
                val response = rawHttp(port, "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n")
                assertFalse("$path: $response", response.startsWith("HTTP/1.1 200"))
            }
            assertTrue(rawHttp(port, "POST / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc").contains("405"))
            assertTrue(rawHttp(port, "GET / HTTP/1.1\r\nHost: evil.example:$port\r\nConnection: close\r\n\r\n").contains("403"))
            assertTrue(rawHttp(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n").contains("413"))
        } finally { server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun realWebSocketExchangesTextAndRejectsExternalOrigin() {
        val events = RecordingLinks()
        val server = LanWebServer(assets.mapValues { (_, bytes) -> { bytes.inputStream() } }, events)
        val client = OkHttpClient()
        val received = LinkedBlockingQueue<String>()
        val failures = LinkedBlockingQueue<Int>()
        try {
            val port = server.start()
            val ws = client.newWebSocket(Request.Builder().url("http://127.0.0.1:$port/room")
                .header("Origin", "http://127.0.0.1:$port").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { received.add(text) }
            })
            val link = events.open()
            ws.send("{\"hello\":\"世界\"}")
            assertEquals("{\"hello\":\"世界\"}", events.message().second)
            link.send("response")
            assertEquals("response", received.poll(5, TimeUnit.SECONDS))
            client.newWebSocket(Request.Builder().url("http://127.0.0.1:$port/room")
                .header("Origin", "https://evil.example").build(), object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failures.add(response?.code ?: -1) }
            })
            assertEquals(403, failures.poll(5, TimeUnit.SECONDS))
            ws.send("x".repeat(65537))
            assertNotNull(events.closure().second)
            assertTrue(events.messages.isEmpty())
            ws.cancel()
        } finally { server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun ninthWebSocketIsRejectedBeforeOnOpen() {
        val events = RecordingLinks()
        val server = LanWebServer(emptyMap(), events)
        val client = OkHttpClient.Builder().dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 }).build()
        val sockets = mutableListOf<WebSocket>()
        val failures = LinkedBlockingQueue<Int>()
        try {
            val port = server.start()
            repeat(9) { index ->
                sockets += client.newWebSocket(Request.Builder().url("http://127.0.0.1:$port/room")
                    .header("Origin", "http://127.0.0.1:$port").build(), object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failures.add(response?.code ?: -1) }
                })
                if (index < 8) events.open()
            }
            assertEquals(503, failures.poll(5, TimeUnit.SECONDS))
            assertTrue(events.opened.isEmpty())
        } finally { sockets.forEach { it.cancel() }; server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun httpRequestRateIsBounded() {
        val server = LanWebServer(emptyMap(), RecordingLinks())
        val client = OkHttpClient()
        try {
            val port = server.start()
            var limited = false
            repeat(245) {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/missing").build()).execute().use {
                    if (it.code == 429) limited = true
                }
            }
            assertTrue("Expected HTTP 429 after request budget", limited)
        } finally { server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    private fun rawHttp(port: Int, request: String): String = Socket("127.0.0.1", port).use {
        it.soTimeout = 5000
        it.getOutputStream().write(request.toByteArray())
        it.getInputStream().bufferedReader().readLine()
    }
}
