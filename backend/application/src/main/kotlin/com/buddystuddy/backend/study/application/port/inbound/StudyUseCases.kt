package com.buddystuddy.backend.study.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.study.application.model.RecordsPageResponse
import com.buddystuddy.backend.study.application.model.StudyPageResponse
import com.buddystuddy.backend.study.application.model.StudyRecordResponse
import com.buddystuddy.backend.study.application.model.StudyRoomResponse

data class CreateStudyCommand(
    val topic: String,
    val difficultyLevel: Int = 5,
    val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val openaiModel: String = "gpt-5.4",
    val maxHistoryCount: Int = 100,
    val isQuestionPublic: Boolean = true,
)

interface StudyUseCase {
    fun createQuestion(principal: Principal, topic: String?): StudyRecordResponse
    fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse
    fun skip(principal: Principal, id: Long): StudyRecordResponse
    fun delete(principal: Principal, id: Long)
    fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse
}

interface BrowseRecordsUseCase {
    fun records(principal: Principal, limit: Int, offset: Int, query: String? = null): RecordsPageResponse
    fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    fun record(principal: Principal, id: Long): StudyRecordResponse
}

interface StudySyncUseCase {
    fun study(principal: Principal, limit: Int, offset: Int, query: String? = null): StudyPageResponse
    fun createStudy(principal: Principal, command: CreateStudyCommand): StudyRoomResponse
}
