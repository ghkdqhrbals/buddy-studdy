package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse

interface StudyUseCase {
    fun createQuestion(principal: Principal, studyId: Long): StudyRecordResponse
    fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse
    fun skip(principal: Principal, id: Long): StudyRecordResponse
    fun delete(principal: Principal, id: Long)
    fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse
}

interface BrowseRecordsUseCase {
    fun records(principal: Principal, limit: Int, offset: Int, query: String? = null, language: String = "ko"): RecordsPageResponse
    fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    fun record(principal: Principal, id: Long, language: String = "ko"): StudyRecordResponse
}

interface StudySyncUseCase {
    fun study(principal: Principal, limit: Int, offset: Int, query: String? = null): StudyPageResponse
    fun createStudy(principal: Principal, command: CreateStudyCommand): StudyRoomResponse
    fun deleteStudy(principal: Principal, studyId: Long)
}

interface RunQuestionScheduleUseCase {
    fun runDueQuestions()
}
