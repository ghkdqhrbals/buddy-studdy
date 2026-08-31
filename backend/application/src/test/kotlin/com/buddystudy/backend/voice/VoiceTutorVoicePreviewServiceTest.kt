package com.buddystudy.backend.voice

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
import com.buddystudy.backend.voice.application.model.VoiceTutorQuotaSnapshot
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorVoicePreviewUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuotaQueryPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorVoicePreviewPort
import com.buddystudy.backend.voice.application.service.VoiceTutorVoicePreviewAdmissionPolicy
import com.buddystudy.backend.voice.application.service.VoiceTutorVoicePreviewService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class VoiceTutorVoicePreviewServiceTest {
    private val principal = Principal(7, "device-7", 70, anonymous = false)

    @Test
    fun `every current Realtime voice is accepted without rewriting`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()
        val service = service(provider)

        VoiceTutorRealtimeVoice.entries.forEachIndexed { index, voice ->
            service.preview(principal.copy(userId = index.toLong() + 1), voice.apiValue, "ko")
        }

        assertThat(provider.requests.map { it.voice }).containsExactlyElementsOf(VoiceTutorRealtimeVoice.entries)
        assertThat(provider.requests).allMatch { it.language == VoiceTutorVoicePreviewLanguage.KOREAN }
        assertThat(VoiceTutorRealtimeVoice.entries.map { it.apiValue }).containsExactly(
            "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar",
        )
    }

    @Test
    fun `default resolves through the configured Realtime default`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()
        val properties = properties().apply { voiceTutor.voice = "cedar" }

        service(provider, properties).preview(principal, "default", "en")

        assertThat(provider.requests).containsExactly(
            VoiceTutorVoicePreviewRequest(VoiceTutorRealtimeVoice.CEDAR, VoiceTutorVoicePreviewLanguage.ENGLISH),
        )
    }

    @Test
    fun `language selects one fixed localized server phrase`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()
        val service = service(provider)

        for ((index, language) in VoiceTutorVoicePreviewLanguage.entries.withIndex()) {
            service.preview(principal.copy(userId = index.toLong() + 1), "marin", language.code)
        }

        assertThat(provider.requests.map { it.language.samplePhrase }).containsExactly(
            "안녕하세요. 함께 공부해 볼까요?",
            "Hello. Shall we study together?",
            "こんにちは。一緒に勉強しましょう。",
        )
        assertThat(provider.requests).allMatch { it.voice == VoiceTutorRealtimeVoice.MARIN }
    }

    @Test
    fun `unsupported voice is rejected before a provider call`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()

        for (voice in listOf("", "DEFAULT", "nova", "marin ", "../marin")) {
            val error = assertThrows<ApiException> { service(provider).preview(principal, voice, "ko") }
            assertThat(error.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
            assertThat(error.code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        assertThat(provider.requests).isEmpty()
    }

    @Test
    fun `unsupported language is rejected instead of silently substituting copy`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()

        for (language in listOf("", "KO", "ko-KR", "fr", " ko ")) {
            val error = assertThrows<ApiException> { service(provider).preview(principal, "marin", language) }
            assertThat(error.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
            assertThat(error.code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        assertThat(provider.requests).isEmpty()
    }

    @Test
    fun `invalid configured default fails closed without calling OpenAI`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()
        val properties = properties().apply { voiceTutor.voice = "unsupported-config" }

        val error = assertThrows<ApiException> {
            service(provider, properties).preview(principal, "default", "ja")
        }

        assertThat(error.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(error.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
        assertThat(provider.requests).isEmpty()
    }

    @Test
    fun `disabled or unconfigured Voice Tutor cannot create a paid preview request`() = runBlocking<Unit> {
        for (properties in listOf(
            properties().apply { voiceTutor.enabled = false },
            properties().apply { openai.userContentApiKey = "" },
        )) {
            val provider = CapturingPreviewPort()
            val error = assertThrows<ApiException> {
                service(provider, properties).preview(principal, "marin", "ko")
            }
            assertThat(error.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(error.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PROVIDER_UNAVAILABLE)
            assertThat(provider.requests).isEmpty()
        }
    }

    @Test
    fun `anonymous account is rejected before configuration or provider access`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()
        val anonymous = principal.copy(anonymous = true)

        val error = assertThrows<ApiException> {
            service(provider).preview(anonymous, "marin", "ko")
        }

        assertThat(error.status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(error.code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        assertThat(provider.requests).isEmpty()
    }

    @Test
    fun `registered non Pro account cannot spend provider TTS`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()
        val freeQuota = eligibleQuota().copy(tierCode = "TIER1", planEligible = false)

        val error = assertThrows<ApiException> {
            service(provider, quota = freeQuota).preview(principal, "marin", "ko")
        }

        assertThat(error.status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(error.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PRO_REQUIRED)
        assertThat(provider.requests).isEmpty()
    }

    @Test
    fun `missing entitlement fails closed while exhausted Pro allowance does not consume quota`() = runBlocking<Unit> {
        val missingProvider = CapturingPreviewPort()
        val missing = assertThrows<ApiException> {
            service(missingProvider, quota = null).preview(principal, "marin", "ko")
        }
        assertThat(missing.code).isEqualTo(ApiErrorCode.VOICE_TUTOR_PRO_REQUIRED)
        assertThat(missingProvider.requests).isEmpty()

        val exhaustedProvider = CapturingPreviewPort()
        val exhausted = eligibleQuota().copy(baseSeconds = 60, usedSeconds = 60, reservedSeconds = 0)
        service(exhaustedProvider, quota = exhausted).preview(principal, "marin", "ko")
        assertThat(exhausted.remainingSeconds).isZero()
        assertThat(exhaustedProvider.requests).hasSize(1)
    }

    @Test
    fun `same account has a bounded cooldown without retaining preview audio`() = runBlocking<Unit> {
        val provider = CapturingPreviewPort()
        val clock = MutableClock(Instant.parse("2026-09-01T00:00:00Z"))
        val service = service(provider, clock = clock)

        service.preview(principal, "marin", "ko")
        val limited = assertThrows<ApiException> { service.preview(principal, "cedar", "en") }
        assertThat(limited.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(limited.code).isEqualTo(ApiErrorCode.SERVER_BUSY)
        assertThat(provider.requests).hasSize(1)

        clock.advance(Duration.ofSeconds(2))
        service.preview(principal, "cedar", "en")
        assertThat(provider.requests.map { it.voice })
            .containsExactly(VoiceTutorRealtimeVoice.MARIN, VoiceTutorRealtimeVoice.CEDAR)
    }

    @Test
    fun `admission bounds global concurrency release and inactive user LRU`() {
        val clock = MutableClock(Instant.parse("2026-09-01T00:00:00Z"))
        val concurrent = VoiceTutorVoicePreviewAdmissionPolicy(
            clock = clock,
            cooldown = Duration.ZERO,
            maxConcurrent = 8,
            maxTrackedUsers = 8,
        )
        (1L..8L).forEach { assertThat(concurrent.tryAcquire(it)).isTrue() }
        assertThat(concurrent.tryAcquire(9)).isFalse()
        concurrent.release(1)
        concurrent.release(1) // Idempotent release must not create a phantom permit.
        assertThat(concurrent.tryAcquire(9)).isTrue()
        assertThat(concurrent.tryAcquire(10)).isFalse()

        val bounded = VoiceTutorVoicePreviewAdmissionPolicy(
            clock = clock,
            cooldown = Duration.ofSeconds(10),
            maxConcurrent = 1,
            maxTrackedUsers = 2,
        )
        assertThat(bounded.tryAcquire(101)).isTrue()
        bounded.release(101)
        clock.advance(Duration.ofSeconds(1))
        assertThat(bounded.tryAcquire(102)).isTrue()
        bounded.release(102)
        clock.advance(Duration.ofSeconds(1))
        assertThat(bounded.tryAcquire(103)).isTrue() // Evicts oldest inactive user 101.
        bounded.release(103)
        assertThat(bounded.tryAcquire(101)).isTrue() // Would be cooling down if it were retained.
    }

    @Test
    fun `preview use case requires Voice Tutor read permission`() {
        val method = VoiceTutorVoicePreviewService::class.java.declaredMethods.single {
            it.name == "preview" && it.getAnnotation(RequirePermission::class.java) != null
        }

        assertThat(method.getAnnotation(RequirePermission::class.java).value)
            .containsExactly(Permissions.VOICE_TUTOR_READ)
        assertThat(VoiceTutorVoicePreviewUseCase::class.java.isAssignableFrom(VoiceTutorVoicePreviewService::class.java))
            .isTrue()
    }

    @Test
    fun `audio value enforces nonempty mp3 and hard response bound`() {
        val valid = VoiceTutorVoicePreviewAudio(byteArrayOf(1, 2, 3))
        assertThat(valid.contentType).isEqualTo("audio/mpeg")
        assertThat(valid.bytes).containsExactly(*byteArrayOf(1, 2, 3))

        assertThrows<IllegalArgumentException> { VoiceTutorVoicePreviewAudio(byteArrayOf()) }
        assertThrows<IllegalArgumentException> {
            VoiceTutorVoicePreviewAudio(ByteArray(VoiceTutorVoicePreviewAudio.MAX_AUDIO_BYTES + 1))
        }
        assertThrows<IllegalArgumentException> {
            VoiceTutorVoicePreviewAudio(byteArrayOf(1), "application/octet-stream")
        }
    }

    private fun service(
        provider: VoiceTutorVoicePreviewPort,
        properties: BuddyStudyProperties = properties(),
        clock: Clock = Clock.systemUTC(),
        quota: VoiceTutorQuotaSnapshot? = eligibleQuota(),
    ) = VoiceTutorVoicePreviewService(provider, properties, FixedQuotaPort(quota), clock)

    private fun properties() = BuddyStudyProperties().apply {
        voiceTutor.enabled = true
        voiceTutor.voice = "marin"
        openai.userContentApiKey = "private-user-content-key"
    }

    private fun eligibleQuota() = VoiceTutorQuotaSnapshot(
        tierCode = "TIER2",
        periodStartedAt = Instant.parse("2026-09-01T00:00:00Z"),
        periodEndsAt = Instant.parse("2026-10-01T00:00:00Z"),
        baseSeconds = 3_600,
        usedSeconds = 0,
        reservedSeconds = 0,
        planEligible = true,
    )

    private class FixedQuotaPort(private val quota: VoiceTutorQuotaSnapshot?) : VoiceTutorQuotaQueryPort {
        override suspend fun quota(userId: Long, now: Instant): VoiceTutorQuotaSnapshot? = quota
    }

    private class CapturingPreviewPort : VoiceTutorVoicePreviewPort {
        val requests = mutableListOf<VoiceTutorVoicePreviewRequest>()

        override suspend fun synthesize(request: VoiceTutorVoicePreviewRequest): VoiceTutorVoicePreviewAudio {
            requests += request
            return VoiceTutorVoicePreviewAudio(byteArrayOf(1, 2, 3))
        }
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
