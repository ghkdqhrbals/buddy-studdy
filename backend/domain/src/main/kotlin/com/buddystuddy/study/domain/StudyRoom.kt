package com.buddystuddy.study.domain

import java.time.Instant

class StudyRoom private constructor(
    val schedule: StudyRoomSchedule,
    private val pendingCount: Long,
) {
    val topic: String get() = schedule.topic
    val difficultyLevel: Int get() = schedule.difficultyLevel
    val openaiModel: String get() = schedule.openaiModel
    val appLanguage: String get() = schedule.appLanguage
    val customPrompt: String get() = schedule.customPrompt

    fun canCreateQuestion(maxPendingPerStudy: Int) {
        if (pendingCount >= maxPendingPerStudy) {
            throw StudyRoomPendingLimitExceeded("A pending question already exists for this study.")
        }
    }

    fun createQuestion(
        question: String,
        hint: String?,
        source: String,
        now: Instant = Instant.now(),
    ) = StudyRoomQuestionDraft(
        studyId = schedule.id,
        deviceId = schedule.deviceId,
        userId = schedule.userId,
        question = question,
        hint = hint,
        topic = schedule.topic,
        language = schedule.appLanguage,
        difficultyLevel = schedule.difficultyLevel,
        scheduledFor = now,
        sentAt = now,
        status = "ungraded",
        source = source,
        publicQuestion = true,
        createdAt = now,
        updatedAt = now,
    )

    companion object {
        fun of(schedule: StudyRoomSchedule, pendingCount: Long) = StudyRoom(schedule, pendingCount)
    }
}

class StudyRoomPendingLimitExceeded(message: String) : RuntimeException(message)

data class StudyRoomSchedule(
    val id: Long,
    val deviceId: String,
    val userId: Long,
    val topic: String,
    val difficultyLevel: Int,
    val openaiModel: String,
    val appLanguage: String,
    val customPrompt: String,
)

data class StudyRoomQuestionDraft(
    val studyId: Long,
    val deviceId: String,
    val userId: Long,
    val question: String,
    val hint: String?,
    val topic: String,
    val language: String,
    val difficultyLevel: Int,
    val scheduledFor: Instant,
    val sentAt: Instant,
    val status: String,
    val source: String,
    val publicQuestion: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
