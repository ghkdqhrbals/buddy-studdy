package com.buddystudy.backend.common.application.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiErrorCodeTest {
    @Test
    fun `wire error numbers remain unique across voice and question features`() {
        assertThat(ApiErrorCode.entries.map { it.code }).doesNotHaveDuplicates()
        assertThat(ApiErrorCode.VOICE_TUTOR_PRO_REQUIRED.code).isEqualTo(511)
        assertThat(ApiErrorCode.VOICE_TUTOR_QUOTA_EXCEEDED.code).isEqualTo(512)
        assertThat(ApiErrorCode.FOLLOW_UP_NOT_AVAILABLE.code).isEqualTo(517)
        assertThat(ApiErrorCode.FOLLOW_UP_LIMIT_REACHED.code).isEqualTo(518)
    }
}
