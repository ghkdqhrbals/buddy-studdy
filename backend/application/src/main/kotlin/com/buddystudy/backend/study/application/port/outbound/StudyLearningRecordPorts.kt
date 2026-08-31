package com.buddystudy.backend.study.application.port.outbound

import com.buddystudy.backend.study.application.model.StudyLearningRecordKey
import com.buddystudy.backend.study.application.model.StudyLearningRecordScope
import com.buddystudy.backend.study.application.model.StudyLearningRecordsCursor
import com.buddystudy.voice.domain.VoiceStudyLearningRecord

interface StudyLearningRecordQueryPort {
    suspend fun pageKeys(
        userId: Long,
        studyId: Long,
        scope: StudyLearningRecordScope,
        limit: Int,
        cursor: StudyLearningRecordsCursor?,
    ): List<StudyLearningRecordKey>
}

interface VoiceStudyLearningRecordQueryPort {
    suspend fun findOwned(userId: Long, recordId: Long): VoiceStudyLearningRecord?
    suspend fun findAllOwned(userId: Long, recordIds: Collection<Long>): List<VoiceStudyLearningRecord>
}
