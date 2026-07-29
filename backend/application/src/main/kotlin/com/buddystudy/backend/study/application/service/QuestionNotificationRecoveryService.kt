package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.RecoverNotificationCommandUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.QuestionLanguage
import org.springframework.stereotype.Service

@Service
class QuestionNotificationRecoveryService(
    private val questions: QuestionPort,
    private val studies: StudyPort,
    private val users: UserPort,
) : RecoverNotificationCommandUseCase {
    override suspend fun recover(eventId: String): NotificationRequestCommand? {
        val questionId = eventId
            .takeIf { it.startsWith(QUESTION_CREATED_PREFIX) }
            ?.removePrefix(QUESTION_CREATED_PREFIX)
            ?.toLongOrNull()
            ?: return null
        val question = questions.findQuestionById(questionId) ?: return null
        if (question.deletedAt != null || question.skippedAt != null) return null
        val userId = question.userId ?: return null
        val studyId = question.studyId ?: return null
        val study = studies.findByIdAndUserId(studyId, userId) ?: return null
        val appLanguage = QuestionLanguage.normalize(users.findById(userId)?.appLanguage)
        return question.toQuestionNotification(study, appLanguage).copy(eventId = eventId)
    }

    private companion object {
        const val QUESTION_CREATED_PREFIX = "question-created-"
    }
}
