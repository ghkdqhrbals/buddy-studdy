package com.buddystuddy.study.domain

import com.buddystuddy.domain.QuestionEntity
import com.buddystuddy.domain.ScheduleEntity
import java.time.Instant

class StudyRoom private constructor(
    val schedule: ScheduleEntity,
    private val pendingCount: Long,
) {
    val topic: String get() = schedule.topic
    val difficultyLevel: Int get() = schedule.difficultyLevel
    val openaiModel: String get() = schedule.openaiModel
    val appLanguage: String get() = schedule.appLanguage
    val customPrompt: String get() = schedule.customPrompt

    fun assertCanCreateQuestion(maxPendingPerStudy: Int) {
        if (pendingCount >= maxPendingPerStudy) {
            throw StudyRoomPendingLimitExceeded("A pending question already exists for this study.")
        }
    }

    fun createQuestion(
        question: String,
        hint: String?,
        source: String,
        now: Instant = Instant.now(),
    ) = QuestionEntity(
        deviceId = schedule.deviceId,
        userId = schedule.userId,
        question = question,
        hint = hint,
        topic = schedule.topic,
        difficultyLevel = schedule.difficultyLevel,
        scheduledFor = now,
        sentAt = now,
        status = "ungraded",
        source = source,
        publicQuestion = schedule.questionPublic,
        createdAt = now,
        updatedAt = now,
    )

    companion object {
        fun of(schedule: ScheduleEntity, pendingCount: Long) = StudyRoom(schedule, pendingCount)
    }
}

class StudyRoomPendingLimitExceeded(message: String) : RuntimeException(message)
