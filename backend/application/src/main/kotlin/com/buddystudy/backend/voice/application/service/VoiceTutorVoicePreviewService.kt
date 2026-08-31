package com.buddystudy.backend.voice.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorRealtimeVoice
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewAudio
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewLanguage
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewRequest
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorVoicePreviewUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuotaQueryPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorVoicePreviewPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap

@Service
class VoiceTutorVoicePreviewService(
    private val previews: VoiceTutorVoicePreviewPort,
    private val properties: BuddyStudyProperties,
    private val quotas: VoiceTutorQuotaQueryPort,
    private val clock: Clock = Clock.systemUTC(),
) : VoiceTutorVoicePreviewUseCase {
    private val admission = VoiceTutorVoicePreviewAdmissionPolicy(clock)

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun preview(
        principal: Principal,
        voice: String,
        language: String,
    ): VoiceTutorVoicePreviewAudio {
        if (principal.anonymous) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.ACCOUNT_FORBIDDEN,
                "A registered account is required for Voice Tutor.",
            )
        }
        if (!properties.voiceTutor.enabled || properties.openai.userContentApiKey.isBlank()) {
            throw unavailable()
        }

        val selectedLanguage = VoiceTutorVoicePreviewLanguage.fromCode(language)
            ?: throw validation("Unsupported Voice Tutor preview language.")
        val requestedVoice = voice
        val selectedVoice = if (requestedVoice == DEFAULT_VOICE) {
            VoiceTutorRealtimeVoice.fromApiValue(properties.voiceTutor.voice)
                ?: throw unavailable()
        } else {
            VoiceTutorRealtimeVoice.fromApiValue(requestedVoice)
                ?: throw validation("Unsupported Voice Tutor voice.")
        }

        val quota = quotas.quota(principal.userId, clock.instant())
        if (quota?.planEligible != true) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.VOICE_TUTOR_PRO_REQUIRED,
                "A Pro membership is required for Voice Tutor voice previews.",
            )
        }

        if (!admission.tryAcquire(principal.userId)) {
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCode.SERVER_BUSY,
                "Voice Tutor voice previews are being requested too quickly.",
            )
        }
        return try {
            previews.synthesize(VoiceTutorVoicePreviewRequest(selectedVoice, selectedLanguage))
        } finally {
            admission.release(principal.userId)
        }
    }

    private fun validation(message: String) =
        ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)

    private fun unavailable() = ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE,
        "Voice Tutor voice preview is temporarily unavailable.",
    )

    private companion object {
        const val DEFAULT_VOICE = "default"
    }
}

/** Process-local cost admission only; it stores no audio or sample text. */
internal class VoiceTutorVoicePreviewAdmissionPolicy(
    private val clock: Clock,
    private val cooldown: Duration = Duration.ofSeconds(2),
    private val maxConcurrent: Int = 8,
    private val maxTrackedUsers: Int = 4_096,
) {
    private data class Entry(val startedAt: Instant, val active: Boolean)

    private val entries = LinkedHashMap<Long, Entry>(16, 0.75f, true)
    private var activeCount = 0

    init {
        require(!cooldown.isNegative)
        require(maxConcurrent > 0)
        require(maxTrackedUsers >= maxConcurrent)
    }

    @Synchronized
    fun tryAcquire(userId: Long): Boolean {
        val now = clock.instant()
        val previous = entries[userId]
        if (previous?.active == true || activeCount >= maxConcurrent) return false
        if (previous != null && now.isBefore(previous.startedAt.plus(cooldown))) return false
        if (previous == null && entries.size >= maxTrackedUsers) {
            val oldestInactive = entries.entries.firstOrNull { !it.value.active }?.key ?: return false
            entries.remove(oldestInactive)
        }
        entries[userId] = Entry(now, active = true)
        activeCount += 1
        return true
    }

    @Synchronized
    fun release(userId: Long) {
        val entry = entries[userId] ?: return
        if (!entry.active) return
        entries[userId] = entry.copy(active = false)
        activeCount -= 1
    }
}
