package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.study.application.model.StudyPageResponse
import com.buddystuddy.backend.study.application.model.StudyRoomResponse
import com.buddystuddy.backend.study.application.model.toRecordResponse
import com.buddystuddy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystuddy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.study.domain.StudyRoomSettings
import com.buddystuddy.study.domain.StudyRoomSettingsCommand
import com.buddystuddy.study.domain.StudyRoomSettingsState
import com.buddystuddy.study.domain.StudyRoomSettingsUpdate
import com.buddystuddy.study.domain.entity.StudyEntity
import com.buddystuddy.study.domain.StudyRecord
import com.buddystuddy.study.domain.StudyRecordState
import com.buddystuddy.study.domain.StudyRecordStats
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudySyncService(
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
) : StudySyncUseCase {
    @Transactional(readOnly = true)
    override fun study(principal: Principal, limit: Int, offset: Int, query: String?): StudyPageResponse {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val pageable = PageRequest.of(offset / limit, limit)
        val page = if (search == null) {
            studies.findByUserId(principal.userId, pageable)
        } else {
            studies.findByUserIdAndQuery(principal.userId, search, pageable)
        }
        return StudyPageResponse(
            studies = page.content.map { it.toStudyRoomResponse() },
            totalCount = page.totalElements,
            limit = limit,
            offset = offset,
            serverTime = Instant.now(),
        )
    }

    @Transactional
    override fun createStudy(principal: Principal, command: CreateStudyCommand): StudyRoomResponse {
        val topic = command.topic.trim()
        if (topic.isEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, "Study topic is required.")
        }

        val now = Instant.now()
        val study = studies.findByUserIdAndTopic(principal.userId, topic)
            ?: StudyEntity(
                deviceId = principal.deviceId,
                userId = principal.userId,
                topic = topic,
                createdAt = now,
            )

        study.topic = topic
        study.deviceId = principal.deviceId
        study.apply(
            StudyRoomSettings.of(study.toStudyRoomSettingsState()).configure(
                StudyRoomSettingsCommand(
                    difficultyLevel = command.difficultyLevel.coerceIn(1, 10),
                    intervalMinutes = command.intervalMinutes.coerceIn(1, 1440),
                    enabled = command.enabled,
                    notificationSound = command.notificationSound,
                    customPrompt = command.customPrompt,
                    openaiModel = command.openaiModel.ifBlank { study.openaiModel.ifBlank { "gpt-5.4" } },
                    maxHistoryCount = command.maxHistoryCount.coerceIn(10, 10_000),
                    questionPublic = command.isQuestionPublic,
                ),
                encryptedOpenAIKey = null,
                anonymous = principal.anonymous,
                now = now,
            )
        )

        return studies.save(study).toStudyRoomResponse()
    }

    private fun StudyEntity.toStudyRoomResponse(): StudyRoomResponse {
        val pending = questions.findPendingByStudyId(id, PageRequest.of(0, 1))
            .content
            .firstOrNull()
            ?.let { question ->
                question.toStudyRecord(questionStats.findById(question.id).orElse(null)).toProjection().toRecordResponse()
            }

        return StudyRoomResponse(
        id = id,
        topic = topic,
        difficultyLevel = difficultyLevel,
        intervalMinutes = intervalMinutes,
        enabled = enabled,
        notificationSound = notificationSound,
        customPrompt = customPrompt,
        openaiModel = openaiModel,
        maxHistoryCount = maxHistoryCount,
        isQuestionPublic = questionPublic,
        nextDueAt = nextDueAt,
        lastSentAt = lastSentAt,
        lastError = lastError,
        pendingQuestion = pending,
        createdAt = createdAt,
        updatedAt = updatedAt,
        )
    }

    private fun StudyEntity.toStudyRoomSettingsState() = StudyRoomSettingsState(
        openaiApiKeyCipher = null,
        nextDueAt = nextDueAt,
    )

    private fun StudyEntity.apply(update: StudyRoomSettingsUpdate) {
        difficultyLevel = update.difficultyLevel
        intervalMinutes = update.intervalMinutes
        enabled = update.enabled
        notificationSound = update.notificationSound
        customPrompt = update.customPrompt
        openaiModel = update.openaiModel
        maxHistoryCount = update.maxHistoryCount
        questionPublic = update.questionPublic
        nextDueAt = update.nextDueAt
        updatedAt = update.updatedAt
    }

    private fun QuestionEntity.toStudyRecord(stats: QuestionStatsEntity? = null) = StudyRecord.of(
        StudyRecordState(
            id = id,
            question = question,
            hint = hint,
            createdAt = createdAt,
            answer = answer,
            score = score,
            correct = correct,
            feedback = feedback,
            explanation = explanation,
            topic = topic,
            difficultyLevel = difficultyLevel,
            answeredAt = answeredAt,
            publicQuestion = publicQuestion,
        ),
        stats?.let { StudyRecordStats(it.likeCount, it.commentCount, it.viewCount) },
    )
}
