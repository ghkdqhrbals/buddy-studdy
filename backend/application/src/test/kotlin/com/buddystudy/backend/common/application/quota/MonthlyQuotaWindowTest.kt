package com.buddystudy.backend.common.application.quota

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth

class MonthlyQuotaWindowTest {
    @Test
    fun `monthly quota resets at the next UTC month boundary`() {
        val now = Instant.parse("2026-07-23T12:34:56Z")

        assertThat(MonthlyQuotaWindow.periodAt(now)).isEqualTo(YearMonth.of(2026, 7))
        assertThat(MonthlyQuotaWindow.resetAt(now)).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"))
        assertThat(MonthlyQuotaWindow.exceededMetadata(now)).containsEntry(
            "quotaResetAt",
            "2026-08-01T00:00:00Z",
        )
    }
}
