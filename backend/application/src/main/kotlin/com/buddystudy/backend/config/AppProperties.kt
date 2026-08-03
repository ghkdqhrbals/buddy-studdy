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
    var billing: Billing = Billing(),
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
        var userContentApiKey: String = "",
        var systemApiKey: String = "",
        var model: String = "gpt-5.4",
        var systemModel: String = "gpt-5.4",
        var embeddingModel: String = "text-embedding-3-small",
        var questionSimilarityThreshold: Double = 0.86,
        var questionSimilarityMaxAttempts: Int = 3,
        var gradingPolicy: String = "",
        var gradingPolicyVersion: String = "ai-judge-v1",
        var gradingMinConfidence: Double = 0.72,
        var gradingResponseStyle: String = "structured-brief-v1",
        var requestTimeoutSeconds: Long = 60,
        var requestMaxRetries: Int = 1,
        var gradingTimeoutSeconds: Long = 240,
    )
    data class Apns(
        var teamId: String = "",
        var keyId: String = "",
        var authKeyP8: String = "",
        var bundleId: String = "io.github.ghkdqhrbals.StudyMate",
    )
    data class Streams(
        var enabled: Boolean = true,
        var notificationRequestedKey: String = "notification.message.requested.v1",
        var accountWithdrawnKey: String = "identity.account.withdrawn.v1",
        var answerGradingRequestedKey: String = "study.answer-grading.requested.v1",
        var questionGenerationRequestedKey: String = "study.question-generation.requested.v1",
        var questionGenerationRollbackRequestedKey: String = "study.question-generation.rollback-requested.v1",
        var questionGeneratedKey: String = "study.question.generated.v1",
        var contentTranslationRequestedKey: String = "localization.content-translation.requested.v1",
        var questionPushRequestedKey: String = "notification.question-push.requested.v1",
        var questionViewedKey: String = "community.question.viewed.v1",
        var questionLikedKey: String = "community.question.liked.v1",
        var questionUnlikedKey: String = "community.question.unliked.v1",
        var questionCommentedKey: String = "community.question.commented.v1",
        var questionCommentDeletedKey: String = "community.question.comment-deleted.v1",
        var notificationRequestedMaxLen: Long = 1_000,
        var accountWithdrawnMaxLen: Long = 1_000,
        var answerGradingRequestedMaxLen: Long = 1_000,
        var questionGenerationRequestedMaxLen: Long = 1_000,
        var questionGenerationRollbackRequestedMaxLen: Long = 1_000,
        var questionGeneratedMaxLen: Long = 1_000,
        var contentTranslationRequestedMaxLen: Long = 1_000,
        var questionPushRequestedMaxLen: Long = 1_000,
        var questionViewedMaxLen: Long = 1_000,
        var questionLikedMaxLen: Long = 1_000,
        var questionUnlikedMaxLen: Long = 1_000,
        var questionCommentedMaxLen: Long = 1_000,
        var questionCommentDeletedMaxLen: Long = 1_000,
        var pushConsumerConcurrency: Int = 10,
    )
    data class Email(
        var verificationTtlSeconds: Long = 180,
        var host: String = "",
        var port: Int = 587,
        var username: String = "",
        var password: String = "",
        var from: String = "",
    )
    data class Translation(
        var baseUrl: String = "http://localhost:5001",
        var supportedLanguages: List<String> = listOf("ko", "en", "ja"),
        var providerOrder: List<String> = listOf("libretranslate", "openai"),
        var timeoutMs: Long = 5_000,
        var apiKey: String = "",
        var backfillEnabled: Boolean = true,
        var backfillBatchSize: Int = 20,
        var backfillPollMs: Long = 60_000,
    )
    data class OpenApi(var enabled: Boolean = false, var accessToken: String = "")
    data class Admin(var username: String = "admin", var password: String = "admin", var tokenHours: Long = 12)
    data class Monitoring(
        var environmentName: String = "production",
        var serviceName: String = "BuddyStudy backend",
        var schedulerReadinessEnabled: Boolean = true,
        var schedulerStaleThresholdMinutes: Long = 15,
        var schedulerStartupGraceMinutes: Long = 15,
        var schedulerMonitoredJobs: List<String> = listOf(
            "question-schedule",
            "event-outbox-dispatch",
            "user-stats-refresh",
            "answer-grading-watchdog",
        ),
    )
    data class Analytics(
        var datasource: AnalyticsDataSource = AnalyticsDataSource(),
    )
    data class AnalyticsDataSource(
        var url: String = "",
        var username: String = "",
        var password: String = "",
        var driverClassName: String = "",
        var databaseName: String = "",
    )
    data class Billing(
        var apple: Apple = Apple(),
    )
    data class Apple(
        var bundleId: String = "io.github.ghkdqhrbals.StudyMate",
        var appAppleId: Long = 6774108938,
        var rootCertificatesBase64: List<String> = emptyList(),
        var rootCertificateResources: List<String> = listOf(
            "classpath:apple/AppleRootCA-G2.cer.b64",
            "classpath:apple/AppleRootCA-G3.cer.b64",
        ),
        var enableOnlineChecks: Boolean = true,
        var allowXcodeEnvironment: Boolean = false,
    )
}

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(BuddyStudyProperties::class)
class PropertiesConfig
