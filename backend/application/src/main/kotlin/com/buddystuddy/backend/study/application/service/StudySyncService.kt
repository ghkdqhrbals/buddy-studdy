package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.study.application.model.StudyPageResponse
import com.buddystuddy.backend.study.application.model.StudyRoomResponse
import com.buddystuddy.backend.study.application.model.toRecordResponse
import com.buddystuddy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.study.domain.entity.StudyEntity
import com.buddystuddy.study.domain.StudyRecord
import com.buddystuddy.study.domain.StudyRecordState
import com.buddystuddy.study.domain.StudyRecordStats
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import org.springframework.data.domain.PageRequest
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
    override fun study(principal: Principal, limit: Int, offset: Int): StudyPageResponse {
        val page = studies.findByUserId(principal.userId, PageRequest.of(offset / limit, limit))
        return StudyPageResponse(
            studies = page.content.map { it.toStudyRoomResponse() },
            totalCount = page.totalElements,
            limit = limit,
            offset = offset,
            serverTime = Instant.now(),
        )
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
