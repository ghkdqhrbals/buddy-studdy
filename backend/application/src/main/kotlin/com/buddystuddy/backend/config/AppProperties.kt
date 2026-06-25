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
    var translation: Translation = Translation(),
    var openapi: OpenApi = OpenApi(),
    var admin: Admin = Admin(),
    var analytics: Analytics = Analytics(),
) {
    data class Auth(var jwtSecret: String = "", var accessTokenDays: Long = 90)
    data class Crypto(var masterKey: String = "")
    data class Scheduler(
        var enabled: Boolean = true,
        var pollMs: Long = 30_000,
        var maxPendingPerStudy: Int = 1,
        var batchSize: Int = 50,
        var processingTimeoutSeconds: Long = 300,
        var workerId: String = "",
    )
    data class OpenAI(
        var apiKey: String = "",
        var model: String = "gpt-5.4",
    )
    data class Apns(
        var teamId: String = "",
        var keyId: String = "",
        var authKeyP8: String = "",
        var bundleId: String = "io.github.ghkdqhrbals.StudyMate",
    )
    data class Streams(
        var enabled: Boolean = true,
        var pushPrefix: String = "push-v1",
        var viewPrefix: String = "view-v1",
        var notificationPrefix: String = "notification-v1",
        var createQuestionPrefix: String = "create-question-v1",
        var maxLen: Long = 100_000,
        var viewQueueCapacity: Int = 20_000,
        var viewPublisherConcurrency: Int = 4,
    )
    data class Email(var verificationTtlSeconds: Long = 180, var from: String = "")
    data class Translation(
        var baseUrl: String = "http://localhost:5001",
        var supportedLanguages: List<String> = listOf("ko", "en"),
    )
    data class OpenApi(var enabled: Boolean = false, var accessToken: String = "")
    data class Admin(var username: String = "admin", var password: String = "admin", var tokenHours: Long = 12)
    data class Analytics(var datasource: AnalyticsDataSource = AnalyticsDataSource())
    data class AnalyticsDataSource(
        var url: String = "",
        var username: String = "",
        var password: String = "",
        var driverClassName: String = "",
    )
}

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(BuddyStuddyProperties::class)
class PropertiesConfig
