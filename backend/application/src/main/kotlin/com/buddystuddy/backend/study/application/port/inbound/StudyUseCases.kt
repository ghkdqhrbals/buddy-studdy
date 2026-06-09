package com.buddystuddy.backend.study.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.study.application.model.RecordsPageResponse
import com.buddystuddy.backend.study.application.model.StudyPageResponse
import com.buddystuddy.backend.study.application.model.StudyRecordResponse

interface StudyUseCase {
    fun createQuestion(principal: Principal, topic: String?): StudyRecordResponse
    fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse
    fun skip(principal: Principal, id: Long): StudyRecordResponse
    fun delete(principal: Principal, id: Long)
    fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse
}

interface BrowseRecordsUseCase {
    fun records(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    fun record(principal: Principal, id: Long): StudyRecordResponse
}

interface StudySyncUseCase {
    fun study(principal: Principal, limit: Int, offset: Int): StudyPageResponse
}
