package com.buddystudy.backend.mcp.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.learningcontext.application.model.LearningContextPatchCommand
import com.buddystudy.backend.learningcontext.application.port.inbound.LearningContextUseCase
import com.buddystudy.backend.mcp.application.model.McpDeletionResponse
import com.buddystudy.backend.mcp.application.model.McpUserContextResponse
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.profile.application.port.inbound.ProfileUseCase
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.port.inbound.GetStudyGrowthUseCase
import com.buddystudy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.BrowseStudyLearningRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicCommand
import com.buddystudy.backend.study.application.port.inbound.GetAnswerGradingProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.GetQuestionGenerationProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.RequestQuestionGenerationUseCase
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class BuddyStudyMcpService(
    private val profiles: ProfileUseCase,
    private val learningContexts: LearningContextUseCase,
    private val studies: StudySyncUseCase,
    private val records: BrowseRecordsUseCase,
    private val studyAnswers: StudyUseCase,
    private val questionRequests: RequestQuestionGenerationUseCase,
    private val questionProcesses: GetQuestionGenerationProcessUseCase,
    private val gradingProcesses: GetAnswerGradingProcessUseCase,
    private val stats: GetStudyStatsUseCase,
    private val growth: GetStudyGrowthUseCase,
    private val voiceTutor: VoiceTutorUseCase,
    private val learningRecords: BrowseStudyLearningRecordsUseCase,
) : BuddyStudyMcpUseCase {
    @RequirePermission(Permissions.PROFILE_READ)
    override suspend fun getMyContext(principal: Principal): McpUserContextResponse {
        requireRegistered(principal)
        return McpUserContextResponse(
            profile = profiles.profile(principal),
            learningContext = learningContexts.get(principal),
        )
    }

    @RequirePermission(Permissions.PROFILE_UPDATE)
    override suspend fun updateMyLearningContext(principal: Principal, command: LearningContextPatchCommand) =
        learningContexts.patch(registered(principal), command)

    @RequirePermission(Permissions.STUDY_READ)
    override suspend fun listStudies(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        language: String,
    ) = studies.study(
        principal = registered(principal),
        limit = boundedLimit(limit, MAX_STUDY_PAGE_SIZE),
        offset = nonNegativeOffset(offset),
        query = query,
        language = language,
    )

    @RequirePermission(Permissions.STUDY_READ)
    override suspend fun listStudies(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        language: String,
        parentStudyId: Long,
    ) = studies.study(
        principal = registered(principal),
        limit = boundedLimit(limit, MAX_STUDY_PAGE_SIZE),
        offset = nonNegativeOffset(offset),
        query = query,
        language = language,
        parentStudyId = positiveId(parentStudyId, "parent_study_id"),
    )

    @RequirePermission(Permissions.STUDY_READ)
    override suspend fun getStudy(principal: Principal, studyId: Long, language: String) =
        studies.study(registered(principal), positiveId(studyId, "study_id"), language)

    @RequirePermission(Permissions.STUDY_CREATE)
    override suspend fun createStudy(principal: Principal, command: CreateStudyCommand) =
        studies.createStudy(registered(principal), command)

    @RequirePermission(Permissions.STUDY_CREATE)
    override suspend fun createStudyTopic(
        principal: Principal,
        parentStudyId: Long,
        command: CreateStudyTopicCommand,
    ) = studies.createStudyTopic(registered(principal), positiveId(parentStudyId, "parent_study_id"), command)

    @RequirePermission(Permissions.STUDY_DELETE)
    override suspend fun deleteStudy(principal: Principal, studyId: Long, confirmed: Boolean): McpDeletionResponse {
        requireRegistered(principal)
        if (!confirmed) {
            throw validation("confirm must be true before deleting a study subtree.")
        }
        val validatedStudyId = positiveId(studyId, "study_id")
        studies.deleteStudy(principal, validatedStudyId)
        return McpDeletionResponse(deleted = true, studyId = validatedStudyId)
    }

    @RequirePermission(Permissions.RECORD_READ)
    override suspend fun listPendingQuestions(principal: Principal, limit: Int, offset: Int) =
        records.pending(
            registered(principal),
            boundedLimit(limit, MAX_RECORD_PAGE_SIZE),
            nonNegativeOffset(offset),
        )

    @RequirePermission(Permissions.QUESTION_CREATE)
    override suspend fun requestQuestion(principal: Principal, studyId: Long, idempotencyKey: String) =
        questionRequests.request(
            registered(principal),
            positiveId(studyId, "study_id"),
            idempotencyKey.trim(),
        )

    @RequirePermission(Permissions.RECORD_READ)
    override suspend fun getQuestionProcess(principal: Principal, correlationId: String) =
        questionProcesses.get(registered(principal), requiredText(correlationId, "correlation_id"))

    @RequirePermission(Permissions.RECORD_UPDATE)
    override suspend fun submitAnswer(
        principal: Principal,
        recordId: Long,
        answer: String,
        sourceLanguage: String?,
    ) = studyAnswers.answer(
        principal = registered(principal),
        recordId = positiveId(recordId, "record_id"),
        answer = validatedAnswer(answer),
        sourceLanguage = sourceLanguage,
        grade = true,
    )

    @RequirePermission(Permissions.RECORD_UPDATE)
    override suspend fun getGradingProcess(
        principal: Principal,
        correlationId: String,
        afterEventId: Long,
    ) = gradingProcesses.get(
        registered(principal),
        requiredText(correlationId, "correlation_id"),
        afterEventId.coerceAtLeast(0),
    )

    @RequirePermission(Permissions.RECORD_READ)
    override suspend fun listRecords(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        studyId: Long?,
        language: String,
        view: String,
    ) = records.records(
        principal = registered(principal),
        limit = boundedLimit(limit, MAX_RECORD_PAGE_SIZE),
        offset = nonNegativeOffset(offset),
        query = query,
        studyId = studyId?.let { positiveId(it, "study_id") },
        language = language,
        view = validatedView(view),
    )

    @RequirePermission(Permissions.RECORD_READ)
    override suspend fun getRecord(principal: Principal, recordId: Long, language: String, view: String) =
        records.record(
            registered(principal),
            positiveId(recordId, "record_id"),
            language,
            validatedView(view),
        )

    @RequirePermission(Permissions.STUDY_READ, Permissions.RECORD_READ, Permissions.VOICE_TUTOR_READ)
    override suspend fun listStudyLearningRecords(
        principal: Principal,
        studyId: Long,
        scope: String,
        limit: Int,
        cursor: String?,
        language: String,
        view: String,
    ) = learningRecords.learningRecords(
        principal = registered(principal),
        studyId = positiveId(studyId, "study_id"),
        scope = scope.takeIf { it == "node" || it == "subtree" }
            ?: throw validation("scope must be node or subtree."),
        limit = boundedLimit(limit, MAX_LEARNING_RECORD_PAGE_SIZE),
        cursor = cursor?.also {
            if (it.length !in 1..512) throw validation("Invalid learning-record cursor.")
        },
        language = language,
        view = validatedView(view),
    )

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun getVoiceLearningRecord(principal: Principal, recordId: Long, language: String, view: String) =
        learningRecords.voiceLearningRecord(
            registered(principal),
            positiveId(recordId, "record_id"),
            language,
            validatedView(view),
        )

    @RequirePermission(Permissions.STATS_READ)
    override suspend fun getTopicStats(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        period: String?,
        startAt: Instant?,
        endAt: Instant?,
    ) = stats.stats(
        principal = registered(principal),
        limit = boundedLimit(limit, MAX_STATS_PAGE_SIZE),
        offset = nonNegativeOffset(offset),
        query = StatsQuery(search = query, period = period, startAt = startAt, endAt = endAt),
    )

    @RequirePermission(Permissions.STATS_READ)
    override suspend fun getStudyGrowth(principal: Principal, startAt: Instant?, endAt: Instant?) =
        growth.growth(registered(principal), startAt, endAt)

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun listVoiceTutorSessions(principal: Principal, limit: Int, cursor: String?) =
        voiceTutor.sessions(registered(principal), boundedLimit(limit, MAX_RECORD_PAGE_SIZE), cursor)

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun getVoiceTutorSession(principal: Principal, sessionId: String) =
        voiceTutor.session(registered(principal), requiredText(sessionId, "session_id"))

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun getVoiceTutorQuota(principal: Principal) =
        voiceTutor.status(registered(principal))

    private fun registered(principal: Principal): Principal {
        requireRegistered(principal)
        return principal
    }

    private fun requireRegistered(principal: Principal) {
        if (principal.anonymous) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.ACCOUNT_FORBIDDEN,
                "A registered account is required to use the MCP server.",
            )
        }
    }

    private fun boundedLimit(value: Int, maximum: Int): Int {
        if (value !in 1..maximum) throw validation("limit must be between 1 and $maximum.")
        return value
    }

    private fun nonNegativeOffset(value: Int): Int {
        if (value < 0) throw validation("offset must be zero or greater.")
        return value
    }

    private fun positiveId(value: Long, name: String): Long {
        if (value <= 0) throw validation("$name must be a positive integer.")
        return value
    }

    private fun requiredText(value: String, name: String): String =
        value.trim().takeIf(String::isNotEmpty) ?: throw validation("$name is required.")

    private fun validatedAnswer(value: String): String {
        if (value.isBlank()) throw validation("answer is required.")
        if (value.length > MAX_ANSWER_LENGTH) throw validation("answer must contain at most $MAX_ANSWER_LENGTH characters.")
        return value
    }

    private fun validatedView(value: String): String {
        val normalized = value.trim().lowercase()
        if (normalized !in SUPPORTED_VIEWS) throw validation("view must be localized or original.")
        return normalized
    }

    private fun validation(message: String) =
        ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)

    private companion object {
        const val MAX_STUDY_PAGE_SIZE = 500
        const val MAX_RECORD_PAGE_SIZE = 100
        const val MAX_LEARNING_RECORD_PAGE_SIZE = 30
        const val MAX_STATS_PAGE_SIZE = 50
        const val MAX_ANSWER_LENGTH = 50_000
        val SUPPORTED_VIEWS = setOf("localized", "original")
    }
}
