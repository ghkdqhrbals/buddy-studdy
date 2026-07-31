package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.backend.common.application.outbox.PublishedStreamRecord
import java.time.Instant

data class QuestionPushRequest(
    val recordId: Long,
    val notificationId: Long,
    val studyId: Long?,
    val deviceId: String,
    val userId: Long?,
    val question: String,
    val expectedAnswerHint: String?,
    val topic: String,
    val difficultyLevel: Int,
    val language: String,
    val sound: String?,
    val intervalMinutes: Int,
    val title: String? = null,
    val body: String? = null,
    val deepLink: String? = null,
    val createdAt: Instant = Instant.now(),
) {
    val eventId: String get() = "question-push-$notificationId-$deviceId"
}

interface QuestionPushPublishPort {
    suspend fun publishPush(request: QuestionPushRequest): PublishedStreamRecord?
}

enum class PushMessageType {
    APNS,
    FCM,
}

sealed interface PushQuestionMessage {
    val type: PushMessageType
    val recordId: String
    val notificationId: String?
    val question: String
    val topic: String
    val sound: String?
    val deepLink: String
    val createdAt: Instant?
}

data class ApnsAlert(
    val title: String,
    val body: String,
)

data class ApnsAps(
    val alert: ApnsAlert,
    val sound: String,
    val badge: Int? = null,
)

data class ApnsQuestionPayload(
    val aps: ApnsAps,
    val deepLink: String,
    val notificationId: String? = null,
)

data class ApnsQuestionMessage(
    override val recordId: String,
    override val notificationId: String? = null,
    override val topic: String,
    val token: String,
    val environment: String,
    val payload: ApnsQuestionPayload,
    override val createdAt: Instant? = null,
) : PushQuestionMessage {
    override val type: PushMessageType = PushMessageType.APNS
    override val question: String get() = payload.aps.alert.body
    override val sound: String get() = payload.aps.sound
    override val deepLink: String get() = payload.deepLink
}

data class FcmQuestionMessage(
    override val recordId: String,
    override val notificationId: String? = null,
    override val question: String,
    override val topic: String,
    override val sound: String?,
    override val deepLink: String,
    val token: String,
    override val createdAt: Instant? = null,
) : PushQuestionMessage {
    override val type: PushMessageType = PushMessageType.FCM
}

interface PushNotificationPort {
    suspend fun sendQuestion(message: PushQuestionMessage)
    suspend fun pushForAll(messages: Iterable<PushQuestionMessage>) {
        for (message in messages) {
            sendQuestion(message)
        }
    }
}

interface PushQuestionSender {
    val type: PushMessageType
    suspend fun sendQuestion(message: PushQuestionMessage)
}
