package com.buddystuddy.backend.study.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.study.application.model.BackendSnapshotResponse
import com.buddystuddy.backend.study.application.model.RecordsPageResponse
import com.buddystuddy.backend.stats.application.model.StatsResponse
import com.buddystuddy.backend.study.application.model.StudyRecordResponse

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
