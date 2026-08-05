package com.buddystudy.backend.common.application.quota

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth

class MonthlyQuotaWindowTest {
    @Test
    fun `monthly quota follows the account creation day and time`() {
        val accountCreatedAt = Instant.parse("2026-01-07T12:34:56Z")
        val now = Instant.parse("2026-07-23T10:00:00Z")

        val period = MonthlyQuotaWindow.periodAt(accountCreatedAt, now)

        assertThat(period.usageMonth).isEqualTo(YearMonth.of(2026, 7))
        assertThat(period.startedAt).isEqualTo(Instant.parse("2026-07-07T12:34:56Z"))
        assertThat(period.resetAt).isEqualTo(Instant.parse("2026-08-07T12:34:56Z"))
        assertThat(MonthlyQuotaWindow.exceededMetadata(accountCreatedAt, now))
            .containsEntry("quotaResetAt", "2026-08-07T12:34:56Z")
            .containsEntry("quotaPeriodStartedAt", "2026-07-07T12:34:56Z")
    }

    @Test
    fun `month end accounts use the shorter month end without drifting later cycles`() {
        val accountCreatedAt = Instant.parse("2026-01-31T10:15:00Z")

        val februaryPeriod = MonthlyQuotaWindow.periodAt(
            accountCreatedAt,
            Instant.parse("2026-02-20T00:00:00Z"),
        )
        val marchPeriod = MonthlyQuotaWindow.periodAt(
            accountCreatedAt,
            Instant.parse("2026-03-15T00:00:00Z"),
        )

        assertThat(februaryPeriod.startedAt).isEqualTo(Instant.parse("2026-01-31T10:15:00Z"))
        assertThat(februaryPeriod.resetAt).isEqualTo(Instant.parse("2026-02-28T10:15:00Z"))
        assertThat(marchPeriod.startedAt).isEqualTo(Instant.parse("2026-02-28T10:15:00Z"))
        assertThat(marchPeriod.resetAt).isEqualTo(Instant.parse("2026-03-31T10:15:00Z"))
    }

    @Test
    fun `exact anniversary starts the next quota period`() {
        val accountCreatedAt = Instant.parse("2026-01-31T10:15:00Z")

        val period = MonthlyQuotaWindow.periodAt(
            accountCreatedAt,
            Instant.parse("2026-02-28T10:15:00Z"),
        )

        assertThat(period.usageMonth).isEqualTo(YearMonth.of(2026, 2))
        assertThat(period.startedAt).isEqualTo(Instant.parse("2026-02-28T10:15:00Z"))
        assertThat(period.resetAt).isEqualTo(Instant.parse("2026-03-31T10:15:00Z"))
    }

    @Test
    fun `leap day anchor returns to the original day after February`() {
        val anchor = Instant.parse("2024-01-31T23:45:00Z")

        val february = MonthlyQuotaWindow.periodAt(anchor, Instant.parse("2024-02-29T12:00:00Z"))
        val march = MonthlyQuotaWindow.periodAt(anchor, Instant.parse("2024-03-30T12:00:00Z"))

        assertThat(february.resetAt).isEqualTo(Instant.parse("2024-02-29T23:45:00Z"))
        assertThat(march.startedAt).isEqualTo(Instant.parse("2024-02-29T23:45:00Z"))
        assertThat(march.resetAt).isEqualTo(Instant.parse("2024-03-31T23:45:00Z"))
    }
}
