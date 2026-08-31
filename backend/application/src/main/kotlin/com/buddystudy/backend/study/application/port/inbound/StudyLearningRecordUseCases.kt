package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.VoiceStudyLearningRecordResponse

interface BrowseStudyLearningRecordsUseCase {
    suspend fun learningRecords(
        principal: Principal,
        studyId: Long,
        scope: String,
        limit: Int,
        cursor: String?,
        language: String,
        view: String,
    ): StudyLearningRecordsPageResponse

    suspend fun voiceLearningRecord(
        principal: Principal,
        recordId: Long,
        language: String,
        view: String,
    ): VoiceStudyLearningRecordResponse
}

interface ReconcileVoiceStudyLearningRecordsUseCase {
    suspend fun reconcileLearningRecords(limit: Int): Int
}
