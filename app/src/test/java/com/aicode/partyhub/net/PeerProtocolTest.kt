package com.aicode.partyhub.net

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PeerProtocolTest {
    // Fixtures follow peerjs v1.5.5 lib/socket.ts and lib/negotiator.ts, not a made-up signaling dialect.
    @Test fun cloudUrlAndHeartbeatMatchPeerJs155() {
        val url = PeerProtocol.socketUrl("Party Host", "random-token")
        assertEquals("0.peerjs.com", url.host)
        assertEquals(443, url.port)
        assertEquals("/peerjs", url.encodedPath)
        assertEquals("peerjs", url.queryParameter("key"))
        assertEquals("Party Host", url.queryParameter("id"))
        assertEquals("random-token", url.queryParameter("token"))
        assertEquals("1.5.5", url.queryParameter("version"))
        assertEquals("{\"type\":\"HEARTBEAT\"}", PeerProtocol.heartbeat.toString())
        assertEquals("stun:stun.l.google.com:19302", PeerProtocol.stunUrl)
        assertEquals(5000L, PeerProtocol.heartbeatMillis)
    }

    @Test fun offerAndAnswerHavePeerJsConnectionEnvelope() {
        val fixture = Json.parseToJsonElement("""{"type":"OFFER","dst":"browser","payload":{"sdp":{"type":"offer","sdp":"v=0\r\n"},"type":"data","connectionId":"dc_fixture","label":"room","reliable":true,"serialization":"json"}}""")
        assertEquals(fixture, PeerProtocol.offer("browser", "dc_fixture", "room", "v=0\r\n"))
        val answer = PeerProtocol.answer("browser", "dc_fixture", "v=0\r\n")
        assertEquals("ANSWER", answer["type"]!!.jsonPrimitive.content)
        assertEquals("answer", answer["payload"]!!.jsonObject["sdp"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("dc_fixture", answer["payload"]!!.jsonObject["connectionId"]!!.jsonPrimitive.content)
    }

    @Test fun candidateFixturePreservesMidAndMLine() {
        val fixture = Json.parseToJsonElement("""{"type":"CANDIDATE","dst":"browser","payload":{"candidate":{"candidate":"candidate:1 1 udp 123 192.168.1.2 54321 typ host","sdpMid":"0","sdpMLineIndex":0},"type":"data","connectionId":"dc_fixture"}}""")
        assertEquals(fixture, PeerProtocol.candidate("browser", "dc_fixture", "candidate:1 1 udp 123 192.168.1.2 54321 typ host", "0", 0))
    }

    @Test fun jsonSerializerUsesUtf8BinaryAndNotBinaryPack() {
        val text = "{\"type\":\"hello\",\"name\":\"中文🎲\"}"
        assertArrayEquals(JsonPrimitive(text).toString().toByteArray(Charsets.UTF_8), PeerJson.encode(text))
        assertEquals(text, PeerJson.decode(PeerJson.encode(text), true))
        assertEquals("ciphertext", PeerJson.decode("\"ciphertext\"".toByteArray(), true))
        assertNull(PeerJson.decode("{\"__peerData\":{\"type\":\"close\"}}".toByteArray(), true))
        assertThrows(IllegalArgumentException::class.java) { PeerJson.decode(text.toByteArray(), false) }
        assertThrows(Exception::class.java) { PeerJson.decode(byteArrayOf(0x81.toByte(), 0xa1.toByte()), true) }
    }

    @Test fun jsonSerializerEnforcesPeerJsMtuBeforeQueueing() {
        assertEquals(16299, PeerJson.encode("a".repeat(16297)).size)
        assertThrows(IllegalArgumentException::class.java) { PeerJson.encode("a".repeat(16298)) }
        assertArrayEquals("\"not json\"".toByteArray(), PeerJson.encode("not json"))
    }

    @Test fun linkTextRoundTripsExactlyLikeBrowserConnSendText() {
        for (text in listOf(" { \"a\": 1 } ", "\"already quoted\"", "null", "42", "", "line\nline")) {
            assertEquals(text, PeerJson.decode(PeerJson.encode(text), true))
        }
        assertEquals("{\"a\":1}", PeerJson.decode("{\"a\":1}".toByteArray(), true))
    }

    @Test fun candidateInboxWaitsForSdpAndKeysByBothPeerAndConnection() {
        val inbox = CandidateInbox<String>()
        val key = PeerConnectionKey("alice", "dc_shared")
        val other = PeerConnectionKey("mallory", "dc_shared")
        assertTrue(inbox.add(key, "first"))
        assertTrue(inbox.add(other, "other"))
        assertTrue(inbox.add(key, "second"))
        assertEquals(listOf("first", "second"), inbox.take(key))
        assertEquals(listOf("other"), inbox.take(other))
        assertTrue(inbox.take(key).isEmpty())
    }

    @Test fun candidateInboxIsBoundedAndExpires() {
        var now = 0L
        val inbox = CandidateInbox<Int>(clock = { now })
        val key = PeerConnectionKey("alice", "dc_1")
        repeat(64) { assertTrue(inbox.add(key, it)) }
        assertFalse(inbox.add(key, 65))
        now = 31_000L
        assertTrue(inbox.take(key).isEmpty())
        repeat(8) { assertTrue(inbox.add(PeerConnectionKey("peer$it", "c"), it)) }
        assertFalse(inbox.add(PeerConnectionKey("extra", "c"), 0))
    }
}
