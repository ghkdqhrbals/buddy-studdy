package com.buddystudy.backend.voice.adapter.inbound.scheduler

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorSessionRecoveryUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorSessionRecoverySchedulerTest {
    @Test
    fun `scheduler delegates one configured bounded recovery batch`() = runBlocking<Unit> {
        var requestedLimit: Int? = null
        val recovery = object : VoiceTutorSessionRecoveryUseCase {
            override suspend fun recoverStaleSessions(limit: Int): Int {
                requestedLimit = limit
                return 3
            }
        }
        val properties = BuddyStudyProperties().apply {
            voiceTutor.sessionRecoveryBatchSize = 37
        }

        VoiceTutorSessionRecoveryScheduler(recovery, properties).recover()

        assertThat(requestedLimit).isEqualTo(37)
    }
}
