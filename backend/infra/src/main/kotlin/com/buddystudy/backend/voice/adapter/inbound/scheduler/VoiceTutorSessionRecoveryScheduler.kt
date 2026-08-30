package com.buddystudy.backend.voice.adapter.inbound.scheduler

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorSessionRecoveryUseCase
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class VoiceTutorSessionRecoveryScheduler(
    private val recovery: VoiceTutorSessionRecoveryUseCase,
    private val properties: BuddyStudyProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${buddystudy.voice-tutor.session-recovery-poll-ms:5000}",
        initialDelayString = "\${buddystudy.voice-tutor.session-recovery-initial-delay-ms:5000}",
    )
    suspend fun recover() {
        runCatching {
            recovery.recoverStaleSessions(properties.voiceTutor.sessionRecoveryBatchSize.coerceIn(1, 500))
        }.onFailure { error ->
            logger.warn("voice_tutor_session_recovery_failed errorType={}", error.javaClass.simpleName)
        }
    }
}
