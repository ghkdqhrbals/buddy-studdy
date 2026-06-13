package com.buddystuddy.backend.study.adapter.inbound.web

import com.buddystuddy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.CreateStudyRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystuddy.backend.stats.application.model.StatsQuery
import com.buddystuddy.backend.stats.application.model.StatsResponse
import com.buddystuddy.backend.study.application.model.RecordsPageResponse
import com.buddystuddy.backend.study.application.model.StudyPageResponse
import com.buddystuddy.backend.study.application.model.StudyRecordResponse
import com.buddystuddy.backend.study.application.model.StudyRoomResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication

interface StudyWebPort {
    fun study(limit: Int, offset: Int, query: String?, authentication: Authentication): StudyPageResponse
    fun records(limit: Int, offset: Int, query: String?, authentication: Authentication): RecordsPageResponse
    fun clearRecords(authentication: Authentication): ResponseEntity<Unit>
    fun record(id: Long, authentication: Authentication): StudyRecordResponse
    fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication): StudyRecordResponse
    fun grade(id: Long, body: AnswerRequest, authentication: Authentication): StudyRecordResponse
    fun skip(id: Long, authentication: Authentication): StudyRecordResponse
    fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit>
    fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication): StudyRecordResponse
    fun stats(limit: Int, offset: Int, query: StatsQuery, authentication: Authentication): StatsResponse
    fun createQuestion(studyId: Long, authentication: Authentication): StudyRecordResponse
    fun createStudy(body: CreateStudyRequest, authentication: Authentication): StudyRoomResponse
}
