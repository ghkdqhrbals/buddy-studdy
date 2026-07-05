package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystudy.backend.community.application.port.outbound.SearchResult
import com.buddystudy.backend.community.application.service.QuestionSearchSyncManager
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionSearchTranslationPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.port.outbound.TranslatedQuestionSearchText
import com.buddystudy.backend.study.application.service.StudySyncService
import com.buddystudy.community.domain.entity.QuestionSearchEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

class StudySyncServiceTest {
    private val studies = FakeStudyPort()
    private val questions = FakeQuestionPort()
    private val questionStats = FakeQuestionStatsPort()
    private val service = StudySyncService(studies, questions, questionStats, fakeSearchSyncManager())
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `study list does not query pending question once per study`() {
        studies.rows += study(id = 11, topic = "Swift")
        studies.rows += study(id = 12, topic = "Kotlin")
        questions.pendingRows += pendingQuestion(id = 101, studyId = 11, topic = "Swift")
        questions.pendingRows += pendingQuestion(id = 102, studyId = 12, topic = "Kotlin")
        questionStats.rows += QuestionStatsEntity(questionId = 101, viewCount = 3)
        questionStats.rows += QuestionStatsEntity(questionId = 102, viewCount = 4)

        val response = service.study(principal, limit = 20, offset = 0, query = null)

        assertThat(response.studies.map { it.pendingQuestion?.id }).containsExactly("101", "102")
        assertThat(response.studies.map { it.pendingQuestion?.viewCount }).containsExactly(3, 4)
        assertThat(questions.findPendingByStudyIdCalls).isZero()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isEqualTo(1)
    }

    @Test
    fun `create new study does not query pending question`() {
        val response = service.createStudy(
            principal,
            com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand(topic = "Postgres"),
        )

        assertThat(response.topic).isEqualTo("Postgres")
        assertThat(response.pendingQuestion).isNull()
        assertThat(response.nextDueAt).isNotNull()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isZero()
    }

    @Test
    fun `saving existing study with same schedule keeps existing job due time`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val existingDueAt = now.plusSeconds(600)
        val existingStudy = study(id = 11, topic = "Postgres").apply {
            intervalMinutes = 15
            enabled = true
            nextDueAt = existingDueAt
        }
        studies.rows += existingStudy

        val response = service.createStudy(
            principal,
            com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand(
                topic = "Postgres",
                intervalMinutes = 15,
                enabled = true,
                customPrompt = "Updated prompt only",
            ),
        )

        assertThat(response.nextDueAt).isEqualTo(existingDueAt)
        assertThat(existingStudy.nextDueAt).isEqualTo(existingDueAt)
    }

    @Test
    fun `saving existing study with changed interval reschedules job`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val existingDueAt = now.plusSeconds(600)
        val existingStudy = study(id = 11, topic = "Postgres").apply {
            intervalMinutes = 15
            enabled = true
            nextDueAt = existingDueAt
        }
        studies.rows += existingStudy

        service.createStudy(
            principal,
            com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand(
                topic = "Postgres",
                intervalMinutes = 30,
                enabled = true,
            ),
        )

        assertThat(existingStudy.nextDueAt).isAfter(existingDueAt)
    }

    private fun study(id: Long, topic: String) = StudyEntity(
        id = id,
        deviceId = principal.deviceId,
        userId = principal.userId,
        topic = topic,
        difficultyLevel = 5,
        createdAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
        updatedAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
    )

    private fun pendingQuestion(id: Long, studyId: Long, topic: String) = QuestionEntity(
        id = id,
        deviceId = principal.deviceId,
        userId = principal.userId,
        studyId = studyId,
        question = "Question $topic",
        topic = topic,
        difficultyLevel = 5,
        scheduledFor = Instant.parse("2026-06-10T00:00:00Z"),
        sentAt = Instant.parse("2026-06-10T00:00:00Z"),
        status = "ungraded",
        createdAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
        updatedAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
    )

    private class FakeStudyPort : StudyPort {
        val rows = mutableListOf<StudyEntity>()
        override fun save(entity: StudyEntity): StudyEntity = entity
        override fun deleteByIdAndUserId(id: Long, userId: Long): Long = rows.removeAll { it.id == id && it.userId == userId }.let { if (it) 1 else 0 }
        override fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = null
        override fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? = rows.firstOrNull { it.id == id && it.userId == userId }
        override fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? = rows.firstOrNull { it.userId == userId && it.topic == topic }
        override fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> =
            rows.filter { it.userId == userId && it.topic in topics }
        override fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId }, pageable, rows.count { it.userId == userId }.toLong())
        override fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId && it.topic.contains(query, ignoreCase = true) }, pageable, rows.count { it.userId == userId }.toLong())
        override fun claimDue(now: Instant, limit: Int): List<StudyEntity> = emptyList()
    }

    private class FakeQuestionPort : QuestionPort {
        val pendingRows = mutableListOf<QuestionEntity>()
        var findPendingByStudyIdCalls = 0
        var findLatestPendingByStudyIdsCalls = 0
        override fun save(entity: QuestionEntity): QuestionEntity = entity
        override fun findQuestionById(id: Long): Optional<QuestionEntity> = Optional.empty()
        override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = null
        override fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> {
            findPendingByStudyIdCalls += 1
            return PageImpl(pendingRows.filter { it.studyId == studyId }.sortedByDescending { it.createdAt }.take(1), pageable, 1)
        }
        override fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> {
            findLatestPendingByStudyIdsCalls += 1
            return pendingRows
                .filter { it.studyId in studyIds }
                .groupBy { it.studyId }
                .values
                .mapNotNull { rows -> rows.maxWithOrNull(compareBy<QuestionEntity> { it.createdAt }.thenBy { it.id }) }
        }
        override fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override fun countPendingForStudy(studyId: Long): Long = pendingRows.count { it.studyId == studyId }.toLong()
        override fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> =
            pendingRows
                .filter { it.studyId in studyIds }
                .groupingBy { it.studyId!! }
                .eachCount()
                .mapValues { it.value.toLong() }
        override fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredById(id: Long): QuestionEntity? = null
        override fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = emptyList()
        override fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
        override fun softDeleteByStudyId(studyId: Long, userId: Long, now: Instant): Int = 0
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        val rows = mutableListOf<QuestionStatsEntity>()
        var findByIdCalls = 0
        var findAllByIdsCalls = 0
        override fun save(entity: QuestionStatsEntity): QuestionStatsEntity = entity
        override fun findById(id: Long): Optional<QuestionStatsEntity> {
            findByIdCalls += 1
            return Optional.ofNullable(rows.firstOrNull { it.questionId == id })
        }
        override fun findAllByIds(ids: Collection<Long>): List<QuestionStatsEntity> {
            findAllByIdsCalls += 1
            return rows.filter { it.questionId in ids }
        }
        override fun incrementView(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun incrementLike(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }

    private class FakeUserPort : UserPort {
        override fun save(entity: UserEntity): UserEntity = entity
        override fun findById(id: Long): Optional<UserEntity> = Optional.empty()
        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = mutableListOf()
        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeQuestionSearchPort : QuestionSearchPort {
        override fun save(entity: QuestionSearchEntity): QuestionSearchEntity = entity
        override fun deleteByQuestionId(questionId: Long): Long = 0
        override fun deleteByStudyId(studyId: Long, userId: Long): Long = 0
        override fun searchPublic(query: String?, language: String, limit: Int, offset: Int): SearchResult = SearchResult(emptyList(), 0)
        override fun findPublicByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? = null
        override fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? = null
    }

    private class FakeQuestionSearchTranslator : QuestionSearchTranslationPort {
        override fun translateSearchText(
            sourceLanguage: String,
            targetLanguage: String,
            topic: String,
            question: String,
            answer: String?,
            feedback: String?,
            explanation: String?,
        ): TranslatedQuestionSearchText = TranslatedQuestionSearchText(topic, question, answer, feedback, explanation)
    }

    private fun fakeSearchSyncManager() =
        QuestionSearchSyncManager(BuddyStudyProperties(), FakeQuestionPort(), FakeUserPort(), FakeQuestionSearchPort(), FakeQuestionSearchTranslator())
}
