package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.study.application.model.StudyPageResponse
import com.buddystuddy.backend.study.application.model.StudyRoomResponse
import com.buddystuddy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.study.domain.entity.StudyEntity
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudySyncService(
    private val studies: StudyPort,
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

    private fun StudyEntity.toStudyRoomResponse() = StudyRoomResponse(
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
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
