package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class QuestionScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val questionScheduleJob: QuestionScheduleJob,
) {
    @Scheduled(fixedDelayString = "\${buddystudy.scheduler.poll-ms:30000}")
    suspend fun runScheduled() {
        jobs.execute(questionScheduleJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class QuestionScheduleJob(
    private val runQuestionSchedule: RunQuestionScheduleUseCase,
) : ManagedJob {
    override val name: String = "question-schedule"

    override suspend fun run(): String {
        runQuestionSchedule.runDueQuestions()
        return "dueQuestionsProcessed=true"
    }
}
