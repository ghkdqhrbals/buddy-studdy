package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.localization.application.port.ContentTranslationRequestAppendPort
import com.buddystudy.backend.study.application.model.QueuedQuestionGeneration
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationRequestWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.ScheduledQuestionService
import com.buddystudy.backend.study.application.service.ScheduledQuestionWriteService
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.OptimisticLockingFailureException
import java.time.Instant

class ScheduledQuestionOptimisticRecoveryTest {
    @Test
    fun `a failed stale schedule save reloads the voice-renamed row before recording its retry`(): Unit = runBlocking {
        val now = Instant.parse("2026-09-03T02:00:00Z")
        val staleRoot = study(id = 11, topic = "Spring", parentId = null, now = now, version = 4)
        val topic = study(id = 12, topic = "Transactions", parentId = 11, now = now, version = 2)
        val renamedRoot = study(id = 11, topic = "Spring Backend", parentId = null, now = now, version = 5)
            .also { it.difficultyLevel = 7 }
        val studies = OptimisticConflictStudies(staleRoot, topic, renamedRoot)
        val writer = ScheduledQuestionWriteService(
            studies = studies,
            questions = Mockito.mock(QuestionPort::class.java),
            questionStats = Mockito.mock(QuestionStatsPort::class.java),
            questionEmbeddings = Mockito.mock(QuestionEmbeddingPort::class.java),
            questionCoverage = Mockito.mock(QuestionCoveragePort::class.java),
            questionKeys = Mockito.mock(OpenAIQuestionKeyProvider::class.java),
            notificationOutbox = Mockito.mock(RedisEventOutboxAppendPort::class.java),
            translationRequests = Mockito.mock(ContentTranslationRequestAppendPort::class.java),
        )
        val scheduler = ScheduledQuestionService(
            properties = BuddyStudyProperties(
                scheduler = BuddyStudyProperties.Scheduler(enabled = true, batchSize = 10),
            ),
            studies = studies,
            questions = EmptyQuestionStatuses,
            sagas = EmptyActiveGenerations,
            requestWriter = VersionMutatingFailure,
            scheduleWriter = writer,
            publisher = Mockito.mock(PublishOutboxUseCase::class.java),
        )

        scheduler.runDueQuestions()

        assertThat(staleRoot.version).isEqualTo(5)
        assertThat(staleRoot.lastError).isNull()
        assertThat(studies.saved).containsExactly(renamedRoot)
        assertThat(renamedRoot.topic).isEqualTo("Spring Backend")
        assertThat(renamedRoot.difficultyLevel).isEqualTo(7)
        assertThat(renamedRoot.lastError).contains("simulated stale scheduler write")
    }

    private class OptimisticConflictStudies(
        private val staleRoot: StudyEntity,
        private val topic: StudyEntity,
        private val currentRoot: StudyEntity,
    ) : StudyPort by unsupportedPort() {
        private var claimed = false
        val saved = mutableListOf<StudyEntity>()

        override suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity> =
            if (claimed) emptyList() else listOf(staleRoot).also { claimed = true }

        override suspend fun findAllByUserId(userId: Long): List<StudyEntity> = listOf(staleRoot, topic)

        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? =
            currentRoot.takeIf { it.id == id && it.userId == userId }

        override suspend fun save(entity: StudyEntity): StudyEntity = entity.also(saved::add)
    }

    private object VersionMutatingFailure : QuestionGenerationRequestWriteUseCase by unsupportedPort() {
        override suspend fun enqueueScheduled(
            scheduleStudy: StudyEntity,
            topicStudy: StudyEntity,
            idempotencyKey: String,
            now: Instant,
        ): QueuedQuestionGeneration {
            // R2dbcEntityTemplate advances the mutable version before executing
            // the UPDATE that then reports the optimistic-lock conflict.
            scheduleStudy.version += 1
            throw OptimisticLockingFailureException("simulated stale scheduler write")
        }
    }

    private object EmptyQuestionStatuses : QuestionPort by unsupportedPort() {
        override suspend fun findLatestStatusesByStudyIds(studyIds: Collection<Long>): Map<Long, QuestionStatus> = emptyMap()
    }

    private object EmptyActiveGenerations : QuestionGenerationSagaPort by unsupportedPort() {
        override suspend fun findActiveTopicIdsByUserId(userId: Long, topicIds: Collection<Long>): Set<Long> = emptySet()
    }

    private fun study(id: Long, topic: String, parentId: Long?, now: Instant, version: Long) = StudyEntity(
        id = id,
        userId = 7,
        deviceId = "device-7",
        parentStudyId = parentId,
        topic = topic,
        enabled = true,
        activeForQuestions = parentId != null,
        difficultyLevel = 5,
        nextDueAt = now.minusSeconds(60),
        createdAt = now.minusSeconds(3_600),
        updatedAt = now.minusSeconds(3_600),
        version = version,
    )

    private companion object {
        inline fun <reified T> unsupportedPort(): T =
            java.lang.reflect.Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java),
            ) { _, method, _ -> error("Unexpected ${T::class.simpleName} call: ${method.name}") } as T
    }
}
