package com.buddystudy.backend.mcp.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.learningcontext.application.model.LearningContextPatchCommand
import com.buddystudy.backend.learningcontext.application.model.LearningContextResponse
import com.buddystudy.backend.mcp.application.model.McpDeletionResponse
import com.buddystudy.backend.mcp.application.model.McpUserContextResponse
import com.buddystudy.backend.stats.application.model.StatsResponse
import com.buddystudy.backend.stats.application.model.StudyGrowthResponse
import com.buddystudy.backend.study.application.model.AnswerGradingProcessResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationAcceptedResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationProcessResponse
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.RootStudyCreationResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.model.StudyTopicCreationResponse
import com.buddystudy.backend.study.application.model.StudyTopicsCreationResponse
import com.buddystudy.backend.study.application.model.StudyTopicSuggestionsResponse
import com.buddystudy.backend.study.application.model.VoiceStudyLearningRecordResponse
import com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateRootStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicsCommand
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyCommand
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionDetailResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionsPageResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorStatusResponse
import java.time.Instant

interface BuddyStudyMcpUseCase {
    suspend fun getMyContext(principal: Principal): McpUserContextResponse
    suspend fun updateMyLearningContext(
        principal: Principal,
        command: LearningContextPatchCommand,
    ): LearningContextResponse

    suspend fun listStudies(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        language: String,
    ): StudyPageResponse

    suspend fun listStudies(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        language: String,
        parentStudyId: Long,
    ): StudyPageResponse

    suspend fun getStudy(principal: Principal, studyId: Long, language: String): StudyRoomResponse
    suspend fun updateStudy(principal: Principal, studyId: Long, command: UpdateStudyCommand): StudyRoomResponse
    suspend fun createStudy(principal: Principal, command: CreateStudyCommand): StudyRoomResponse
    suspend fun createRootStudy(principal: Principal, command: CreateRootStudyCommand): RootStudyCreationResponse
    suspend fun createStudyTopic(
        principal: Principal,
        parentStudyId: Long,
        command: CreateStudyTopicCommand,
    ): StudyTopicCreationResponse

    suspend fun suggestStudyTopics(
        principal: Principal,
        parentStudyId: Long,
        count: Int,
    ): StudyTopicSuggestionsResponse

    suspend fun createStudyTopics(
        principal: Principal,
        parentStudyId: Long,
        command: CreateStudyTopicsCommand,
    ): StudyTopicsCreationResponse

    suspend fun deleteStudy(
        principal: Principal,
        studyId: Long,
        confirmed: Boolean,
        expectedStudyIds: List<Long>? = null,
    ): McpDeletionResponse
    suspend fun listPendingQuestions(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    suspend fun listPendingQuestions(principal: Principal, limit: Int, offset: Int, studyId: Long): RecordsPageResponse
    suspend fun skipQuestion(principal: Principal, recordId: Long): StudyRecordResponse
    suspend fun requestQuestion(
        principal: Principal,
        studyId: Long,
        idempotencyKey: String,
    ): QuestionGenerationAcceptedResponse

    suspend fun getQuestionProcess(principal: Principal, correlationId: String): QuestionGenerationProcessResponse
    suspend fun submitAnswer(
        principal: Principal,
        recordId: Long,
        answer: String,
        sourceLanguage: String?,
    ): StudyRecordResponse

    suspend fun getGradingProcess(
        principal: Principal,
        correlationId: String,
        afterEventId: Long,
    ): AnswerGradingProcessResponse

    suspend fun listRecords(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        studyId: Long?,
        language: String,
        view: String,
    ): RecordsPageResponse

    suspend fun getRecord(
        principal: Principal,
        recordId: Long,
        language: String,
        view: String,
    ): StudyRecordResponse

    suspend fun listStudyLearningRecords(
        principal: Principal,
        studyId: Long,
        scope: String,
        limit: Int,
        cursor: String?,
        language: String,
        view: String,
    ): StudyLearningRecordsPageResponse

    suspend fun getVoiceLearningRecord(
        principal: Principal,
        recordId: Long,
        language: String,
        view: String,
    ): VoiceStudyLearningRecordResponse

    suspend fun getTopicStats(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        period: String?,
        startAt: Instant?,
        endAt: Instant?,
    ): StatsResponse

    suspend fun getStudyGrowth(principal: Principal, startAt: Instant?, endAt: Instant?): StudyGrowthResponse
    suspend fun listVoiceTutorSessions(principal: Principal, limit: Int, cursor: String?): VoiceTutorSessionsPageResponse
    suspend fun getVoiceTutorSession(principal: Principal, sessionId: String): VoiceTutorSessionDetailResponse
    suspend fun getVoiceTutorQuota(principal: Principal): VoiceTutorStatusResponse
}
