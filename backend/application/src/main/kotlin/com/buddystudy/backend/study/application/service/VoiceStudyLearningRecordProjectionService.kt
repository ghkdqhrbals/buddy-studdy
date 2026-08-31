package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.study.application.port.inbound.ReconcileVoiceStudyLearningRecordsUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceStudyLearningRecordAppendPort
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class VoiceStudyLearningRecordProjectionService(
    private val records: VoiceStudyLearningRecordAppendPort,
) : ReconcileVoiceStudyLearningRecordsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun reconcileLearningRecords(limit: Int): Int {
        var completed = 0
        for (candidate in records.completedCandidates(limit.coerceIn(1, 10))) {
            try {
                // Each proxy call has its own transaction. One legacy failure cannot roll back others.
                records.appendCompletedSession(candidate.userId, candidate.sessionId, candidate.explorations, Instant.now())
                completed++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("voice_study_record_projection_failed errorType={}", error.javaClass.simpleName)
            }
        }
        return completed
    }
}
