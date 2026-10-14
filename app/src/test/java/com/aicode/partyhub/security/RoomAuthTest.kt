package com.aicode.partyhub.security

import org.junit.Assert.*
import org.junit.Test

class RoomAuthTest {
    @Test fun correctPasswordCreatesMutuallyAuthenticatedKeys() {
        val host = RoomAuthHost("TEST-ROOM", "test-passphrase")
        val challenge = host.challenge()
        val client = RoomAuthClient("TEST-ROOM", "test-passphrase")
        val proof = client.answer(challenge)
        val result = host.verify(proof)
        val clientCipher = client.finish(result.proof)
        assertEquals("你好", result.cipher.open(clientCipher.seal("你好")))
        assertEquals("ready", clientCipher.open(result.cipher.seal("ready")))
    }
    @Test fun incorrectPasswordIsRejected() {
        val host = RoomAuthHost("TEST-ROOM", "test-passphrase")
        val client = RoomAuthClient("TEST-ROOM", "wrong-passphrase")
        assertThrows(Exception::class.java) { host.verify(client.answer(host.challenge())) }
    }
    @Test fun tamperingReplayAndWrongRoomAreRejected() {
        val host = RoomAuthHost("R", "test-passphrase")
        val client = RoomAuthClient("R", "test-passphrase")
        val result = host.verify(client.answer(host.challenge()))
        val cc = client.finish(result.proof)
        val packet = cc.seal("private cards")
        assertEquals("private cards", result.cipher.open(packet))
        assertThrows(Exception::class.java) { result.cipher.open(packet) }
        val next = cc.seal("secret")
        assertThrows(Exception::class.java) { result.cipher.open(next.copy(data = "00" + next.data.drop(2))) }
        assertThrows(Exception::class.java) {
            RoomAuthClient("other", "test-passphrase").answer(RoomAuthHost("R", "test-passphrase").challenge())
        }
    }
}
