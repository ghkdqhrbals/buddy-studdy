package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystudy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.CreateStudyRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
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
    private val studySyncUseCase: StudySyncUseCase,
) : StudyWebPort {
    override fun study(limit: Int, offset: Int, query: String?, authentication: Authentication) =
        studySyncUseCase.study(authentication.principalOrThrow(), safeLimit(limit, 1000), max(0, offset), query)

    override fun records(limit: Int, offset: Int, query: String?, language: String, authentication: Authentication) =
        recordsUseCase.records(authentication.principalOrThrow(), safeLimit(limit, 500), max(0, offset), query, language)

    override fun clearRecords(authentication: Authentication): ResponseEntity<Unit> = ResponseEntity.noContent().build()

    override fun record(id: Long, language: String, authentication: Authentication) =
        recordsUseCase.record(authentication.principalOrThrow(), id, language)

    override fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication) =
        studyUseCase.answer(authentication.principalOrThrow(), id, body.answer, grade = false)

    override fun grade(id: Long, body: AnswerRequest, authentication: Authentication) =
        studyUseCase.answer(authentication.principalOrThrow(), id, body.answer, grade = true)

    override fun skip(id: Long, authentication: Authentication) =
        studyUseCase.skip(authentication.principalOrThrow(), id)

    override fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit> {
        studyUseCase.delete(authentication.principalOrThrow(), id)
        return ResponseEntity.noContent().build()
    }

    override fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication) =
        studyUseCase.publicity(authentication.principalOrThrow(), id, body.isPublic)

    override fun stats(limit: Int, offset: Int, query: StatsQuery, authentication: Authentication) =
        statsUseCase.stats(authentication.principalOrThrow(), safeLimit(limit, 100), max(0, offset), query)

    override fun statsActivity(startAt: Instant?, endAt: Instant?, authentication: Authentication) =
        statsUseCase.activity(authentication.principalOrThrow(), startAt, endAt)

    override fun createQuestion(studyId: Long, authentication: Authentication) =
        studyUseCase.createQuestion(authentication.principalOrThrow(), studyId)

    override fun createStudy(body: CreateStudyRequest, authentication: Authentication) =
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

    override fun deleteStudy(studyId: Long, authentication: Authentication): ResponseEntity<Unit> {
        studySyncUseCase.deleteStudy(authentication.principalOrThrow(), studyId)
        return ResponseEntity.noContent().build()
    }

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}
