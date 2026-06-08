package com.buddystuddy.backend.study.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.dto.BackendSnapshotResponse
import com.buddystuddy.backend.dto.RecordsPageResponse
import com.buddystuddy.backend.dto.StatsResponse
import com.buddystuddy.backend.dto.StudyRecordResponse

interface StudyUseCase {
    fun createQuestion(principal: Principal, topic: String?): StudyRecordResponse
    fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse
    fun records(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse
    fun record(principal: Principal, id: Long): StudyRecordResponse
    fun skip(principal: Principal, id: Long): StudyRecordResponse
    fun delete(principal: Principal, id: Long)
    fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse
    fun stats(principal: Principal, limit: Int, offset: Int): StatsResponse
    fun snapshot(principal: Principal, limit: Int, offset: Int): BackendSnapshotResponse
}
