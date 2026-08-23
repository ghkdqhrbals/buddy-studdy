package com.buddystudy.backend.common.application.quota

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class MonthlyQuotaPeriod(
    val usageMonth: YearMonth,
    val startedAt: Instant,
    val resetAt: Instant,
)

object MonthlyQuotaWindow {
    private val zone = ZoneOffset.UTC

    fun periodAt(accountCreatedAt: Instant, now: Instant): MonthlyQuotaPeriod {
        require(!now.isBefore(accountCreatedAt)) { "Quota time cannot precede account creation." }

        val anchor = accountCreatedAt.atZone(zone)
        val current = now.atZone(zone)
        var elapsedMonths = ChronoUnit.MONTHS.between(
            anchor.toLocalDate().withDayOfMonth(1),
            current.toLocalDate().withDayOfMonth(1),
        )
        if (anchor.plusMonths(elapsedMonths).isAfter(current)) {
            elapsedMonths -= 1
        }

        val startedAt = anchor.plusMonths(elapsedMonths).toInstant()
        val resetAt = anchor.plusMonths(elapsedMonths + 1).toInstant()
        return MonthlyQuotaPeriod(
            usageMonth = YearMonth.from(startedAt.atZone(zone)),
            startedAt = startedAt,
            resetAt = resetAt,
        )
    }

    fun exceededMetadata(
        accountCreatedAt: Instant,
        now: Instant,
        additional: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val period = periodAt(accountCreatedAt, now)
        return additional + mapOf(
            "quotaPeriod" to "MONTHLY",
            "quotaPeriodStartedAt" to period.startedAt.toString(),
            "quotaResetAt" to period.resetAt.toString(),
            "quotaTimeZone" to zone.id,
        )
    }
}
