package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.crypto.KeyCipher
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.ProcessAnswerGradingUseCase
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class AnswerGradingService(
    private val properties: BuddyStudyProperties,
    private val questions: QuestionPort,
    private val studies: StudyPort,
    private val users: UserPort,
    private val cipher: KeyCipher,
    @param:Qualifier("openAIClient")
    private val openAI: OpenAIPort,
    private val writer: AnswerGradingWriteUseCase,
) : ProcessAnswerGradingUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(event: AnswerGradingRequestedEvent) {
        val question = questions.findByIdAndUserIdAndDeletedAtIsNull(event.recordId, event.userId)
            ?: return
        if (question.gradingRequestId != event.requestId ||
            question.gradingStatus == AnswerGradingStatus.COMPLETED.name ||
            question.gradingStatus == AnswerGradingStatus.FAILED.name
        ) {
            return
        }
        val answer = question.answer?.takeIf { it.isNotBlank() } ?: return fail(event, "저장된 답변을 찾을 수 없습니다.")
        val user = users.findById(event.userId)
        val study = question.studyId?.let { studies.findByIdAndUserId(it, event.userId) }
            ?: studies.findByUserIdAndTopic(event.userId, question.topic)
            ?: studies.findFirstByUserIdOrderByUpdatedAtDesc(event.userId)

        val grade = try {
            openAI.gradeWithRubric(
                apiKey = apiKeyFor(user),
                model = study?.openaiModel?.takeIf { it.isNotBlank() } ?: properties.openai.model,
                question = question.question,
                answer = answer,
                topic = question.topic,
                level = question.difficultyLevel,
                language = user?.appLanguage ?: "ko",
                rubric = question.gradingRubric(),
                onProgress = { stage ->
                    writer.transition(event, AnswerGradingStatus.valueOf(stage.name), Instant.now())
                },
            )
        } catch (error: Exception) {
            log.error(
                "answer_grading_failed eventId={} requestId={} recordId={} errorType={} error={}",
                event.eventId,
                event.requestId,
                event.recordId,
                error.javaClass.name,
                error.message,
                error,
            )
            fail(event, "채점을 완료하지 못했습니다. 다시 시도해 주세요.")
            return
        }

        writer.complete(event, grade, Instant.now())
    }

    private suspend fun apiKeyFor(user: com.buddystudy.account.domain.entity.UserEntity?): String =
        cipher.decrypt(user?.openaiApiKeyCipher)
            ?: properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.OPENAI_API_KEY_MISSING,
                "OpenAI API key is not configured.",
            )

    private suspend fun fail(event: AnswerGradingRequestedEvent, message: String) {
        writer.fail(event, message, Instant.now())
    }
}
