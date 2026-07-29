package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishedStreamRecord
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
) {
    val eventId: String get() = "question-push-${notificationId ?: recordId}-$deviceId"
}

interface QuestionPushPublishPort {
    suspend fun publishPush(request: QuestionPushRequest): PublishedStreamRecord?
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

interface QuestionPushOutboxAppendPort {
    suspend fun enqueue(request: QuestionPushRequest, now: Instant = Instant.now()): Long
}

interface QuestionPushOutboxPort : QuestionPushOutboxAppendPort {
    suspend fun claim(id: Long, now: Instant, staleBefore: Instant): ClaimedQuestionPushOutbox?
    suspend fun claimBatch(now: Instant, staleBefore: Instant, limit: Int): List<ClaimedQuestionPushOutbox>
    suspend fun markPublished(
        id: Long,
        claimToken: String,
        publication: PublishedStreamRecord,
        publishedAt: Instant,
    ): Boolean
    suspend fun markRetry(
        id: Long,
        claimToken: String,
        attempts: Int,
        nextAttemptAt: Instant,
        error: String,
        updatedAt: Instant,
    ): Boolean
}

data class ClaimedQuestionPushOutbox(
    val id: Long,
    val request: QuestionPushRequest,
    val attempts: Int,
    val createdAt: Instant,
    val claimToken: String,
)

fun Long.toQuestionPushOutboxReference(): OutboxReference = OutboxReference(OutboxType.QUESTION_PUSH, this)

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
