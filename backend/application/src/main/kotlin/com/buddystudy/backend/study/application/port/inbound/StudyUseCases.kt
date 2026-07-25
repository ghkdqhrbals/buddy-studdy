package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.model.StudyTopicSuggestionsResponse

interface StudyUseCase {
    suspend fun createQuestion(principal: Principal, studyId: Long): StudyRecordResponse
    suspend fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse
    suspend fun skip(principal: Principal, id: Long): StudyRecordResponse
    suspend fun delete(principal: Principal, id: Long)
    suspend fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse
}

interface BrowseRecordsUseCase {
    suspend fun records(principal: Principal, limit: Int, offset: Int, query: String? = null, language: String = "ko"): RecordsPageResponse
    suspend fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    suspend fun record(principal: Principal, id: Long, language: String = "ko"): StudyRecordResponse
}

interface StudySyncUseCase {
    suspend fun study(principal: Principal, limit: Int, offset: Int, query: String? = null): StudyPageResponse
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
