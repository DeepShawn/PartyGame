package com.aicode.partyhub.net

import org.junit.Assert.*
import org.junit.Test

class LanDiscoveryTest {
    @Test fun advertisesOnlyPublicMetadataAndRoundTrips() {
        val fields = lanAttributes("room-1", "桌游房间", 43210, "chess", 3)
        assertEquals(setOf("v", "id", "name", "web", "game", "players"), fields.keys)
        val room = parseLanRoom("192.168.1.3", 12345, fields.mapValues { it.value.toByteArray() })!!
        assertEquals(LanRoom("room-1", "桌游房间", "192.168.1.3", 12345, 43210, "chess", 3), room)
    }

    @Test fun malformedExternalTxtIsNotExposedAsARoom() {
        val valid = lanAttributes("room-1", "Room", 43210, "chess", 1).mapValues { it.value.toByteArray() }
        for ((key, value) in listOf("v" to "2", "web" to "65536", "web" to "-1", "players" to "9", "id" to "", "game" to "x".repeat(65))) {
            assertNull(parseLanRoom("192.168.1.3", 12345, valid + (key to value.toByteArray())))
        }
        assertNull(parseLanRoom("192.168.1.3", 0, valid))
        assertNull(parseLanRoom("192.168.1.3", 12345, valid + ("name" to byteArrayOf(0xff.toByte()))))
    }
}
