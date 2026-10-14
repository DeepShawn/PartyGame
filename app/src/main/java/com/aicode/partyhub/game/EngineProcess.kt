package com.aicode.partyhub.game

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生 UCI/GTP 引擎进程：仅在房主设备上运行，stdin/stdout 按行通信。
 * 引擎以 lib*.so 形式打包，从 nativeLibraryDir 直接执行（Android 10+ 禁止执行 filesDir）。
 */
class EngineProcess(
    private val binary: File,
    private val args: List<String> = emptyList(),
    private val workingDir: File,
    private val environment: Map<String, String> = emptyMap(),
    private val tag: String = "engine",
    private val onLog: (String) -> Unit = {},
) : Closeable {
    private val binaryPath = binary.absolutePath
    private val lines = LinkedBlockingQueue<String>()
    private val closed = AtomicBoolean()
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    fun start() {
        check(!closed.get()) { "引擎已关闭" }
        check(process == null) { "引擎已在运行" }
        check(binary.canExecute()) { "引擎文件不可执行: ${binary.name}" }
        val builder = ProcessBuilder(listOf(binaryPath) + args)
        builder.directory(workingDir.also { it.mkdirs() })
        builder.environment().putAll(environment)
        builder.redirectErrorStream(true)
        val started = builder.start()
        process = started
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
        Thread({
            try {
                BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).useLines { sequence ->
                    sequence.forEach { line ->
                        onLog(line)
                        lines.put(line)
                    }
                }
            } catch (_: Exception) {
            } finally {
                lines.put("__eof__")
            }
        }, "engine-reader-$tag").apply { isDaemon = true; start() }
    }

    fun send(line: String) {
        val target = writer ?: error("引擎未启动")
        target.write(line)
        target.newLine()
        target.flush()
    }

    /** 等待下一行输出；超时返回 null；进程退出返回 null。 */
    fun readLine(timeoutMs: Long): String? {
        val value = lines.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        return if (value == "__eof__") null else value
    }

    fun readUntil(prefix: String, timeoutMs: Long): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return null
            val line = readLine(remaining) ?: return null
            if (line.startsWith(prefix)) return line
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { writer?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        try {
            if (process?.waitFor(1500, TimeUnit.MILLISECONDS) == false) process?.destroyForcibly()
        } catch (_: Exception) {
        }
        process = null
        writer = null
    }
}
