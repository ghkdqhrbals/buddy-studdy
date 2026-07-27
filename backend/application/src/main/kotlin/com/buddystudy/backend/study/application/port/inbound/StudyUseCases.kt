package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.model.StudyTopicSuggestionsResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationAcceptedResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationProcessResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent

interface StudyUseCase {
    suspend fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse
    suspend fun skip(principal: Principal, id: Long): StudyRecordResponse
    suspend fun delete(principal: Principal, id: Long)
    suspend fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse
}

interface RequestQuestionGenerationUseCase {
    suspend fun request(
        principal: Principal,
        studyId: Long,
        idempotencyKey: String,
    ): QuestionGenerationAcceptedResponse
}

interface GetQuestionGenerationProcessUseCase {
    suspend fun get(principal: Principal, correlationId: String): QuestionGenerationProcessResponse
}

interface ProcessQuestionGenerationUseCase {
    suspend fun process(event: QuestionGenerationRequestedEvent)
}

interface BrowseRecordsUseCase {
    suspend fun records(principal: Principal, limit: Int, offset: Int, query: String? = null, language: String = "ko"): RecordsPageResponse
    suspend fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    suspend fun record(principal: Principal, id: Long, language: String = "ko"): StudyRecordResponse
}

interface StudySyncUseCase {
    suspend fun study(principal: Principal, limit: Int, offset: Int, query: String? = null): StudyPageResponse
    suspend fun study(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String? = null,
        language: String,
    ): StudyPageResponse = study(principal, limit, offset, query)
    suspend fun createStudy(principal: Principal, command: CreateStudyCommand): StudyRoomResponse
    suspend fun createStudyTopic(
        principal: Principal,
        parentStudyId: Long,
        command: CreateStudyTopicCommand,
    ): StudyRoomResponse
    suspend fun deleteStudy(principal: Principal, studyId: Long)
}

interface StudyTreeUseCase {
    suspend fun suggestTopics(
        principal: Principal,
        parentStudyId: Long,
        count: Int,
    ): StudyTopicSuggestionsResponse

    suspend fun updateTopicActivation(
        principal: Principal,
        studyId: Long,
        command: UpdateStudyTopicActivationCommand,
    ): StudyRoomResponse
}

interface RunQuestionScheduleUseCase {
    suspend fun runDueQuestions()
}
