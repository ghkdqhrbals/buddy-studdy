package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystudy.backend.stats.application.port.inbound.GetStudyGrowthUseCase
import com.buddystudy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.CreateStudyRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.CreateStudyTopicRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.StudyTopicActivationRequest
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.ObserveAnswerGradingUseCase
import com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicCommand
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyTreeUseCase
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyTopicActivationCommand
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaUseCase
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

@Component
class StudyWebAdapter(
    private val studyUseCase: StudyUseCase,
    private val recordsUseCase: BrowseRecordsUseCase,
    private val statsUseCase: GetStudyStatsUseCase,
    private val studyGrowthUseCase: GetStudyGrowthUseCase,
    private val studySyncUseCase: StudySyncUseCase,
    private val studyTreeUseCase: StudyTreeUseCase,
    private val questionQuotaUseCase: QuestionQuotaUseCase,
    private val answerGrading: ObserveAnswerGradingUseCase,
) : StudyWebPort {
    override suspend fun study(limit: Int, offset: Int, query: String?, authentication: Authentication) =
        studySyncUseCase.study(authentication.principalOrThrow(), safeLimit(limit, 1000), max(0, offset), query)

    override suspend fun records(limit: Int, offset: Int, query: String?, language: String, authentication: Authentication) =
        recordsUseCase.records(authentication.principalOrThrow(), safeLimit(limit, 500), max(0, offset), query, language)

    override suspend fun clearRecords(authentication: Authentication): ResponseEntity<Unit> = ResponseEntity.noContent().build()

    override suspend fun record(id: Long, language: String, authentication: Authentication) =
        recordsUseCase.record(authentication.principalOrThrow(), id, language)

    override suspend fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication) =
        studyUseCase.answer(authentication.principalOrThrow(), id, body.answer, grade = false)

    override suspend fun grade(id: Long, body: AnswerRequest, authentication: Authentication) =
        ResponseEntity.accepted().body(
            studyUseCase.answer(authentication.principalOrThrow(), id, body.answer, grade = true),
        )

    override suspend fun gradingEvents(id: Long, afterId: Long, authentication: Authentication) =
        answerGrading.observe(authentication.principalOrThrow(), id, afterId)

    override suspend fun skip(id: Long, authentication: Authentication) =
        studyUseCase.skip(authentication.principalOrThrow(), id)

    override suspend fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit> {
        studyUseCase.delete(authentication.principalOrThrow(), id)
        return ResponseEntity.noContent().build()
    }

    override suspend fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication) =
        studyUseCase.publicity(authentication.principalOrThrow(), id, body.isPublic)

    override suspend fun stats(limit: Int, offset: Int, query: StatsQuery, authentication: Authentication) =
        statsUseCase.stats(authentication.principalOrThrow(), safeLimit(limit, 100), max(0, offset), query)

    override suspend fun statsActivity(startAt: Instant?, endAt: Instant?, authentication: Authentication) =
        statsUseCase.activity(authentication.principalOrThrow(), startAt, endAt)

    override suspend fun studyGrowth(startAt: Instant?, endAt: Instant?, authentication: Authentication) =
        studyGrowthUseCase.growth(authentication.principalOrThrow(), startAt, endAt)

    override suspend fun createQuestion(studyId: Long, authentication: Authentication) =
        studyUseCase.createQuestion(authentication.principalOrThrow(), studyId)

    override suspend fun questionQuota(authentication: Authentication) =
        questionQuotaUseCase.status(authentication.principalOrThrow())

    override suspend fun createStudy(body: CreateStudyRequest, authentication: Authentication) =
        studySyncUseCase.createStudy(
            authentication.principalOrThrow(),
            CreateStudyCommand(
                topic = body.topic,
                difficultyLevel = body.difficultyLevel,
                intervalMinutes = body.intervalMinutes,
                enabled = body.enabled,
                notificationSound = body.notificationSound,
                customPrompt = body.customPrompt,
                openaiModel = body.openaiModel,
                maxHistoryCount = body.maxHistoryCount,
            ),
        )

    override suspend fun createStudyTopic(
        parentStudyId: Long,
        body: CreateStudyTopicRequest,
        authentication: Authentication,
    ) = studySyncUseCase.createStudyTopic(
        principal = authentication.principalOrThrow(),
        parentStudyId = parentStudyId,
        command = CreateStudyTopicCommand(
            topic = body.topic,
            sortOrder = body.sortOrder,
            difficultyLevel = body.difficultyLevel,
            activeForQuestions = body.activeForQuestions,
        ),
    )

    override suspend fun deleteStudy(studyId: Long, authentication: Authentication): ResponseEntity<Unit> {
        studySyncUseCase.deleteStudy(authentication.principalOrThrow(), studyId)
        return ResponseEntity.noContent().build()
    }

    override suspend fun suggestStudyTopics(studyId: Long, count: Int, authentication: Authentication) =
        studyTreeUseCase.suggestTopics(
            principal = authentication.principalOrThrow(),
            parentStudyId = studyId,
            count = safeLimit(count, 8),
        )

    override suspend fun updateStudyTopicActivation(
        studyId: Long,
        body: StudyTopicActivationRequest,
        authentication: Authentication,
    ) = studyTreeUseCase.updateTopicActivation(
        principal = authentication.principalOrThrow(),
        studyId = studyId,
        command = UpdateStudyTopicActivationCommand(body.active),
    )

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}
