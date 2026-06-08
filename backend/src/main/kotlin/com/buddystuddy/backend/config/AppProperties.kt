package com.buddystuddy.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "buddystuddy")
data class BuddyStuddyProperties(
    var auth: Auth = Auth(),
    var crypto: Crypto = Crypto(),
    var scheduler: Scheduler = Scheduler(),
    var openai: OpenAI = OpenAI(),
    var apns: Apns = Apns(),
    var streams: Streams = Streams(),
    var email: Email = Email(),
    var openapi: OpenApi = OpenApi(),
) {
    data class Auth(var jwtSecret: String = "", var accessTokenDays: Long = 90)
    data class Crypto(var masterKey: String = "")
    data class Scheduler(var enabled: Boolean = true, var pollMs: Long = 30_000, var maxPendingPerStudy: Int = 1)
    data class OpenAI(var apiKey: String = "", var model: String = "gpt-5.4")
    data class Apns(
        var teamId: String = "",
        var keyId: String = "",
        var authKeyP8: String = "",
        var bundleId: String = "io.github.ghkdqhrbals.StudyMate",
    )
    data class Streams(
        var enabled: Boolean = true,
        var coordinatorBaseUrl: String = "https://coordinator.ghkdqhrbals.org",
        var coordinatorUsername: String = "admin",
        var coordinatorPassword: String = "",
        var pushPrefix: String = "bs-push-v1",
        var viewPrefix: String = "bs-view-content-v1",
        var actionPrefix: String = "bs-question-action-v1",
        var maxLen: Long = 100_000,
    )
    data class Email(var verificationTtlSeconds: Long = 180, var from: String = "")
    data class OpenApi(var enabled: Boolean = false, var accessToken: String = "")
}

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(BuddyStuddyProperties::class)
class PropertiesConfig
