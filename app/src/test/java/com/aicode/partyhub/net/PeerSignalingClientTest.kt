package com.aicode.partyhub.net

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PeerSignalingClientTest {
    @Test fun realSignalingSocketWaitsForOpenAndSendsHeartbeatAndOffer() {
        val received = LinkedBlockingQueue<String>()
        val requestQuery = LinkedBlockingQueue<String>()
        val statuses = LinkedBlockingQueue<String>()
        val ready = LinkedBlockingQueue<Boolean>()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing { webSocket("/peerjs") {
                requestQuery.add(call.request.queryParameters["id"] ?: "")
                delay(200)
                send("{\"type\":\"OPEN\"}")
                for (frame in incoming) if (frame is Frame.Text) received.add(frame.readText())
            } }
        }.start(false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        val client = PeerSignalingClient({}, { statuses.add(it) }) { id, token ->
            "http://127.0.0.1:$port/peerjs".toHttpUrl().newBuilder()
                .addQueryParameter("id", id).addQueryParameter("token", token).build()
        }
        try {
            assertFalse(client.send(PeerProtocol.heartbeat))
            client.start("test-peer") { ready.add(true) }
            assertEquals(true, ready.poll(5, TimeUnit.SECONDS))
            assertEquals("test-peer", requestQuery.poll(5, TimeUnit.SECONDS))
            assertEquals("signal: OPEN", statuses.poll(5, TimeUnit.SECONDS))
            val offer = PeerProtocol.offer("browser", "dc_fixture", "room", "v=0\r\n")
            assertTrue(client.send(offer))
            assertEquals(offer, Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)))
            assertEquals(PeerProtocol.heartbeat.toString(), received.poll(7, TimeUnit.SECONDS))
        } finally { client.close(); server.stop(0, 1000) }
        assertFalse(client.send(PeerProtocol.heartbeat))
    }

    @Test fun publicCloudOpenSmokeWhenExplicitlyEnabled() {
        org.junit.Assume.assumeTrue(java.lang.Boolean.getBoolean("partyhub.cloudSmoke"))
        val ready = LinkedBlockingQueue<Boolean>()
        val statuses = LinkedBlockingQueue<String>()
        val client = PeerSignalingClient({}, { statuses.add(it) })
        try {
            client.start("partyhub-net-test-${java.util.UUID.randomUUID()}") { ready.add(true) }
            assertEquals("Cloud statuses: $statuses", true, ready.poll(25, TimeUnit.SECONDS))
            assertEquals("signal: OPEN", statuses.poll(1, TimeUnit.SECONDS))
            println("PeerJS Cloud: real TLS WebSocket /peerjs OPEN received")
        } finally { client.close() }
    }

    @Test fun idTakenAndInvalidMessagesNeverReportReady() {
        for ((message, expected) in listOf(
            "{\"type\":\"ID-TAKEN\"}" to "ID-TAKEN",
            "{\"type\":\"INVALID-KEY\"}" to "INVALID-KEY",
            "{\"type\":{}}" to "malformed",
            "garbage" to "malformed"
        )) {
            val statuses = LinkedBlockingQueue<String>()
            val ready = AtomicInteger()
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                install(WebSockets)
                routing { webSocket("/peerjs") { send(message); for (frame in incoming) Unit } }
            }.start(false)
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val client = PeerSignalingClient({}, { statuses.add(it) }) { _, _ -> "http://127.0.0.1:$port/peerjs".toHttpUrl() }
            try {
                client.start("test-peer") { ready.incrementAndGet() }
                val status = statuses.poll(5, TimeUnit.SECONDS)
                assertNotNull(status)
                assertTrue(status, status!!.startsWith("signal:") && status.contains(expected))
                assertEquals(0, ready.get())
                assertFalse(client.send(PeerProtocol.heartbeat))
            } finally { client.close(); server.stop(0, 1000) }
        }
    }
}
