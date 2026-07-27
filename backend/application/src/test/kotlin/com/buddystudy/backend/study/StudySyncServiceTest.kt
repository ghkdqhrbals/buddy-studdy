package com.buddystudy.backend.study

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicCommand
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.StudySyncService
import com.buddystudy.backend.test.EmptyContentLocalizationPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    private val service = StudySyncService(studies, questions, questionStats, EmptyContentLocalizationPort())
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
    fun `child studies keep their parent and sibling order`(): Unit = runBlocking {
        studies.rows += study(id = 11, topic = "Redis")

        val response = service.createStudyTopic(
            principal,
            parentStudyId = 11,
            CreateStudyTopicCommand(
                topic = "Streams",
                sortOrder = 3,
                difficultyLevel = 7,
            ),
        )

        assertThat(response.parentStudyId).isEqualTo(11)
        assertThat(response.sortOrder).isEqualTo(3)
        assertThat(response.difficultyLevel).isEqualTo(7)
        assertThat(response.enabled).isFalse()
        assertThat(response.nextDueAt).isNull()
        assertThat(studies.rows.last().parentStudyId).isEqualTo(11)
    }

    @Test
    fun `same normalized topic is rejected across the study tree`() {
        studies.rows += study(id = 11, topic = "Redis")
        studies.rows += study(id = 12, topic = "Kafka")

        runBlocking {
            service.createStudyTopic(
                principal,
                parentStudyId = 11,
                CreateStudyTopicCommand(topic = "Redis Streams"),
            )
        }

        assertThatThrownBy {
            runBlocking {
                service.createStudyTopic(
                    principal,
                    parentStudyId = 12,
                    CreateStudyTopicCommand(topic = "  redis   streams "),
                )
            }
        }.isInstanceOf(ApiException::class.java)
        assertThat(studies.rows.count { it.topic == "Redis Streams" }).isEqualTo(1)
    }

    @Test
    fun `child study rejects a parent owned by another user`() {
        studies.rows += study(id = 11, topic = "Redis").apply { userId = 99 }

        assertThatThrownBy {
            runBlocking {
                service.createStudyTopic(
                    principal,
                    parentStudyId = 11,
                    CreateStudyTopicCommand(topic = "Streams"),
                )
            }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `deleting study removes records in that study subtree without using topic matching`(): Unit = runBlocking {
        studies.rows += study(id = 8, topic = "Redis")

        service.deleteStudy(principal, studyId = 8)

        assertThat(questions.softDeletedSubtreeIds).containsExactly(8)
        assertThat(questions.softDeletedTopics).isEmpty()
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
        override suspend fun save(entity: StudyEntity): StudyEntity {
            if (entity.id == 0L) {
                entity.id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
                rows += entity
            }
            return entity
        }
        override suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long = rows.removeAll { it.id == id && it.userId == userId }.let { if (it) 1 else 0 }
        override suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = null
        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? = rows.firstOrNull { it.id == id && it.userId == userId }
        override suspend fun findByUserIdAndParentStudyIdAndTopic(
            userId: Long,
            parentStudyId: Long?,
            topic: String,
        ): StudyEntity? = rows.firstOrNull {
            it.userId == userId && it.parentStudyId == parentStudyId && it.topic == topic
        }
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
        val softDeletedSubtreeIds = mutableListOf<Long>()
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
        override suspend fun softDeleteByStudySubtree(rootStudyId: Long, userId: Long, now: Instant): Int {
            softDeletedSubtreeIds += rootStudyId
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

}
