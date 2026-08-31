package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.model.StudyLearningRecordKey
import com.buddystudy.backend.study.application.model.StudyLearningRecordScope
import com.buddystudy.backend.study.application.model.StudyLearningRecordSource
import com.buddystudy.backend.study.application.model.StudyLearningRecordsCursor
import com.buddystudy.backend.study.application.policy.StudyLearningRecordsCursorPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.Base64

class StudyLearningRecordsCursorPolicyTest {
    @Test
    fun `all sources and scopes round trip microseconds and large numeric IDs without opaque-text drift`() {
        for (scope in StudyLearningRecordScope.entries) {
            for (source in StudyLearningRecordSource.entries) {
                val key = StudyLearningRecordKey(source, Long.MAX_VALUE, 12, AT)
                // The cursor is bound to the requested root, not the final descendant key's studyId.
                val encoded = StudyLearningRecordsCursorPolicy.encode(USER, NODE, scope, key)

                assertThat(encoded).matches("[A-Za-z0-9_-]+")
                assertThat(encoded.length).isLessThanOrEqualTo(512)
                assertThat(StudyLearningRecordsCursorPolicy.decode(encoded, USER, NODE, scope))
                    .isEqualTo(StudyLearningRecordsCursor(AT, source, Long.MAX_VALUE))
                assertThat(StudyLearningRecordsCursorPolicy.encode(USER, NODE, scope, key)).isEqualTo(encoded)
            }
        }
    }

    @Test
    fun `only absent cursor means first page and blank or whitespace is invalid`() {
        assertThat(StudyLearningRecordsCursorPolicy.decode(null, USER, NODE, StudyLearningRecordScope.NODE)).isNull()
        for (value in listOf("", " ", "\n", "\t")) assertInvalid(value)
    }

    @Test
    fun `malformed base64 framing version source and numeric position fail closed`() {
        val invalid = listOf(
            "%%not_base64%%", "a", "a|b|c", encoded("not-a-cursor"),
            encoded("v2|7|10|NODE|$AT|QUESTION|9"),
            encoded("v1|7|10|NODE|$AT|QUESTION"),
            encoded("v1|7|10|NODE|$AT|QUESTION|9|extra"),
            encoded("v1|user|10|NODE|$AT|QUESTION|9"),
            encoded("v1|7|node|NODE|$AT|QUESTION|9"),
            encoded("v1|7|10|node|$AT|QUESTION|9"),
            encoded("v1|7|10|NODE|$AT|AUDIO|9"),
            encoded("v1|7|10|NODE|$AT|question|9"),
            encoded("v1|7|10|NODE|$AT|QUESTION|0"),
            encoded("v1|7|10|NODE|$AT|QUESTION|-1"),
            encoded("v1|7|10|NODE|$AT|QUESTION|9.5"),
            encoded("v1|7|10|NODE|$AT|QUESTION|9223372036854775808"),
        )
        invalid.forEach { assertInvalid(it) }
    }

    @Test
    fun `oversized cursors are rejected before decoding unbounded contents`() {
        for (value in listOf("A".repeat(513), "A".repeat(8_192), encoded("x".repeat(1_024)))) {
            assertInvalid(value)
        }
    }

    @Test
    fun `same cursor cannot cross owner selected node or subtree boundary`() {
        val key = StudyLearningRecordKey(StudyLearningRecordSource.QUESTION, 9, NODE, AT)
        val cursor = StudyLearningRecordsCursorPolicy.encode(USER, NODE, StudyLearningRecordScope.NODE, key)

        assertInvalid(cursor, userId = 8)
        assertInvalid(cursor, studyId = 11)
        assertInvalid(cursor, scope = StudyLearningRecordScope.SUBTREE)
        val subtree = StudyLearningRecordsCursorPolicy.encode(USER, NODE, StudyLearningRecordScope.SUBTREE, key)
        assertInvalid(subtree, scope = StudyLearningRecordScope.NODE)
    }

    @Test
    fun `timestamp boundaries exclude pre-epoch future-overflow and malformed positions`() {
        val invalidTimes = listOf("1969-12-31T23:59:59.999999Z", "+10000-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z", "not-a-time")
        for (timestamp in invalidTimes) assertInvalid(encoded("v1|7|10|NODE|$timestamp|QUESTION|9"))
        for (timestamp in listOf(Instant.EPOCH, Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            val decoded = StudyLearningRecordsCursorPolicy.decode(encoded("v1|7|10|NODE|$timestamp|QUESTION|9"), USER, NODE, StudyLearningRecordScope.NODE)
            assertThat(decoded?.createdAt).isEqualTo(timestamp)
        }
    }

    @Test
    fun `colliding timestamps keep source and numeric ID tie breakers distinct`() {
        val positions = listOf(
            StudyLearningRecordsCursor(AT, StudyLearningRecordSource.QUESTION, 10),
            StudyLearningRecordsCursor(AT, StudyLearningRecordSource.QUESTION, 9),
            StudyLearningRecordsCursor(AT, StudyLearningRecordSource.VOICE_TUTOR, 10),
            StudyLearningRecordsCursor(AT, StudyLearningRecordSource.VOICE_TUTOR, 9),
        )
        val cursors = positions.map { position ->
            StudyLearningRecordsCursorPolicy.encode(USER, NODE, StudyLearningRecordScope.NODE,
                StudyLearningRecordKey(position.source, position.recordId, NODE, position.createdAt))
        }

        assertThat(cursors.toSet()).hasSize(4)
        assertThat(cursors.map { StudyLearningRecordsCursorPolicy.decode(it, USER, NODE, StudyLearningRecordScope.NODE) })
            .containsExactlyElementsOf(positions)
    }

    private fun assertInvalid(value: String, userId: Long = USER, studyId: Long = NODE, scope: StudyLearningRecordScope = StudyLearningRecordScope.NODE) {
        val failure = runCatching { StudyLearningRecordsCursorPolicy.decode(value, userId, studyId, scope) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat(failure.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(failure.message).isEqualTo("Invalid learning-record cursor.")
    }

    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val USER = 7L
        const val NODE = 10L
        val AT: Instant = Instant.parse("2026-08-31T03:04:05.123456Z")
    }
}
