package com.buddystudy.backend

import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionPushOutboxRepository
import com.buddystudy.study.domain.entity.QuestionPushOutboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
class QuestionPushOutboxRepositoryTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var outbox: QuestionPushOutboxRepository

    @BeforeEach
    fun clearOutbox(): Unit = runBlocking {
        outbox.deleteAll()
        Unit
    }

    @Test
    fun `concurrent claim has one winner and completion is fenced by claim token`(): Unit = runBlocking {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val item = pendingItem(now)

        val claims = List(12) {
            async(Dispatchers.Default) {
                outbox.claim(item.id, now, now.minusSeconds(120))
            }
        }.awaitAll().filterNotNull()

        assertThat(claims).hasSize(1)
        val claim = claims.single()
        assertThat(outbox.markPublished(item.id, "wrong-token", now)).isFalse()
        assertThat(outbox.findById(item.id)!!.status).isEqualTo("PROCESSING")
        assertThat(outbox.markPublished(item.id, claim.claimToken, now)).isTrue()
        assertThat(outbox.findById(item.id)!!.status).isEqualTo("PUBLISHED")
    }

    @Test
    fun `stale processing claim is reclaimed and old owner cannot update it`(): Unit = runBlocking {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val item = pendingItem(now)
        val original = checkNotNull(outbox.claim(item.id, now, now.minusSeconds(120)))

        val reclaimedAt = now.plusSeconds(121)
        val reclaimed = checkNotNull(outbox.claim(item.id, reclaimedAt, reclaimedAt.minusSeconds(120)))

        assertThat(reclaimed.claimToken).isNotEqualTo(original.claimToken)
        assertThat(
            outbox.markRetry(
                id = item.id,
                claimToken = original.claimToken,
                attempts = 1,
                nextAttemptAt = reclaimedAt.plusSeconds(30),
                error = "old owner",
                updatedAt = reclaimedAt,
            ),
        ).isFalse()
        assertThat(outbox.markPublished(item.id, reclaimed.claimToken, reclaimedAt)).isTrue()
    }

    private suspend fun pendingItem(now: Instant): QuestionPushOutboxEntity =
        outbox.save(
            QuestionPushOutboxEntity(
                recordId = 10,
                deviceId = "device-1",
                userId = 20,
                question = "Question?",
                expectedAnswerHint = "Hint",
                topic = "Kotlin",
                difficultyLevel = 7,
                language = "ko",
                sound = "default",
                intervalMinutes = 15,
                status = "PENDING",
                nextAttemptAt = now.minusSeconds(1),
                createdAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
            ),
        )
}
