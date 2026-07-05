package com.buddystudy.backend.common.application.security

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object JwtSupport {
    private val mapper = jacksonObjectMapper()
    private val mapType = object : TypeReference<Map<String, Any?>>() {}
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    fun hs256(payload: Map<String, Any?>, secret: ByteArray, header: Map<String, Any?> = defaultHeader("HS256")): String {
        val content = encodedJson(header) + "." + encodedJson(payload)
        val signature = hmacSha256(content.toByteArray(Charsets.UTF_8), secret)
        return "$content.${base64Url(signature)}"
    }

    fun verifyHs256(raw: String, secret: ByteArray): Map<String, Any?> {
        val parts = raw.split(".")
        require(parts.size == 3) { "JWT must have three parts." }

        val content = "${parts[0]}.${parts[1]}"
        val expected = hmacSha256(content.toByteArray(Charsets.UTF_8), secret)
        val actual = urlDecoder.decode(parts[2])
        require(MessageDigest.isEqual(expected, actual)) { "JWT signature is invalid." }

        return mapper.readValue(String(urlDecoder.decode(parts[1]), Charsets.UTF_8), mapType)
    }

    fun es256(payload: Map<String, Any?>, privateKey: PrivateKey, header: Map<String, Any?>): String {
        val content = encodedJson(header) + "." + encodedJson(payload)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(content.toByteArray(Charsets.UTF_8))
        return "$content.${base64Url(derToJose(signature.sign(), 32))}"
    }

    private fun defaultHeader(alg: String): Map<String, Any?> =
        linkedMapOf("alg" to alg, "typ" to "JWT")

    private fun encodedJson(value: Map<String, Any?>): String =
        base64Url(mapper.writeValueAsBytes(value))

    private fun hmacSha256(content: ByteArray, secret: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(content)
    }

    private fun base64Url(value: ByteArray): String = urlEncoder.encodeToString(value)

    private fun derToJose(der: ByteArray, partLength: Int): ByteArray {
        require(der.size > 8 && der[0] == 0x30.toByte()) { "Invalid DER ECDSA signature." }
        val reader = DerReader(der, 1)
        val sequenceLength = reader.readLength()
        require(reader.index + sequenceLength == der.size) { "Invalid DER ECDSA sequence length." }
        require(reader.readByte() == 0x02.toByte()) { "Invalid DER ECDSA R marker." }
        val r = reader.readInteger()
        require(reader.readByte() == 0x02.toByte()) { "Invalid DER ECDSA S marker." }
        val s = reader.readInteger()
        return unsignedFixed(r, partLength) + unsignedFixed(s, partLength)
    }

    private fun unsignedFixed(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(raw.size <= length) { "ECDSA integer is too large." }
        return ByteArray(length - raw.size) + raw
    }

    private class DerReader(private val der: ByteArray, var index: Int) {
        fun readByte(): Byte = der[index++]

        fun readLength(): Int {
            val first = readByte().toInt() and 0xff
            if (first < 0x80) {
                return first
            }
            val bytes = first and 0x7f
            require(bytes in 1..4) { "Unsupported DER length." }
            var length = 0
            repeat(bytes) {
                length = (length shl 8) or (readByte().toInt() and 0xff)
            }
            return length
        }

        fun readInteger(): BigInteger {
            val length = readLength()
            val bytes = der.copyOfRange(index, index + length)
            index += length
            return BigInteger(1, bytes)
        }
    }
}
