package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.CreateStudyRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.model.StatsActivityResponse
import com.buddystudy.backend.stats.application.model.StatsResponse
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import java.time.Instant

interface StudyWebPort {
    fun study(limit: Int, offset: Int, query: String?, authentication: Authentication): StudyPageResponse
    fun records(limit: Int, offset: Int, query: String?, language: String, authentication: Authentication): RecordsPageResponse
    fun clearRecords(authentication: Authentication): ResponseEntity<Unit>
    fun record(id: Long, language: String, authentication: Authentication): StudyRecordResponse
    fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication): StudyRecordResponse
    fun grade(id: Long, body: AnswerRequest, authentication: Authentication): StudyRecordResponse
    fun skip(id: Long, authentication: Authentication): StudyRecordResponse
    fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit>
    fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication): StudyRecordResponse
    fun stats(limit: Int, offset: Int, query: StatsQuery, authentication: Authentication): StatsResponse
    fun statsActivity(startAt: Instant?, endAt: Instant?, authentication: Authentication): StatsActivityResponse
    fun createQuestion(studyId: Long, authentication: Authentication): StudyRecordResponse
    fun createStudy(body: CreateStudyRequest, authentication: Authentication): StudyRoomResponse
}
