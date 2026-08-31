package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.study.application.port.inbound.BrowseStudyLearningRecordsUseCase
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class StudyLearningRecordsWebAdapter(private val records: BrowseStudyLearningRecordsUseCase) : StudyLearningRecordsWebPort {
    override suspend fun learningRecords(studyId: Long, scope: String, limit: Int, cursor: String?, language: String, view: String, authentication: Authentication) =
        records.learningRecords(authentication.principalOrThrow(), studyId, scope, limit, cursor, language, view)

    override suspend fun voiceLearningRecord(recordId: Long, language: String, view: String, authentication: Authentication) =
        records.voiceLearningRecord(authentication.principalOrThrow(), recordId, language, view)
}
