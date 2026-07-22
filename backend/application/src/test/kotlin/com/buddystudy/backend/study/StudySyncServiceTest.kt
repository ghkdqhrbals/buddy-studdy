package com.buddystudy.backend.study

import kotlinx.coroutines.runBlocking

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
    private val serviceSearchPort = FakeQuestionSearchPort()
    private val service = StudySyncService(studies, questions, questionStats, fakeSearchSyncManager(serviceSearchPort))
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `study list does not query pending question once per study`(): Unit = runBlocking {
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
    fun `create new study does not query pending question`(): Unit = runBlocking {
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
    fun `saving existing study with same schedule keeps existing job due time`(): Unit = runBlocking {
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
    fun `saving existing study with changed interval reschedules job`(): Unit = runBlocking {
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

    @Test
    fun `deleting study also removes same-topic records and search rows`(): Unit = runBlocking {
        studies.rows += study(id = 8, topic = "Redis")

        service.deleteStudy(principal, studyId = 8)

        assertThat(questions.softDeletedStudyIds).containsExactly(8)
        assertThat(questions.softDeletedTopics).containsExactly("Redis")
        assertThat(serviceSearchPort.deletedStudyIds).containsExactly(8)
        assertThat(serviceSearchPort.deletedTopics).containsExactly("Redis")
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
        override suspend fun save(entity: StudyEntity): StudyEntity = entity
        override suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long = rows.removeAll { it.id == id && it.userId == userId }.let { if (it) 1 else 0 }
        override suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = null
        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? = rows.firstOrNull { it.id == id && it.userId == userId }
        override suspend fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? = rows.firstOrNull { it.userId == userId && it.topic == topic }
        override suspend fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> =
            rows.filter { it.userId == userId && it.topic in topics }
        override suspend fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId }, pageable, rows.count { it.userId == userId }.toLong())
        override suspend fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId && it.topic.contains(query, ignoreCase = true) }, pageable, rows.count { it.userId == userId }.toLong())
        override suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity> = emptyList()
    }

    private class FakeQuestionPort : QuestionPort {
        val pendingRows = mutableListOf<QuestionEntity>()
        val softDeletedStudyIds = mutableListOf<Long>()
        val softDeletedTopics = mutableListOf<String>()
        var findPendingByStudyIdCalls = 0
        var findLatestPendingByStudyIdsCalls = 0
        override suspend fun save(entity: QuestionEntity): QuestionEntity = entity
        override suspend fun findQuestionById(id: Long): QuestionEntity? = null
        override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = null
        override suspend fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override suspend fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> {
            findPendingByStudyIdCalls += 1
            return PageImpl(pendingRows.filter { it.studyId == studyId }.sortedByDescending { it.createdAt }.take(1), pageable, 1)
        }
        override suspend fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> {
            findLatestPendingByStudyIdsCalls += 1
            return pendingRows
                .filter { it.studyId in studyIds }
                .groupBy { it.studyId }
                .values
                .mapNotNull { rows -> rows.maxWithOrNull(compareBy<QuestionEntity> { it.createdAt }.thenBy { it.id }) }
        }
        override suspend fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override suspend fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override suspend fun countPendingForStudy(studyId: Long): Long = pendingRows.count { it.studyId == studyId }.toLong()
        override suspend fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> =
            pendingRows
                .filter { it.studyId in studyIds }
                .groupingBy { it.studyId!! }
                .eachCount()
                .mapValues { it.value.toLong() }
        override suspend fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredById(id: Long): QuestionEntity? = null
        override suspend fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = emptyList()
        override suspend fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
        override suspend fun softDeleteByStudyId(studyId: Long, userId: Long, now: Instant): Int {
            softDeletedStudyIds += studyId
            return 0
        }
        override suspend fun softDeleteByUserIdAndTopic(userId: Long, topic: String, now: Instant): Int {
            softDeletedTopics += topic
            return 0
        }
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        val rows = mutableListOf<QuestionStatsEntity>()
        var findByIdCalls = 0
        var findAllByIdsCalls = 0
        override suspend fun save(entity: QuestionStatsEntity): QuestionStatsEntity = entity
        override suspend fun findById(id: Long): QuestionStatsEntity? {
            findByIdCalls += 1
            return rows.firstOrNull { it.questionId == id }
        }
        override suspend fun findAllByIds(ids: Collection<Long>): List<QuestionStatsEntity> {
            findAllByIdsCalls += 1
            return rows.filter { it.questionId in ids }
        }
        override suspend fun incrementView(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun incrementLike(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }

    private class FakeUserPort : UserPort {
        override suspend fun save(entity: UserEntity): UserEntity = entity
        override suspend fun findById(id: Long): UserEntity? = null
        override suspend fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = mutableListOf()
        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeQuestionSearchPort : QuestionSearchPort {
        val deletedStudyIds = mutableListOf<Long>()
        val deletedTopics = mutableListOf<String>()
        override suspend fun save(entity: QuestionSearchEntity): QuestionSearchEntity = entity
        override suspend fun deleteByQuestionId(questionId: Long): Long = 0
        override suspend fun deleteByStudyId(studyId: Long, userId: Long): Long {
            deletedStudyIds += studyId
            return 0
        }
        override suspend fun deleteByUserIdAndTopic(userId: Long, topic: String): Long {
            deletedTopics += topic
            return 0
        }
        override suspend fun searchPublic(query: String?, language: String, limit: Int, offset: Int): SearchResult = SearchResult(emptyList(), 0)
        override suspend fun findPublicByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? = null
        override suspend fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? = null
    }

    private class FakeQuestionSearchTranslator : QuestionSearchTranslationPort {
        override suspend fun translateSearchText(
            sourceLanguage: String,
            targetLanguage: String,
            topic: String,
            question: String,
            answer: String?,
            feedback: String?,
            explanation: String?,
        ): TranslatedQuestionSearchText = TranslatedQuestionSearchText(topic, question, answer, feedback, explanation)
    }

    private fun fakeSearchSyncManager(search: QuestionSearchPort = FakeQuestionSearchPort()) =
        QuestionSearchSyncManager(BuddyStudyProperties(), FakeQuestionPort(), FakeUserPort(), search, FakeQuestionSearchTranslator())
}
