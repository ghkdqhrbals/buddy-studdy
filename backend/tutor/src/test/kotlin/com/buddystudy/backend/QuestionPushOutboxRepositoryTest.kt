package com.buddystudy.backend

import com.buddystudy.backend.common.application.outbox.PublishedStreamRecord
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionPushOutboxRepository
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionPushOutboxEntity
import com.buddystudy.study.domain.entity.QuestionPushOutboxStatus
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
        val publication = PublishedStreamRecord("notification.question-push.requested.v1", "1-0")
        assertThat(outbox.markPublished(item.id, "wrong-token", publication, now)).isFalse()
        assertThat(outbox.findById(item.id)!!.status).isEqualTo(QuestionPushOutboxStatus.PROCESSING)
        assertThat(outbox.markPublished(item.id, claim.claimToken, publication, now)).isTrue()
        val published = outbox.findById(item.id)!!
        assertThat(published.status).isEqualTo(QuestionPushOutboxStatus.PUBLISHED)
        assertThat(published.streamKey).isEqualTo(publication.streamKey)
        assertThat(published.redisRecordId).isEqualTo(publication.recordId)
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
        assertThat(
            outbox.markPublished(
                item.id,
                reclaimed.claimToken,
                PublishedStreamRecord("notification.question-push.requested.v1", "2-0"),
                reclaimedAt,
            ),
        ).isTrue()
    }

    @Test
    fun `claimed push rebuilds a markdown-safe notification preview`(): Unit = runBlocking {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val item = outbox.save(
            QuestionPushOutboxEntity(
                recordId = 11,
                deviceId = "device-markdown",
                userId = 21,
                question = "# 컬렉션\n\n**List**와 `Set`의 차이는?",
                expectedAnswerHint = null,
                topic = "Kotlin",
                difficultyLevel = 3,
                language = SupportedLanguage.KOREAN,
                sound = "default",
                intervalMinutes = 15,
                status = QuestionPushOutboxStatus.PENDING,
                nextAttemptAt = now.minusSeconds(1),
                createdAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
            ),
        )

        val claimed = checkNotNull(outbox.claim(item.id, now, now.minusSeconds(120)))

        assertThat(claimed.request.title).isEqualTo("새 질문 도착")
        assertThat(claimed.request.body).isEqualTo("컬렉션\nList와 \"Set\"의 차이는?")
        assertThat(claimed.request.question).startsWith("# 컬렉션")
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
                language = SupportedLanguage.KOREAN,
                sound = "default",
                intervalMinutes = 15,
                status = QuestionPushOutboxStatus.PENDING,
                nextAttemptAt = now.minusSeconds(1),
                createdAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
            ),
        )
}
