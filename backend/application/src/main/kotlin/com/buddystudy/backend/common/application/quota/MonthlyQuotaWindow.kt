package com.buddystudy.backend.common.application.quota

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

object MonthlyQuotaWindow {
    private val zone = ZoneOffset.UTC

    fun periodAt(now: Instant): YearMonth =
        YearMonth.from(now.atZone(zone))

    fun resetAt(now: Instant): Instant =
        periodAt(now)
            .plusMonths(1)
            .atDay(1)
            .atStartOfDay(zone)
            .toInstant()

    fun exceededMetadata(
        now: Instant,
        additional: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        additional + mapOf(
            "quotaPeriod" to "MONTHLY",
            "quotaResetAt" to resetAt(now).toString(),
            "quotaTimeZone" to zone.id,
        )
}
