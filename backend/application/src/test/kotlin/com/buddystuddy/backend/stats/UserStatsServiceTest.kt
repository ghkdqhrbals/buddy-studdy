package com.buddystuddy.backend.stats

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.stats.application.model.StatsQuery
import com.buddystuddy.backend.stats.application.port.outbound.UserStatsPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.stats.domain.entity.UserStatsEntity
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

class UserStatsServiceTest {
    private val questions = FakeQuestionPort()
    private val userStats = FakeUserStatsPort()
    private val questionStats = FakeQuestionStatsPort()
    private val refresh = StatsRefreshService(questions, userStats)
    private val service = StatsService(userStats, questions, questionStats)
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `refresh creates one daily row per user topic date and difficulty`() {
        questions.rows += gradedQuestion(topic = "Swift UI", difficultyLevel = 5, score = 80, correct = true, answeredAt = "2026-06-10T02:00:00Z")
        questions.rows += gradedQuestion(topic = "swift  ui", difficultyLevel = 5, score = 60, correct = false, answeredAt = "2026-06-10T03:00:00Z")
        questions.rows += gradedQuestion(topic = "SwiftUI", difficultyLevel = 6, score = 90, correct = true, answeredAt = "2026-06-11T04:00:00Z")

        refresh.refreshAll(Instant.parse("2026-06-13T00:00:00Z"))

        assertThat(userStats.rows).hasSize(2)
        val sameDay = userStats.rows.single { it.statDate == LocalDate.parse("2026-06-10") }
        assertThat(sameDay.topicKey).isEqualTo("swift ui")
        assertThat(sameDay.responseCount).isEqualTo(2)
        assertThat(sameDay.scoreCount).isEqualTo(2)
        assertThat(sameDay.scoreSum).isEqualTo(140)
        assertThat(sameDay.bestScore).isEqualTo(80)
        assertThat(sameDay.correctCount).isEqualTo(1)
    }

    @Test
    fun `stats reads aggregated user stats by selected date range`() {
        userStats.rows += UserStatsEntity(
            userId = 7,
            statDate = LocalDate.parse("2026-06-01"),
            topicKey = "redis",
            topic = "Redis",
            difficultyLevel = 2,
            responseCount = 4,
            scoreCount = 4,
            scoreSum = 320,
            bestScore = 90,
            correctCount = 3,
            latestAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
        userStats.rows += UserStatsEntity(
            userId = 7,
            statDate = LocalDate.parse("2026-06-10"),
            topicKey = "swiftui",
            topic = "SwiftUI",
            difficultyLevel = 6,
            responseCount = 2,
            scoreCount = 2,
            scoreSum = 180,
            bestScore = 95,
            correctCount = 2,
            latestAt = Instant.parse("2026-06-10T08:00:00Z"),
        )
        questions.rows += gradedQuestion(topic = "SwiftUI", difficultyLevel = 6, score = 95, correct = true, answeredAt = "2026-06-10T08:00:00Z")

        val response = service.stats(
            principal,
            limit = 10,
            offset = 0,
            query = StatsQuery(startAt = Instant.parse("2026-06-09T00:00:00Z"), endAt = Instant.parse("2026-06-11T00:00:00Z")),
        )

        assertThat(response.totalResponses).isEqualTo(2)
        assertThat(response.totalTopics).isEqualTo(1)
        assertThat(response.topics.map { it.topic }).containsExactly("SwiftUI")
        assertThat(response.topics.single().average).isEqualTo(90)
        assertThat(response.topics.single().levelRange.level).isEqualTo(6)
    }

    private fun gradedQuestion(
        topic: String,
        difficultyLevel: Int,
        score: Int,
        correct: Boolean,
        answeredAt: String,
    ) = QuestionEntity(
        id = (questions.rows.size + 1).toLong(),
        deviceId = "dev-1",
        userId = 7,
        question = "Question $topic",
        topic = topic,
        difficultyLevel = difficultyLevel,
        scheduledFor = Instant.parse(answeredAt),
        status = "graded",
        answer = "Answer",
        score = score,
        correct = correct,
        answeredAt = Instant.parse(answeredAt),
        gradedAt = Instant.parse(answeredAt),
        createdAt = Instant.parse(answeredAt),
        updatedAt = Instant.parse(answeredAt),
    )

    private class FakeUserStatsPort : UserStatsPort {
        val rows = mutableListOf<UserStatsEntity>()
        override fun replaceAll(rows: Collection<UserStatsEntity>) {
            this.rows.clear()
            this.rows.addAll(rows)
        }

        override fun findByUser(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): List<UserStatsEntity> =
            rows.filter { row ->
                row.userId == userId &&
                    (startDate == null || !row.statDate.isBefore(startDate)) &&
                    (endDate == null || row.statDate.isBefore(endDate)) &&
                    (query.isNullOrBlank() || row.topic.contains(query, ignoreCase = true) || row.topicKey.contains(query, ignoreCase = true))
            }
    }

    private class FakeQuestionPort : QuestionPort {
        val rows = mutableListOf<QuestionEntity>()
        override fun save(entity: QuestionEntity): QuestionEntity = entity
        override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = rows.firstOrNull { it.id == id && it.userId == userId }
        override fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = PageImpl(rows.filter { it.userId == userId && it.score != null })
        override fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = PageImpl(rows.filter { it.userId == userId && it.score != null && it.topic.contains(query, ignoreCase = true) })
        override fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = PageImpl(rows.filter { it.userId == userId && it.score != null && it.topic in topics })
        override fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = PageImpl(rows.filter { it.score != null && it.deletedAt == null })
        override fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun countPendingForStudy(studyId: Long): Long = 0
        override fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredById(id: Long): QuestionEntity? = null
        override fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        override fun save(entity: QuestionStatsEntity): QuestionStatsEntity = entity
        override fun findById(id: Long): Optional<QuestionStatsEntity> = Optional.empty()
        override fun incrementView(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun incrementLike(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }
}
