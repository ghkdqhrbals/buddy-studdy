package com.buddystuddy.backend.study.adapter.inbound.scheduler

import com.buddystuddy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QuestionScheduler(
    private val runQuestionSchedule: RunQuestionScheduleUseCase,
) {
    @Scheduled(fixedDelayString = "\${buddystuddy.scheduler.poll-ms:30000}")
    fun runScheduled() {
        runQuestionSchedule.runDueQuestions()
    }
}
