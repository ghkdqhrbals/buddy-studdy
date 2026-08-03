package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.study.application.model.QueuedQuestionGeneration
import com.buddystudy.backend.study.application.model.QuestionGenerationAcceptedResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationRequestWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import com.buddystudy.backend.study.application.port.inbound.ScheduledQuestionWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.model.GeneratedQuestionWithEmbedding
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.ScheduledQuestionService
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

class QuestionSchedulerTest {
    @Test
    fun `question scheduler runs through managed job executor`(): Unit = runBlocking {
        val useCase = RecordingScheduleUseCase()
        val job = QuestionScheduleJob(useCase)
        val jobs = RecordingManagedJobs()
        val adapter = QuestionScheduler(jobs, job)

        adapter.runScheduled()

        assertThat(jobs.executedJobNames).containsExactly("question-schedule")
        assertThat(jobs.triggerTypes).containsExactly(JobTriggerType.SCHEDULED)
        assertThat(useCase.calls).isEqualTo(1)
    }

    @Test
    fun `scheduled run only queues a saga and publishes its outbox`(): Unit = runBlocking {
        val now = Instant.parse("2026-07-27T00:00:00Z")
        val root = study(11, 7, "Backend", null, now).apply { activeForQuestions = false }
        val topic = study(12, 7, "Redis", 11, now).apply { activeForQuestions = true }
        val studies = RecordingStudies(listOf(root, topic))
        val requests = RecordingGenerationRequests()
        val publisher = RecordingPublisher()
        val scheduler = service(studies, requests, publisher)

        scheduler.runDueQuestions()

        assertThat(requests.topics.map(StudyEntity::id)).containsExactly(12)
        assertThat(requests.idempotencyKeys.single()).startsWith("scheduled:11:12:")
        assertThat(publisher.references).containsExactly(OutboxReference(OutboxType.DOMAIN_EVENT, 91))
        assertThat(studies.claimDueCalls).isEqualTo(2)
    }

    @ParameterizedTest
    @EnumSource(value = QuestionStatus::class, names = ["UNGRADED", "GRADING"])
    fun `scheduled run rotates past a topic whose latest question is in progress`(
        status: QuestionStatus,
    ): Unit = runBlocking {
        val now = Instant.parse("2026-07-27T00:00:00Z")
        val root = study(21, 8, "Backend", null, now).apply { activeForQuestions = false }
        val blocked = study(22, 8, "Redis", 21, now).apply { activeForQuestions = true }
        val available = study(23, 8, "Kafka", 21, now).apply { activeForQuestions = true }
        val studies = RecordingStudies(listOf(root, blocked, available))
        val requests = RecordingGenerationRequests()
        val questions = LatestQuestionStatusPort(mapOf(22L to status))
        val scheduler = service(studies, requests, RecordingPublisher(), questions)

        scheduler.runDueQuestions()

        assertThat(requests.topics.map(StudyEntity::id)).containsExactly(23)
    }

    @ParameterizedTest
    @EnumSource(value = QuestionStatus::class, names = ["FAILED", "GRADED", "SKIPPED"])
    fun `scheduled run can reuse a topic whose latest question is terminal`(
        status: QuestionStatus,
    ): Unit = runBlocking {
        val now = Instant.parse("2026-07-27T00:00:00Z")
        val root = study(31, 9, "Backend", null, now).apply { activeForQuestions = false }
        val topic = study(32, 9, "Redis", 31, now).apply { activeForQuestions = true }
        val studies = RecordingStudies(listOf(root, topic))
        val requests = RecordingGenerationRequests()
        val scheduler = service(
            studies,
            requests,
            RecordingPublisher(),
            LatestQuestionStatusPort(mapOf(topic.id to status)),
        )

        scheduler.runDueQuestions()

        assertThat(requests.topics.map(StudyEntity::id)).containsExactly(topic.id)
    }

    private fun service(
        studies: RecordingStudies,
        requests: RecordingGenerationRequests,
        publisher: RecordingPublisher,
        questions: QuestionPort = LatestQuestionStatusPort(emptyMap()),
    ) = ScheduledQuestionService(
        properties = BuddyStudyProperties(
            scheduler = BuddyStudyProperties.Scheduler(enabled = true, maxPendingPerStudy = 1, batchSize = 10),
        ),
        studies = studies,
        questions = questions,
        requestWriter = requests,
        scheduleWriter = RecordingScheduleWriter(),
        publisher = publisher,
    )

    private fun study(
        id: Long,
        userId: Long,
        topic: String,
        parentId: Long?,
        now: Instant,
    ) = StudyEntity(
        id = id,
        userId = userId,
        deviceId = "dev-$userId",
        parentStudyId = parentId,
        topic = topic,
        enabled = true,
        difficultyLevel = 5,
        intervalMinutes = 30,
        nextDueAt = now.minusSeconds(60),
        createdAt = now.minusSeconds(3600),
        updatedAt = now.minusSeconds(3600),
    )

    private class RecordingStudies(
        val rows: List<StudyEntity>,
    ) : StudyPort by unsupportedPort() {
        var claimDueCalls = 0

        override suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity> {
            claimDueCalls += 1
            return if (claimDueCalls == 1) rows.filter { it.parentStudyId == null }.take(limit) else emptyList()
        }

        override suspend fun findAllByUserId(userId: Long): List<StudyEntity> = rows.filter { it.userId == userId }
    }

    private class LatestQuestionStatusPort(
        private val statuses: Map<Long, QuestionStatus>,
    ) : QuestionPort by unsupportedPort() {
        override suspend fun findLatestStatusesByStudyIds(
            studyIds: Collection<Long>,
        ): Map<Long, QuestionStatus> = statuses.filterKeys(studyIds::contains)
    }

    private class RecordingGenerationRequests : QuestionGenerationRequestWriteUseCase {
        val topics = mutableListOf<StudyEntity>()
        val idempotencyKeys = mutableListOf<String>()

        override suspend fun enqueueManual(
            userId: Long,
            deviceId: String,
            studyId: Long,
            idempotencyKey: String,
            now: Instant,
        ): QueuedQuestionGeneration = error("Manual requests are not expected.")

        override suspend fun enqueueScheduled(
            scheduleStudy: StudyEntity,
            topicStudy: StudyEntity,
            idempotencyKey: String,
            now: Instant,
        ): QueuedQuestionGeneration {
            topics += topicStudy
            idempotencyKeys += idempotencyKey
            return QueuedQuestionGeneration(
                accepted = QuestionGenerationAcceptedResponse(
                    correlationId = "correlation-${topicStudy.id}",
                    studyId = scheduleStudy.id.toString(),
                    topicId = topicStudy.id.toString(),
                    status = QuestionGenerationStatus.QUEUED,
                    submittedAt = now,
                ),
                outboxes = listOf(OutboxReference(OutboxType.DOMAIN_EVENT, 91)),
            )
        }
    }

    private class RecordingScheduleWriter : ScheduledQuestionWriteUseCase {
        override suspend fun complete(
            scheduleStudy: StudyEntity,
            topicStudy: StudyEntity,
            generated: GeneratedQuestionWithEmbedding,
            coverage: QuestionCoverageSelection?,
            questionKey: OpenAIQuestionKey,
            appLanguage: String,
            now: Instant,
        ): QuestionWriteResult = error("The scheduler must not generate questions directly.")

        override suspend fun deferUntilNextInterval(study: StudyEntity, now: Instant) {
            study.nextDueAt = now.plusSeconds(study.intervalMinutes.toLong() * 60)
        }

        override suspend fun fail(
            study: StudyEntity,
            questionKey: OpenAIQuestionKey?,
            error: String,
            retryAt: Instant,
            now: Instant,
        ) {
            study.nextDueAt = retryAt
            study.lastError = error
        }
    }

    private class RecordingPublisher : PublishOutboxUseCase {
        val references = mutableListOf<OutboxReference>()

        override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
            this.references += references
            return OutboxPublishSummary(references.size, references.size, 0)
        }
    }

    private class RecordingScheduleUseCase : RunQuestionScheduleUseCase {
        var calls = 0
        override suspend fun runDueQuestions() {
            calls += 1
        }
    }

    private class RecordingManagedJobs : ManagedJobExecutionUseCase by unsupportedPort() {
        val executedJobNames = mutableListOf<String>()
        val triggerTypes = mutableListOf<JobTriggerType>()

        override suspend fun execute(
            job: ManagedJob,
            triggerType: JobTriggerType,
            retryOfRunId: Long?,
            createdBy: String,
        ): ScheduledJobRun {
            executedJobNames += job.name
            triggerTypes += triggerType
            job.run()
            return ScheduledJobRun(
                id = 1,
                jobName = job.name,
                triggerType = triggerType,
                status = JobRunStatus.SUCCESS,
                startedAt = Instant.EPOCH,
            )
        }
    }

    private companion object {
        inline fun <reified T> unsupportedPort(): T =
            java.lang.reflect.Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java),
            ) { _, method, _ ->
                error("Unexpected ${T::class.simpleName} call: ${method.name}")
            } as T
    }
}
