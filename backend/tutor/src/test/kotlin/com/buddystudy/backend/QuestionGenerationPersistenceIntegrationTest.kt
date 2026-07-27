package com.buddystudy.backend

import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionGenerationSagaRepository
import com.buddystudy.backend.common.adapter.outbound.persistence.StreamConsumerInboxRepository
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    properties = [
        "buddystudy.streams.enabled=false",
        "buddystudy.scheduler.enabled=false",
    ],
)
class QuestionGenerationPersistenceIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired
    private lateinit var inbox: StreamConsumerInboxRepository

    @Autowired
    private lateinit var sagas: QuestionGenerationSagaRepository

    @Test
    fun `consumer groups deduplicate independently and recover only after the lease expires`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val eventId = "event-$suffix"
        val correlationId = UUID.randomUUID().toString()
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val lease = Duration.ofMinutes(3)

        val generation = inbox.claim(eventId, "generation", correlationId, lease, now)
        assertThat(generation).isNotNull
        assertThat(generation!!.attempt).isEqualTo(1)
        assertThat(inbox.claim(eventId, "generation", correlationId, lease, now.plusSeconds(179))).isNull()

        val audit = inbox.claim(eventId, "audit", correlationId, lease, now.plusSeconds(1))
        assertThat(audit).isNotNull
        assertThat(audit!!.attempt).isEqualTo(1)

        val recovered = inbox.claim(eventId, "generation", correlationId, lease, now.plusSeconds(180))
        assertThat(recovered).isNotNull
        assertThat(recovered!!.attempt).isEqualTo(2)
        assertThat(inbox.markSucceeded(recovered, now.plusSeconds(181))).isTrue()
        assertThat(inbox.claim(eventId, "generation", correlationId, lease, now.plusSeconds(600))).isNull()

        assertThat(inbox.markSucceeded(audit, now.plusSeconds(2))).isTrue()
        assertThat(inbox.claim(eventId, "audit", correlationId, lease, now.plusSeconds(600))).isNull()
    }

    @Test
    fun `saga idempotency active topic guard and state transitions are compare and set`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val userId = positiveId(suffix, 1)
        val studyId = positiveId(suffix, 2)
        val topicId = positiveId(suffix, 3)
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val first = saga(
            correlationId = UUID.randomUUID().toString(),
            userId = userId,
            studyId = studyId,
            topicId = topicId,
            idempotencyKey = "manual:$suffix",
            now = now,
        )

        assertThat(sagas.insert(first)).isTrue()
        assertThat(
            sagas.insert(
                first.copy(
                    correlationId = UUID.randomUUID().toString(),
                    topicId = topicId + 1,
                ),
            ),
        ).isFalse()
        assertThat(
            sagas.insert(
                first.copy(
                    correlationId = UUID.randomUUID().toString(),
                    idempotencyKey = "manual:$suffix-active-topic",
                ),
            ),
        ).isFalse()

        assertThat(sagas.markGenerating(first.correlationId, now.plusSeconds(1))).isTrue()
        assertThat(sagas.markGenerating(first.correlationId, now.plusSeconds(2))).isFalse()
        assertThat(sagas.markTranslating(first.correlationId, 900_001, now.plusSeconds(3))).isTrue()
        assertThat(sagas.markTranslating(first.correlationId, 900_002, now.plusSeconds(4))).isFalse()
        assertThat(sagas.markCompleted(first.correlationId, now.plusSeconds(5))).isTrue()
        assertThat(sagas.markCompleted(first.correlationId, now.plusSeconds(6))).isFalse()

        val completed = sagas.findByCorrelationId(first.correlationId)
        assertThat(completed?.status).isEqualTo(QuestionGenerationStatus.COMPLETED)
        assertThat(completed?.questionId).isEqualTo(900_001)
        assertThat(completed?.completedAt).isEqualTo(now.plusSeconds(5))

        assertThat(
            sagas.insert(
                first.copy(
                    correlationId = UUID.randomUUID().toString(),
                    idempotencyKey = "manual:$suffix-next",
                    createdAt = now.plusSeconds(7),
                    updatedAt = now.plusSeconds(7),
                ),
            ),
        ).isTrue()
    }

    private fun saga(
        correlationId: String,
        userId: Long,
        studyId: Long,
        topicId: Long,
        idempotencyKey: String,
        now: Instant,
    ) = QuestionGenerationSaga(
        correlationId = correlationId,
        userId = userId,
        studyId = studyId,
        topicId = topicId,
        questionId = null,
        source = QuestionGenerationSource.MANUAL,
        status = QuestionGenerationStatus.QUEUED,
        currentStep = QuestionGenerationStep.QUEUED,
        idempotencyKey = idempotencyKey,
        quotaPeriodStartedAt = now.minusSeconds(60),
        quotaRefundedAt = null,
        failedStep = null,
        errorCode = null,
        errorMessage = null,
        createdAt = now,
        updatedAt = now,
        completedAt = null,
    )

    private fun positiveId(seed: String, salt: Int): Long =
        ((seed.hashCode().toLong() shl 8) xor salt.toLong()).let {
            if (it == Long.MIN_VALUE) 1 else kotlin.math.abs(it).coerceAtLeast(1)
        }
}
