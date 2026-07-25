package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.CreateStudyRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.StudyTopicActivationRequest
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.model.StatsActivityResponse
import com.buddystudy.backend.stats.application.model.StatsResponse
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.model.QuestionQuotaResponse
import com.buddystudy.backend.study.application.model.StudyTopicSuggestionsResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import java.time.Instant

interface StudyWebPort {
    suspend fun study(limit: Int, offset: Int, query: String?, authentication: Authentication): StudyPageResponse
    suspend fun records(limit: Int, offset: Int, query: String?, language: String, authentication: Authentication): RecordsPageResponse
    suspend fun clearRecords(authentication: Authentication): ResponseEntity<Unit>
    suspend fun record(id: Long, language: String, authentication: Authentication): StudyRecordResponse
    suspend fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication): StudyRecordResponse
    suspend fun grade(id: Long, body: AnswerRequest, authentication: Authentication): StudyRecordResponse
    suspend fun skip(id: Long, authentication: Authentication): StudyRecordResponse
    suspend fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit>
    suspend fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication): StudyRecordResponse
    suspend fun stats(limit: Int, offset: Int, query: StatsQuery, authentication: Authentication): StatsResponse
    suspend fun statsActivity(startAt: Instant?, endAt: Instant?, authentication: Authentication): StatsActivityResponse
    suspend fun createQuestion(studyId: Long, authentication: Authentication): StudyRecordResponse
    suspend fun questionQuota(authentication: Authentication): QuestionQuotaResponse
    suspend fun createStudy(body: CreateStudyRequest, authentication: Authentication): StudyRoomResponse
    suspend fun deleteStudy(studyId: Long, authentication: Authentication): ResponseEntity<Unit>
    suspend fun suggestStudyTopics(studyId: Long, count: Int, authentication: Authentication): StudyTopicSuggestionsResponse
    suspend fun updateStudyTopicActivation(
        studyId: Long,
        body: StudyTopicActivationRequest,
        authentication: Authentication,
    ): StudyRoomResponse
}
