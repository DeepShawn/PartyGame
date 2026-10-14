package com.aicode.partyhub.game

import android.content.Context
import java.io.Closeable
import java.io.File

/** 国际象棋引擎封装：UCI 协议，思考时间与难度受限，进程级超时兜底。 */
class StockfishEngine(
    private val engineFile: File,
    private val workDir: File,
    private val onLog: (String) -> Unit = {},
) : Closeable {

    private var process: EngineProcess? = null

    private fun ensure(): EngineProcess {
        val current = process
        if (current != null && current.isRunning()) return current
        current?.close()
        val started = EngineProcess(engineFile, workingDir = workDir, tag = "stockfish", onLog = onLog)
        started.start()
        started.send("uci")
        if (started.readUntil("uciok", 10_000) == null) {
            started.close()
            error("Stockfish 启动失败（未响应 uciok）")
        }
        process = started
        return started
    }

    /** 返回 UCI 着法（如 e7e8q）或 null。skill 0..2 映射到棋力限制。 */
    @Synchronized
    fun bestMove(fen: String, movetimeMs: Int, skill: Int): String? {
        val engine = try {
            ensure()
        } catch (error: Exception) {
            onLog("stockfish: ${error.message}")
            return null
        }
        engine.send("setoption name Ponder value false")
        engine.send("setoption name Skill Level value ${skill.coerceIn(0, 20)}")
        engine.send("isready")
        if (engine.readUntil("readyok", 8_000) == null) {
            close()
            return null
        }
        engine.send("position fen $fen")
        engine.send("go movetime ${movetimeMs.coerceIn(50, 30_000)}")
        val line = engine.readUntil("bestmove", movetimeMs + 15_000L) ?: run {
            close()
            return null
        }
        val move = line.split(Regex("\\s+")).getOrNull(1) ?: return null
        if (move == "(none)" || !move.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) return null
        return move
    }

    override fun close() {
        process?.close()
        process = null
    }

    companion object {
        fun locate(context: Context): File? = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
            .takeIf { it.isFile && it.canExecute() }
    }
}

/** 围棋引擎封装：GTP 协议；历史棋谱由共享规则层给出，引擎只负责选点。 */
class KataGoEngine(
    private val engineFile: File,
    private val configFile: File,
    private val modelFile: File,
    private val workDir: File,
    private val onLog: (String) -> Unit = {},
) : Closeable {

    private var process: EngineProcess? = null

    private fun ensure(): EngineProcess {
        val current = process
        if (current != null && current.isRunning()) return current
        current?.close()
        val started = EngineProcess(
            engineFile,
            args = listOf("gtp", "-config", configFile.absolutePath, "-model", modelFile.absolutePath),
            workingDir = workDir,
            environment = mapOf("OMP_NUM_THREADS" to "2"),
            tag = "katago",
            onLog = onLog,
        )
        started.start()
        if (started.readUntil("=", 20_000) == null) {
            started.close()
            error("KataGo 启动失败（加载模型或配置超时）")
        }
        process = started
        return started
    }

    /** 让引擎执 color（"B"/"W"）走一步；moves 为 GTP 历史（如 B D4）。 */
    @Synchronized
    fun genMove(boardSize: Int, komi: Double, moves: List<String>, color: String): String? {
        val engine = try {
            ensure()
        } catch (error: Exception) {
            onLog("katago: ${error.message}")
            return null
        }
        engine.send("boardsize ${boardSize.coerceIn(7, 19)}")
        engine.send("komi $komi")
        engine.send("clear_board")
        moves.forEach { engine.send("play $it") }
        engine.send("genmove $color")
        val line = engine.readUntil("=", 30_000) ?: run {
            close()
            return null
        }
        val move = line.removePrefix("=").trim()
        if (move.isEmpty() || move.equals("resign", ignoreCase = true) || move.equals("pass", ignoreCase = true)) {
            return if (move.equals("pass", ignoreCase = true)) "pass" else null
        }
        if (!move.matches(Regex("[A-HJ-Ta-hj-t][1-9][0-9]?"))) return null
        return move
    }

    override fun close() {
        process?.close()
        process = null
    }

    companion object {
        fun locate(context: Context): File? = File(context.applicationInfo.nativeLibraryDir, "libkatago.so")
            .takeIf { it.isFile && it.canExecute() }
    }
}
