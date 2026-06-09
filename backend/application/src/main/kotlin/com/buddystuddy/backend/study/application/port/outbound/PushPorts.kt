package com.buddystuddy.backend.study.application.port.outbound

import java.time.Instant

data class QuestionPushRequest(
    val recordId: Long,
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
)

interface QuestionPushPublishPort {
    fun publishPush(request: QuestionPushRequest): Boolean
}

enum class PushMessageType {
    APNS,
    FCM,
}

sealed interface PushQuestionMessage {
    val type: PushMessageType
    val recordId: String
    val question: String
    val topic: String
    val sound: String?
}

data class ApnsQuestionMessage(
    override val recordId: String,
    override val question: String,
    override val topic: String,
    override val sound: String?,
    val token: String,
    val environment: String,
) : PushQuestionMessage {
    override val type: PushMessageType = PushMessageType.APNS
}

data class FcmQuestionMessage(
    override val recordId: String,
    override val question: String,
    override val topic: String,
    override val sound: String?,
    val token: String,
) : PushQuestionMessage {
    override val type: PushMessageType = PushMessageType.FCM
}

interface PushNotificationPort {
    fun sendQuestion(message: PushQuestionMessage)
}

interface PushQuestionSender {
    val type: PushMessageType
    fun sendQuestion(message: PushQuestionMessage)
}
