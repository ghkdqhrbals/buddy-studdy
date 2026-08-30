package com.buddystudy.backend.voice.adapter.inbound.scheduler

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorResultRecoveryUseCase
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class VoiceTutorResultRecoveryScheduler(
    private val recovery: VoiceTutorResultRecoveryUseCase,
    private val properties: BuddyStudyProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${buddystudy.voice-tutor.summary-recovery-poll-ms:5000}",
        initialDelayString = "\${buddystudy.voice-tutor.summary-recovery-initial-delay-ms:5000}",
    )
    suspend fun recover() {
        runCatching {
            recovery.recoverPendingResults(properties.voiceTutor.summaryRecoveryBatchSize.coerceIn(1, 100))
        }.onFailure { error ->
            logger.warn("voice_tutor_result_recovery_failed errorType={}", error.javaClass.simpleName)
        }
    }
}
