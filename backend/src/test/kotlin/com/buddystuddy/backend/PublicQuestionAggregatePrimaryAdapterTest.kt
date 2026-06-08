package com.buddystuddy.backend

import com.buddystuddy.backend.community.adapter.outbound.PublicQuestionAggregatePrimaryAdapter
import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionAggregateCommandPort
import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionAggregateQueryPort
import com.buddystuddy.backend.community.domain.PublicQuestionSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class PublicQuestionAggregatePrimaryAdapterTest {
    @Test
    fun `returns redis aggregate without querying rdb`() {
        val cached = snapshot(id = "10", question = "cached")
        val redis = FakeQueryPort(cached)
        val rdb = FakeQueryPort(snapshot(id = "10", question = "rdb"))
        val redisCommand = FakeCommandPort()
        val adapter = PublicQuestionAggregatePrimaryAdapter(redis, redisCommand, rdb)

        val result = adapter.findByQuestionId(10)

        assertThat(result?.question).isEqualTo("cached")
        assertThat(redis.calls).isEqualTo(1)
        assertThat(rdb.calls).isZero()
        assertThat(redisCommand.saved).isEmpty()
    }

    @Test
    fun `loads from rdb and stores redis aggregate on cache miss`() {
        val loaded = snapshot(id = "10", question = "rdb")
        val redis = FakeQueryPort(null)
        val rdb = FakeQueryPort(loaded)
        val redisCommand = FakeCommandPort()
        val adapter = PublicQuestionAggregatePrimaryAdapter(redis, redisCommand, rdb)

        val result = adapter.findByQuestionId(10)

        assertThat(result).isEqualTo(loaded)
        assertThat(redis.calls).isEqualTo(1)
        assertThat(rdb.calls).isEqualTo(1)
        assertThat(redisCommand.saved).containsEntry(10, loaded)
    }

    private class FakeQueryPort(private val snapshot: PublicQuestionSnapshot?) : PublicQuestionAggregateQueryPort {
        var calls = 0

        override fun findByQuestionId(questionId: Long): PublicQuestionSnapshot? {
            calls += 1
            return snapshot
        }
    }

    private class FakeCommandPort : PublicQuestionAggregateCommandPort {
        val saved = mutableMapOf<Long, PublicQuestionSnapshot>()
        val evicted = mutableListOf<Long>()

        override fun save(questionId: Long, snapshot: PublicQuestionSnapshot) {
            saved[questionId] = snapshot
        }

        override fun evict(questionId: Long) {
            evicted += questionId
        }
    }

    private fun snapshot(id: String, question: String) = PublicQuestionSnapshot(
        id = id,
        question = question,
        answer = "answer",
        score = 90,
        correct = true,
        feedback = "good",
        explanation = "because",
        topic = "SwiftUI",
        difficultyLevel = 5,
        status = "graded",
        source = "manual",
        createdAt = Instant.EPOCH,
        answeredAt = Instant.EPOCH,
        author = null,
        likeCount = 1,
        commentCount = 2,
        viewCount = 3,
        isLikedByMe = false,
    )
}
