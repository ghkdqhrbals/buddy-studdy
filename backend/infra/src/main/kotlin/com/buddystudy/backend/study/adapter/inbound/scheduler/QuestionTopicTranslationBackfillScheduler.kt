package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.study.application.port.inbound.BackfillQuestionTopicsUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "buddystudy.translation",
    name = ["backfill-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class QuestionTopicTranslationBackfillScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val backfillJob: QuestionTopicTranslationBackfillJob,
) {
    @Scheduled(fixedDelayString = "\${buddystudy.translation.backfill-poll-ms:60000}")
    suspend fun backfill() {
        jobs.execute(backfillJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class QuestionTopicTranslationBackfillJob(
    private val backfill: BackfillQuestionTopicsUseCase,
    private val properties: BuddyStudyProperties,
) : ManagedJob {
    override val name: String = "question-topic-translation-backfill"

    override suspend fun run(): String {
        val result = backfill.backfill(properties.translation.backfillBatchSize)
        return "candidates=${result.candidates},translated=${result.translated},failed=${result.failed}"
    }
}
