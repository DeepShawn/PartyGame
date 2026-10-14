package com.aicode.partyhub.security

import com.bitbucket.thinbus.srp6.js.HexHashedVerifierGenerator
import com.bitbucket.thinbus.srp6.js.SRP6JavaClientSessionSHA256
import com.bitbucket.thinbus.srp6.js.SRP6JavascriptServerSessionSHA256
import com.nimbusds.srp6.SRP6CryptoParams
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class AuthChallenge(val type: String = "challenge", val roomId: String, val sid: String, val salt: String, val B: String)
@Serializable
data class ClientProof(val type: String = "proof", val sid: String, val A: String, val M1: String)
@Serializable
data class ServerProof(val type: String = "verified", val sid: String, val M2: String)
@Serializable
data class EncryptedPacket(val type: String = "encrypted", val sid: String, val n: Int, val data: String)

data class AuthResult(val proof: ServerProof, val cipher: SessionCipher)

private val params = SRP6CryptoParams.getInstance(2048, "SHA-256")
private val secureRandom = SecureRandom()
private val hexadecimal = Regex("[0-9a-f]+")
private fun randomHex(): String = BigInteger(256, secureRandom).toString(16)
private fun requireHex(value: String, max: Int) {
    require(value.length in 1..max && hexadecimal.matches(value)) { "无效认证数据" }
}

class RoomAuthHost(private val roomId: String, password: String) {
    private val session = SRP6JavascriptServerSessionSHA256(params.N.toString(), params.g.toString())
    private val sid = randomHex()
    private val salt = randomHex()
    private val verifier = HexHashedVerifierGenerator(params).generateVerifier(salt, roomId, password)
    private var used = false
    private val challenge = AuthChallenge(roomId = roomId, sid = sid, salt = salt, B = session.step1(roomId, salt, verifier))
    fun challenge(): AuthChallenge = challenge
    fun verify(proof: ClientProof): AuthResult {
        check(!used) { "认证已过期" }
        used = true
        require(proof.type == "proof" && proof.sid == sid)
        requireHex(proof.A, 512)
        requireHex(proof.M1, 64)
        val evidence = session.step2(proof.A, proof.M1).trimStart('0').ifEmpty { "0" }
        val cipher = SessionCipher(roomId, sid, session.getSessionKey(true), true)
        return AuthResult(ServerProof(sid = sid, M2 = evidence), cipher)
    }
}

class RoomAuthClient(private val roomId: String, private val password: String) {
    private val session = SRP6JavaClientSessionSHA256(params.N.toString(), params.g.toString())
    private var sid: String? = null
    private var resolvedRoomId: String? = null
    private var started = false
    private var finished = false

    fun answer(challenge: AuthChallenge): ClientProof {
        check(!started)
        require(challenge.type == "challenge") { "认证消息无效" }
        val identity = if (roomId.isBlank()) challenge.roomId else roomId
        require(identity.isNotEmpty()) { "房间不匹配" }
        require(challenge.roomId == identity) { "房间不匹配" }
        requireHex(challenge.sid, 64)
        requireHex(challenge.salt, 64)
        requireHex(challenge.B, 512)
        started = true
        session.step1(identity, password)
        sid = challenge.sid
        resolvedRoomId = identity
        session.step2(challenge.salt, challenge.B)
        return ClientProof(sid = challenge.sid, A = session.publicClientValue, M1 = session.clientEvidenceMessage)
    }

    fun finish(proof: ServerProof): SessionCipher {
        check(!finished && sid != null)
        finished = true
        require(proof.type == "verified" && proof.sid == sid)
        requireHex(proof.M2, 64)
        session.step3(proof.M2)
        return SessionCipher(resolvedRoomId ?: roomId, sid!!, session.getSessionKey(true), false)
    }
}

class SessionCipher(private val roomId: String, private val sid: String, keyHex: String, host: Boolean) {
    private val sendDirection = if (host) "host" else "guest"
    private val receiveDirection = if (host) "guest" else "host"
    private val sendKey = derive(keyHex, sendDirection)
    private val receiveKey = derive(keyHex, receiveDirection)
    private var sent = 0
    private var received = -1

    @Synchronized fun seal(text: String): EncryptedPacket {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 28_000) { "消息过大" }
        check(sent < 1_000_000_000) { "请重新连接房间" }
        val sequence = sent++
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(sequence)))
        cipher.updateAAD(aad(sendDirection, sequence))
        return EncryptedPacket(sid = sid, n = sequence, data = hex(cipher.doFinal(bytes)))
    }

    @Synchronized fun open(packet: EncryptedPacket): String {
        require(packet.type == "encrypted" && packet.sid == sid && packet.n == received + 1) { "消息顺序或会话无效" }
        require(packet.data.length in 32..56_032 && packet.data.length % 2 == 0)
        requireHex(packet.data, 56_032)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, nonce(packet.n)))
        cipher.updateAAD(aad(receiveDirection, packet.n))
        val plain = cipher.doFinal(unhex(packet.data)).toString(Charsets.UTF_8)
        received = packet.n
        return plain
    }

    private fun aad(direction: String, sequence: Int) = "partygame/1|$roomId|$sid|$direction|$sequence".toByteArray(Charsets.UTF_8)
    private fun nonce(sequence: Int) = ByteBuffer.allocate(12).putInt(0).putLong(sequence.toLong()).array()
    private fun derive(key: String, direction: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(key.padStart(64, '0')), "HmacSHA256"))
        return mac.doFinal("partygame/1|$roomId|$sid|$direction\u0001".toByteArray(Charsets.UTF_8))
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
