package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QuestionScheduler(
    private val runQuestionSchedule: RunQuestionScheduleUseCase,
) {
    @Scheduled(fixedDelayString = "\${buddystudy.scheduler.poll-ms:30000}")
    fun runScheduled() {
        runQuestionSchedule.runDueQuestions()
    }
}
