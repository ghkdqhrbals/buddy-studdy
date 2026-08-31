package com.buddystudy.backend.study.application.policy

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.model.StudyLearningRecordKey
import com.buddystudy.backend.study.application.model.StudyLearningRecordScope
import com.buddystudy.backend.study.application.model.StudyLearningRecordSource
import com.buddystudy.backend.study.application.model.StudyLearningRecordsCursor
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.Base64

/** A bounded keyset position, bound to the owning user's requested tree scope. Never an access grant. */
object StudyLearningRecordsCursorPolicy {
    fun encode(userId: Long, studyId: Long, scope: StudyLearningRecordScope, key: StudyLearningRecordKey): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            listOf("v1", userId, studyId, scope.name, key.createdAt, key.source.name, key.recordId)
                .joinToString("|").toByteArray(Charsets.UTF_8),
        )

    fun decode(value: String?, userId: Long, studyId: Long, scope: StudyLearningRecordScope): StudyLearningRecordsCursor? {
        if (value == null) return null
        try {
            require(value.length in 1..512)
            val parts = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('|')
            require(parts.size == 7 && parts[0] == "v1")
            require(parts[1].toLong() == userId && parts[2].toLong() == studyId && parts[3] == scope.name)
            val at = Instant.parse(parts[4])
            require(at >= Instant.EPOCH && at <= MAX_TIMESTAMP)
            val source = StudyLearningRecordSource.valueOf(parts[5])
            val id = parts[6].toLong().also { require(it > 0) }
            return StudyLearningRecordsCursor(at, source, id)
        } catch (_: Exception) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Invalid learning-record cursor.")
        }
    }

    private val MAX_TIMESTAMP = Instant.parse("9999-12-31T23:59:59.999999Z")
}
