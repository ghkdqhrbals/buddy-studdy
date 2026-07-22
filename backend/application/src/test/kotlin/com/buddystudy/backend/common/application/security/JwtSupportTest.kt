package com.buddystudy.backend.common.application.security

import kotlinx.coroutines.runBlocking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class JwtSupportTest {
    @Test
    fun `es256 returns a compact JWT with a JOSE signature`(): Unit = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val token = JwtSupport.es256(
            header = linkedMapOf("alg" to "ES256", "kid" to "key-id"),
            payload = linkedMapOf("iss" to "team-id", "iat" to 1_783_000_000),
            privateKey = keyPair.private,
        )

        val parts = token.split(".")
        assertThat(parts).hasSize(3)
        assertThat(Base64.getUrlDecoder().decode(parts[2])).hasSize(64)
        assertThat(verifyEs256(parts, keyPair.public as ECPublicKey)).isTrue()
    }

    private fun verifyEs256(parts: List<String>, publicKey: ECPublicKey): Boolean {
        val joseSignature = Base64.getUrlDecoder().decode(parts[2])
        val derSignature = joseToDer(joseSignature)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
        return signature.verify(derSignature)
    }

    private fun joseToDer(signature: ByteArray): ByteArray {
        val r = encodeDerInteger(signature.copyOfRange(0, 32))
        val s = encodeDerInteger(signature.copyOfRange(32, 64))
        val body = byteArrayOf(0x02) + derLength(r.size) + r + byteArrayOf(0x02) + derLength(s.size) + s
        return byteArrayOf(0x30) + derLength(body.size) + body
    }

    private fun encodeDerInteger(value: ByteArray): ByteArray {
        val trimmed = value.dropWhile { it == 0.toByte() }.ifEmpty { listOf(0.toByte()) }.toByteArray()
        return if ((trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
    }

    private fun derLength(length: Int): ByteArray =
        if (length < 0x80) {
            byteArrayOf(length.toByte())
        } else {
            byteArrayOf(0x81.toByte(), length.toByte())
        }
}
