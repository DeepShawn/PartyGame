package com.aicode.partyhub.net

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

interface Link {
    val id: String
    fun send(text: String)
    fun close()
}

interface LinkListener {
    fun onOpen(link: Link)
    fun onMessage(link: Link, text: String)
    fun onClosed(link: Link, reason: String?)
}

internal const val MAX_FRAME_BYTES = 64 * 1024
internal const val SEND_QUEUE_CAPACITY = 32
internal const val MAX_LINKS = 8

internal fun encodeUtf8(text: String, limit: Int = MAX_FRAME_BYTES): ByteArray {
    require(text.length <= limit) { "frame too large" }
    val buffer = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(text))
    require(buffer.remaining() <= limit) { "frame too large" }
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

internal fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()
