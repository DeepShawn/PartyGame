package com.aicode.partyhub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ContractTest {
    @Test fun catalogContainsSixteenDistinctPlayableGames() {
        assertEquals(16, GameCatalog.entries.size)
        assertEquals(16, GameCatalog.entries.map { it.id }.toSet().size)
        assertTrue(GameCatalog.entries.all { it.title.isNotBlank() && it.rules.isNotBlank() })
    }
    @Test fun actionsAndSnapshotsRoundTripWithoutLosingChineseText() {
        val action = GameAction("move", listOf(2, 8), "马")
        assertEquals(action, Json.decodeFromString<GameAction>(Json.encodeToString(action)))
        val snapshot = GameSnapshot("gomoku", 2, turn = 1, message = "轮到白方")
        assertEquals(snapshot, Json.decodeFromString<GameSnapshot>(Json.encodeToString(snapshot)))
    }
    @Test fun standardBoardGamesStayTwoPlayer() {
        assertEquals(2, GameCatalog.entries.first { it.id == "chess" }.maxPlayers)
        assertEquals(3, GameCatalog.entries.first { it.id == "chinese_checkers" }.maxPlayers)
        assertFalse(GameCatalog.entries.first { it.id == "go" }.webBot)
    }
}
