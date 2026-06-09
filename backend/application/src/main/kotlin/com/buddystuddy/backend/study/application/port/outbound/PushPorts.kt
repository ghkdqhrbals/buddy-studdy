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
    val landing: PushLanding
}

enum class PushLandingPage(val wireName: String) {
    STUDY_RECORD("studyRecord"),
}

data class PushLanding(
    val page: PushLandingPage,
    val recordId: String,
    val route: String,
    val topic: String,
)

data class ApnsAlert(
    val title: String,
    val body: String,
)

data class ApnsAps(
    val alert: ApnsAlert,
    val sound: String,
)

data class ApnsQuestionPayload(
    val aps: ApnsAps,
    val recordId: String,
    val topic: String,
    val landing: PushLanding,
)

data class ApnsQuestionMessage(
    val token: String,
    val environment: String,
    val payload: ApnsQuestionPayload,
) : PushQuestionMessage {
    override val type: PushMessageType = PushMessageType.APNS
    override val recordId: String get() = payload.recordId
    override val question: String get() = payload.aps.alert.body
    override val topic: String get() = payload.topic
    override val sound: String get() = payload.aps.sound
    override val landing: PushLanding get() = payload.landing
}

data class FcmQuestionMessage(
    override val recordId: String,
    override val question: String,
    override val topic: String,
    override val sound: String?,
    override val landing: PushLanding,
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
