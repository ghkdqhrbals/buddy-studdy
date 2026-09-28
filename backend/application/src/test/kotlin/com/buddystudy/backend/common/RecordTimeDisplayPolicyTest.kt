package com.buddystudy.backend.common

import com.buddystudy.backend.common.application.model.RecordTimeDisplayPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant

class RecordTimeDisplayPolicyTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z")

    @ParameterizedTest
    @CsvSource(
        "-60, 방금 전, just now, たった今",
        "0, 방금 전, just now, たった今",
        "59, 방금 전, just now, たった今",
        "60, 1분 전, 1 minute ago, 1分前",
        "119, 1분 전, 1 minute ago, 1分前",
        "120, 2분 전, 2 minutes ago, 2分前",
        "3599, 59분 전, 59 minutes ago, 59分前",
        "3600, 1시간 전, 1 hour ago, 1時間前",
        "7200, 2시간 전, 2 hours ago, 2時間前",
        "86399, 23시간 전, 23 hours ago, 23時間前",
        "86400, 1일 전, 1 day ago, 1日前",
        "604799, 6일 전, 6 days ago, 6日前",
        "604800, 1주 전, 1 week ago, 1週間前",
        "2591999, 4주 전, 4 weeks ago, 4週間前",
        "2592000, 1개월 전, 1 month ago, 1か月前",
        "31535999, 12개월 전, 12 months ago, 12か月前",
        "31536000, 1년 전, 1 year ago, 1年前",
        "63072000, 2년 전, 2 years ago, 2年前",
    )
    fun `elapsed time floors into localized units at the exact boundaries`(seconds: Long, korean: String, english: String, japanese: String) {
        for ((locale, expected) in listOf("ko-KR" to korean, "en_US" to english, "ja-JP" to japanese)) {
            val result = RecordTimeDisplayPolicy(locale, now).display(now.minusSeconds(seconds), now.minusSeconds(999_999))
            assertThat(result.relativeText).describedAs("%s at %s seconds", locale, seconds).isEqualTo(expected)
            assertThat(result.timestamp).isEqualTo(now.minusSeconds(seconds))
            assertThat(result.generatedAt).isEqualTo(now)
            assertThat(result.language).isEqualTo(locale.take(2))
        }
    }

    @Test
    fun `answer submission wins over generation while unanswered records fall back to creation`() {
        val policy = RecordTimeDisplayPolicy("en", now)
        val createdAt = now.minusSeconds(86_400)
        assertThat(policy.display(now.minusSeconds(120), createdAt).relativeText).isEqualTo("2 minutes ago")
        val unanswered = policy.display(null, createdAt)
        assertThat(unanswered.timestamp).isEqualTo(createdAt)
        assertThat(unanswered.relativeText).isEqualTo("1 day ago")
    }

    @Test
    fun `unsupported locale uses the established Korean fallback`() {
        val result = RecordTimeDisplayPolicy("fr-FR", now).display(null, now.minusSeconds(60))
        assertThat(result.language).isEqualTo("ko")
        assertThat(result.relativeText).isEqualTo("1분 전")
    }

    @Test
    fun `fractional seconds do not advance the minute early`() {
        val result = RecordTimeDisplayPolicy("en", now).display(now.minusSeconds(60).plusNanos(1), Instant.EPOCH)
        assertThat(result.relativeText).isEqualTo("just now")
    }
}
