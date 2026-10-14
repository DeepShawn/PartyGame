package com.aicode.partyhub.game

import android.content.Context
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.games.arcade.BomberGame
import com.aicode.partyhub.games.arcade.ColorGame
import com.aicode.partyhub.games.arcade.DodgeGame
import com.aicode.partyhub.games.arcade.MathGame
import com.aicode.partyhub.games.arcade.MemoryGame
import com.aicode.partyhub.games.arcade.ReactionGame
import com.aicode.partyhub.games.arcade.SnakeGame
import com.aicode.partyhub.games.arcade.TankGame
import com.aicode.partyhub.games.board.ChessGame
import com.aicode.partyhub.games.board.ChineseCheckersGame
import com.aicode.partyhub.games.board.DraughtsGame
import com.aicode.partyhub.games.board.GoGame
import com.aicode.partyhub.games.board.GomokuGame
import com.aicode.partyhub.games.board.ReversiGame
import com.aicode.partyhub.games.board.XiangqiGame
import com.aicode.partyhub.games.cards.DouDizhuGame
import java.io.File

object GameFactory {
    fun create(gameId: String, players: Int, seed: Int, difficulty: Int, options: Map<String, String> = emptyMap()): GameEngine? {
        val config = GameConfig(players = players, seed = seed, difficulty = difficulty, options = options)
        return when (gameId) {
            "reaction" -> ReactionGame(config)
            "color" -> ColorGame(config)
            "math" -> MathGame(config)
            "memory" -> MemoryGame(config)
            "snake" -> SnakeGame(config)
            "tank" -> TankGame(config)
            "bomber" -> BomberGame(config)
            "dodge" -> DodgeGame(config)
            "gomoku" -> GomokuGame(config)
            "xiangqi" -> XiangqiGame(config)
            "chess" -> ChessGame(config)
            "go" -> GoGame(config)
            "reversi" -> ReversiGame(config)
            "chinese_checkers" -> ChineseCheckersGame(config)
            "draughts" -> DraughtsGame(config)
            "doudizhu" -> DouDizhuGame(config)
            else -> null
        }
    }
}

/** 把随 APK 打包的引擎配置/模型按需复制到应用私有目录（assets 不可直接执行或映射）。 */
object EngineAssets {
    // aapt2 会解压以 .gz 结尾的资源并去掉后缀，因此先找 model.bin，再退回 model.bin.gz（两者 KataGo 都能按后缀正确解析）。
    private val MODEL_ASSETS = listOf("engines/model.bin", "engines/model.bin.gz")
    private const val CONFIG = "engines/gtp.cfg"

    fun ensure(context: Context): KataGoFiles? {
        val appContext = context.applicationContext
        val engine = KataGoEngine.locate(appContext) ?: return null
        val dir = File(appContext.filesDir, "engines").also { it.mkdirs() }
        val config = File(dir, "gtp.cfg")
        return try {
            if (!config.isFile || config.length() == 0L) copy(appContext, CONFIG, config)
            val model = ensureModel(appContext, dir) ?: return null
            KataGoFiles(engine, config, model)
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureModel(context: Context, dir: File): File? {
        MODEL_ASSETS.forEach { asset ->
            val target = File(dir, asset.substringAfterLast('/'))
            if (target.isFile && target.length() > 0L) return target
        }
        MODEL_ASSETS.forEach { asset ->
            val target = File(dir, asset.substringAfterLast('/'))
            try {
                copy(context, asset, target)
                return target
            } catch (_: Exception) {
                // 该命名不存在则尝试下一个
            }
        }
        return null
    }

    private fun copy(context: Context, asset: String, target: File) {
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    data class KataGoFiles(val engine: File, val config: File, val model: File) {
        fun isReady(): Boolean = engine.canExecute() && config.isFile && model.isFile
    }
}
