package com.buddystuddy.backend.study.adapter.inbound.web

import com.buddystuddy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.CreateQuestionRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.CreateStudyRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication

interface StudyWebPort {
    fun study(limit: Int, offset: Int, query: String?, authentication: Authentication): Any
    fun records(limit: Int, offset: Int, query: String?, authentication: Authentication): Any
    fun clearRecords(authentication: Authentication): ResponseEntity<Unit>
    fun record(id: Long, authentication: Authentication): Any
    fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication): Any
    fun grade(id: Long, body: AnswerRequest, authentication: Authentication): Any
    fun skip(id: Long, authentication: Authentication): Any
    fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit>
    fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication): Any
    fun stats(limit: Int, offset: Int, query: String?, authentication: Authentication): Any
    fun createQuestion(body: CreateQuestionRequest, authentication: Authentication): Any
    fun createStudy(body: CreateStudyRequest, authentication: Authentication): Any
}
