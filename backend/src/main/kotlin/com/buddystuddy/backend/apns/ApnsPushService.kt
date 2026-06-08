package com.buddystuddy.backend.apns

import com.buddystuddy.backend.config.BuddyStuddyProperties
import io.jsonwebtoken.Jwts
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.Date

@Service
class ApnsPushService(
    private val properties: BuddyStuddyProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_2)
        .build()

    fun sendQuestion(fields: Map<String, String>) {
        val token = fields["apnsToken"]?.takeIf { it.isNotBlank() }
        if (token == null) {
            logger.warn("apns_push_skipped_missing_token recordId={}", fields["recordId"])
            return
        }
        val jwt = apnsJwt()
        val environment = fields["apnsEnvironment"]?.lowercase()
        val host = if (environment == "sandbox") "api.sandbox.push.apple.com" else "api.push.apple.com"
        val question = fields["question"] ?: "A new study question is ready."
        val body = """
            {"aps":{"alert":{"title":"BuddyStuddy","body":${jsonString(question)}},"sound":${jsonString(fields["sound"]?.takeIf { it.isNotBlank() } ?: "default")}},"recordId":${jsonString(fields["recordId"] ?: "")},"topic":${jsonString(fields["topic"] ?: "")}}
        """.trimIndent()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://$host/3/device/$token"))
            .timeout(Duration.ofSeconds(15))
            .header("authorization", "bearer $jwt")
            .header("apns-topic", properties.apns.bundleId)
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("APNs failed status=${response.statusCode()} body=${response.body()}")
        }
        logger.info("apns_push_sent recordId={} topic={} status={}", fields["recordId"], fields["topic"], response.statusCode())
    }

    private fun apnsJwt(): String {
        if (properties.apns.teamId.isBlank() || properties.apns.keyId.isBlank() || properties.apns.authKeyP8.isBlank()) {
            throw IllegalStateException("APNs credentials are not configured.")
        }
        return Jwts.builder()
            .header().keyId(properties.apns.keyId).and()
            .issuer(properties.apns.teamId)
            .issuedAt(Date())
            .signWith(privateKey(), Jwts.SIG.ES256)
            .compact()
    }

    private fun privateKey(): ECPrivateKey {
        val keyText = properties.apns.authKeyP8.trim()
        val pem = if (keyText.contains("BEGIN PRIVATE KEY")) keyText else String(Base64.getDecoder().decode(keyText))
        val der = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(der))
        return KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
    }

    private fun jsonString(value: String): String =
        "\"" + value.flatMap {
            when (it) {
                '\\' -> listOf('\\', '\\')
                '"' -> listOf('\\', '"')
                '\n' -> listOf('\\', 'n')
                '\r' -> listOf('\\', 'r')
                '\t' -> listOf('\\', 't')
                else -> listOf(it)
            }
        }.joinToString("") + "\""
}
