package com.buddystudy.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "buddystudy")
data class BuddyStudyProperties(
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
    var monitoring: Monitoring = Monitoring(),
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
        var embeddingModel: String = "text-embedding-3-small",
        var questionSimilarityThreshold: Double = 0.86,
        var questionSimilarityMaxAttempts: Int = 3,
    )
    data class Apns(
        var teamId: String = "",
        var keyId: String = "",
        var authKeyP8: String = "",
        var bundleId: String = "io.github.ghkdqhrbals.StudyMate",
    )
    data class Streams(
        var enabled: Boolean = true,
        var key: String = "buddystudy-events-v1",
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
    data class Monitoring(
        var slackWebhookUrl: String = "",
        var environmentName: String = "production",
        var serviceName: String = "BuddyStudy backend",
        var adminBaseUrl: String = "",
        var slackTimeoutMs: Long = 5_000,
        var schedulerFailureAlertRepeatSeconds: Long = 300,
        var schedulerReadinessEnabled: Boolean = true,
        var schedulerStaleThresholdMinutes: Long = 15,
        var schedulerStartupGraceMinutes: Long = 15,
        var schedulerMonitoredJobs: List<String> = listOf(
            "question-schedule",
            "question-push-outbox-dispatch",
            "user-stats-refresh",
            "admin-analytics-recent",
        ),
    )
    data class Analytics(
        var enabled: Boolean = true,
        var recentDays: Long = 2,
        var correctionDays: Long = 30,
        var datasource: AnalyticsDataSource = AnalyticsDataSource(),
    )
    data class AnalyticsDataSource(
        var url: String = "",
        var username: String = "",
        var password: String = "",
        var driverClassName: String = "",
        var databaseName: String = "",
    )
}

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(BuddyStudyProperties::class)
class PropertiesConfig
