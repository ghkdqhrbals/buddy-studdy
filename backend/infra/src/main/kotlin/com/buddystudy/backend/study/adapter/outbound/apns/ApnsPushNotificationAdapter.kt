package com.buddystudy.backend.study.adapter.outbound.apns

import com.buddystudy.backend.common.application.security.JwtSupport
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystudy.backend.study.application.port.outbound.PushMessageType
import com.buddystudy.backend.study.application.port.outbound.PushQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.PushQuestionSender
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Component
class ApnsPushNotificationAdapter(
    private val properties: BuddyStudyProperties,
) : PushQuestionSender {
    override val type: PushMessageType = PushMessageType.APNS
    private val logger = LoggerFactory.getLogger(javaClass)
    private val timeout = Duration.ofSeconds(5)
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .version(HttpClient.Version.HTTP_2)
        .build()

    @PostConstruct
    fun logConfigurationStatus() {
        val missing = buildList {
            if (properties.apns.teamId.isBlank()) add("teamId")
            if (properties.apns.keyId.isBlank()) add("keyId")
            if (properties.apns.authKeyP8.isBlank()) add("authKeyP8")
            if (properties.apns.bundleId.isBlank()) add("bundleId")
        }
        if (missing.isEmpty()) {
            logger.info("apns_configuration_ready bundleId={}", properties.apns.bundleId)
        } else {
            logger.warn(
                "apns_configuration_incomplete missing={} bundleId={} pushDeliveryAvailable=false",
                missing.joinToString(","),
                properties.apns.bundleId.ifBlank { "-" },
            )
        }
    }

    override suspend fun sendQuestion(message: PushQuestionMessage) {
        require(message is ApnsQuestionMessage) { "APNs adapter cannot send ${message.type} messages." }
        val token = message.token.takeIf { it.isNotBlank() }
        if (token == null) {
            throw IllegalArgumentException("APNs token is missing for record ${message.recordId}.")
        }
        val startedAt = Instant.now()
        val tokenFingerprint = tokenFingerprint(token)
        val environment = message.environment.lowercase()
        val host = apnsHost(environment)
        logger.info(
            "apns_push_started recordId={} topic={} environment={} host={} bundleId={} tokenFingerprint={} pushCreatedAt={}",
            message.recordId,
            message.topic,
            environment,
            host,
            properties.apns.bundleId,
            tokenFingerprint,
            message.createdAt,
        )
        try {
            val jwt = apnsJwt()
            val request = buildRequest(message, jwt)
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            val completedAt = Instant.now()
            val durationMs = Duration.between(startedAt, completedAt).toMillis()
            val apnsId = response.headers().firstValue("apns-id").orElse(null)
            if (response.statusCode() !in 200..299) {
                logger.warn(
                    "apns_push_rejected recordId={} topic={} environment={} host={} tokenFingerprint={} status={} apnsId={} durationMs={} responseBody={}",
                    message.recordId,
                    message.topic,
                    environment,
                    host,
                    tokenFingerprint,
                    response.statusCode(),
                    apnsId,
                    durationMs,
                    response.body().take(MAX_LOG_BODY_CHARS),
                )
                throw IllegalStateException("APNs failed status=${response.statusCode()} body=${response.body()}")
            }
            val pushAgeMs = message.createdAt?.let { Duration.between(it, completedAt).toMillis() }
            logger.info(
                "apns_push_sent recordId={} topic={} environment={} host={} tokenFingerprint={} status={} apnsId={} durationMs={} pushCreatedAt={} apnsSentAt={} pushAgeMs={}",
                message.recordId,
                message.topic,
                environment,
                host,
                tokenFingerprint,
                response.statusCode(),
                apnsId,
                durationMs,
                message.createdAt,
                completedAt,
                pushAgeMs,
            )
        } catch (error: Exception) {
            logger.error(
                "apns_push_failed recordId={} topic={} environment={} host={} tokenFingerprint={} durationMs={} errorType={} error={}",
                message.recordId,
                message.topic,
                environment,
                host,
                tokenFingerprint,
                Duration.between(startedAt, Instant.now()).toMillis(),
                error.javaClass.name,
                error.message,
                error,
            )
            throw error
        }
    }

    internal fun buildRequest(message: ApnsQuestionMessage, jwt: String): HttpRequest {
        val environment = message.environment.lowercase()
        val host = apnsHost(environment)
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

    private fun apnsHost(environment: String): String =
        if (environment == "sandbox") "api.sandbox.push.apple.com" else "api.push.apple.com"

    private fun tokenFingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }

    internal fun buildPayloadJson(message: ApnsQuestionMessage): String {
        val original = message.payload.toJsonUnchecked()
        val originalBytes = original.utf8Size()
        if (originalBytes <= MAX_PAYLOAD_BYTES) {
            return original
        }

        val compacted = message.payload.compactedJson()
        logger.warn(
            "apns_payload_compacted recordId={} notificationId={} originalBytes={} payloadBytes={}",
            message.recordId,
            message.notificationId,
            originalBytes,
            compacted.utf8Size(),
        )
        return compacted
    }

    private fun apnsJwt(): String {
        if (properties.apns.teamId.isBlank() || properties.apns.keyId.isBlank() || properties.apns.authKeyP8.isBlank()) {
            throw IllegalStateException("APNs credentials are not configured.")
        }
        return JwtSupport.es256(
            header = linkedMapOf(
                "alg" to "ES256",
                "kid" to properties.apns.keyId,
            ),
            payload = linkedMapOf(
                "iss" to properties.apns.teamId,
                "iat" to Instant.now().epochSecond,
            ),
            privateKey = privateKey(),
        )
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

    private fun ApnsQuestionPayload.compactedJson(): String {
        val bodyCompacted = copy(
            aps = aps.copy(
                alert = aps.alert.copy(
                    body = aps.alert.body.truncatedToFit { candidate ->
                        copy(
                            aps = aps.copy(alert = aps.alert.copy(body = candidate)),
                        ).toJsonUnchecked()
                    },
                ),
            ),
        )
        val bodyCompactedJson = bodyCompacted.toJsonUnchecked()
        if (bodyCompactedJson.utf8Size() <= MAX_PAYLOAD_BYTES) {
            return bodyCompactedJson
        }

        val fullyCompacted = bodyCompacted.copy(
            aps = bodyCompacted.aps.copy(
                alert = bodyCompacted.aps.alert.copy(
                    title = bodyCompacted.aps.alert.title.truncatedToFit { candidate ->
                        bodyCompacted.copy(
                            aps = bodyCompacted.aps.copy(
                                alert = bodyCompacted.aps.alert.copy(title = candidate),
                            ),
                        ).toJsonUnchecked()
                    },
                ),
            ),
        )
        return fullyCompacted.toJsonUnchecked().also { payload ->
            require(payload.utf8Size() <= MAX_PAYLOAD_BYTES) {
                "APNs navigation metadata exceeds the $MAX_PAYLOAD_BYTES-byte payload limit."
            }
        }
    }

    private fun String.truncatedToFit(renderPayload: (String) -> String): String {
        if (renderPayload(this).utf8Size() <= MAX_PAYLOAD_BYTES) {
            return this
        }

        val codePointCount = codePointCount(0, length)
        var lowerBound = 0
        var upperBound = codePointCount
        var best = ""
        while (lowerBound <= upperBound) {
            val candidateLength = (lowerBound + upperBound) ushr 1
            val candidate = if (candidateLength >= codePointCount) {
                this
            } else {
                prefixCodePoints(candidateLength) + ELLIPSIS
            }
            if (renderPayload(candidate).utf8Size() <= MAX_PAYLOAD_BYTES) {
                best = candidate
                lowerBound = candidateLength + 1
            } else {
                upperBound = candidateLength - 1
            }
        }
        return best
    }

    private fun String.prefixCodePoints(count: Int): String =
        substring(0, offsetByCodePoints(0, count))

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private fun ApnsQuestionPayload.toJsonUnchecked(): String {
        val badge = aps.badge?.let { ""","badge":$it""" }.orEmpty()
        val notificationId = notificationId?.let { ""","notificationId":${jsonString(it)}""" }.orEmpty()
        return """
            {"aps":{"alert":{"title":${jsonString(aps.alert.title)},"body":${jsonString(aps.alert.body)}},"sound":${jsonString(aps.sound)}$badge},"deepLink":${jsonString(deepLink)}$notificationId}
        """.trimIndent()
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 4_096
        const val MAX_LOG_BODY_CHARS = 512
        const val ELLIPSIS = "…"
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException((error as? CompletionException)?.cause ?: error)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
