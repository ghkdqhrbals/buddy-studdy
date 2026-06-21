package com.buddystuddy.backend.study.application.port.outbound

import java.time.Instant

data class QuestionPushRequest(
    val recordId: Long,
    val notificationId: Long? = null,
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
)

interface QuestionPushPublishPort {
    fun publishPush(request: QuestionPushRequest): Boolean
}

data class QuestionPushOutboxCommand(
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
    val createdAt: Instant = Instant.now(),
) {
    fun toRequest(recordId: Long): QuestionPushRequest =
        QuestionPushRequest(
            recordId = recordId,
            studyId = studyId,
            createdAt = createdAt,
            deviceId = deviceId,
            userId = userId,
            question = question,
            expectedAnswerHint = expectedAnswerHint,
            topic = topic,
            difficultyLevel = difficultyLevel,
            language = language,
            sound = sound,
            intervalMinutes = intervalMinutes,
        )
}

interface QuestionPushOutboxPort {
    fun enqueue(request: QuestionPushRequest, now: Instant = Instant.now()): Long
}

interface QuestionPushOutboxDispatchPort {
    fun dispatchOutbox(outboxId: Long)
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
) : PushQuestionMessage {
    override val type: PushMessageType = PushMessageType.FCM
}

interface PushNotificationPort {
    fun sendQuestion(message: PushQuestionMessage)
    fun pushForAll(messages: Iterable<PushQuestionMessage>) {
        messages.forEach(::sendQuestion)
    }
}

interface PushQuestionSender {
    val type: PushMessageType
    fun sendQuestion(message: PushQuestionMessage)
}
