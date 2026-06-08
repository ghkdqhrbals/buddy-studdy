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

interface PushNotificationPort {
    fun sendQuestion(fields: Map<String, String>)
}
