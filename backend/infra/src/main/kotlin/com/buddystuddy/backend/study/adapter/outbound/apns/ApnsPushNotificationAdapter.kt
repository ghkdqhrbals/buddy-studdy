package com.buddystuddy.backend.study.adapter.outbound.apns

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystuddy.backend.study.application.port.outbound.PushMessageType
import com.buddystuddy.backend.study.application.port.outbound.PushQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.PushQuestionSender
import io.jsonwebtoken.Jwts
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
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

@Component
class ApnsPushNotificationAdapter(
    private val properties: BuddyStuddyProperties,
) : PushQuestionSender {
    override val type: PushMessageType = PushMessageType.APNS
    private val logger = LoggerFactory.getLogger(javaClass)
    private val timeout = Duration.ofSeconds(5)
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .version(HttpClient.Version.HTTP_2)
        .build()

    override fun sendQuestion(message: PushQuestionMessage) {
        require(message is ApnsQuestionMessage) { "APNs adapter cannot send ${message.type} messages." }
        val token = message.token.takeIf { it.isNotBlank() }
        if (token == null) {
            logger.warn("apns_push_skipped_missing_token recordId={}", message.recordId)
            return
        }
        val jwt = apnsJwt()
        val request = buildRequest(message, jwt)
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("APNs failed status=${response.statusCode()} body=${response.body()}")
        }
        logger.info("apns_push_sent recordId={} topic={} status={}", message.recordId, message.topic, response.statusCode())
    }

    internal fun buildRequest(message: ApnsQuestionMessage, jwt: String): HttpRequest {
        val environment = message.environment.lowercase()
        val host = if (environment == "sandbox") "api.sandbox.push.apple.com" else "api.push.apple.com"
        val body = buildPayloadJson(message)
        return HttpRequest.newBuilder()
            .uri(URI.create("https://$host/3/device/${message.token}"))
            .timeout(timeout)
            .header("authorization", "bearer $jwt")
            .header("apns-topic", properties.apns.bundleId)
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    internal fun buildPayloadJson(message: ApnsQuestionMessage): String = message.payload.toJson()

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

    private fun ApnsQuestionPayload.toJson(): String {
        val badge = aps.badge?.let { ""","badge":$it""" }.orEmpty()
        val notificationId = notificationId?.let { ""","notificationId":${jsonString(it)}""" }.orEmpty()
        return """
            {"aps":{"alert":{"title":${jsonString(aps.alert.title)},"body":${jsonString(aps.alert.body)}},"sound":${jsonString(aps.sound)}$badge},"deepLink":${jsonString(deepLink)}$notificationId}
        """.trimIndent()
    }
}
