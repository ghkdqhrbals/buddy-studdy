package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.VoiceStudyLearningRecordResponse
import org.springframework.security.core.Authentication

interface StudyLearningRecordsWebPort {
    suspend fun learningRecords(studyId: Long, scope: String, limit: Int, cursor: String?, language: String, view: String, authentication: Authentication): StudyLearningRecordsPageResponse
    suspend fun voiceLearningRecord(recordId: Long, language: String, view: String, authentication: Authentication): VoiceStudyLearningRecordResponse
}
