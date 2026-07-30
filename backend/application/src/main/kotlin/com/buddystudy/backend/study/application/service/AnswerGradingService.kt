package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.ProcessAnswerGradingUseCase
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class AnswerGradingService(
    private val properties: BuddyStudyProperties,
    private val questions: QuestionPort,
    private val studies: StudyPort,
    private val userContentKeys: UserContentOpenAIKeyProvider,
    @param:Qualifier("openAIClient")
    private val openAI: OpenAIPort,
    private val writer: AnswerGradingWriteUseCase,
    private val inbox: StreamInboxPort,
) : ProcessAnswerGradingUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(event: AnswerGradingRequestedEvent, streamKey: String) {
        val timeoutSeconds = properties.openai.gradingTimeoutSeconds.coerceIn(30, MAX_TIMEOUT_SECONDS)
        val claim = inbox.claim(
            eventId = event.eventId,
            consumerGroup = CONSUMER_GROUP,
            correlationId = event.requestId,
            leaseDuration = Duration.ofSeconds(timeoutSeconds + CLAIM_GRACE_SECONDS),
            now = Instant.now(),
            streamKey = streamKey,
        ) ?: return
        val question = questions.findByIdAndUserIdAndDeletedAtIsNull(event.recordId, event.userId)
            ?: return succeed(claim)
        if (question.gradingRequestId != event.requestId ||
            question.gradingStatus == AnswerGradingStatus.COMPLETED.name ||
            question.gradingStatus == AnswerGradingStatus.FAILED.name
        ) {
            return succeed(claim)
        }
        val answer = question.answer?.takeIf { it.isNotBlank() }
            ?: return fail(event, claim, "저장된 답변을 찾을 수 없습니다.")
        val study = question.studyId?.let { studies.findByIdAndUserId(it, event.userId) }
            ?: studies.findByUserIdAndTopic(event.userId, question.topic)
            ?: studies.findFirstByUserIdOrderByUpdatedAtDesc(event.userId)

        val grade = try {
            withTimeout(timeoutSeconds * 1_000) {
                openAI.gradeWithRubric(
                    apiKey = userContentKeys.requireApiKey(),
                    model = study?.openaiModel?.takeIf { it.isNotBlank() } ?: properties.openai.model,
                    question = question.question,
                    answer = answer,
                    topic = question.topic,
                    level = question.difficultyLevel,
                    language = event.responseLanguage,
                    rubric = question.gradingRubric(),
                    onProgress = { stage ->
                        writer.transition(event, AnswerGradingStatus.valueOf(stage.name), Instant.now())
                    },
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            log.error(
                "answer_grading_timed_out eventId={} requestId={} recordId={} timeoutSeconds={}",
                event.eventId,
                event.requestId,
                event.recordId,
                timeoutSeconds,
                timeout,
            )
            fail(event, claim, TIMEOUT_MESSAGE)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            fail(event, claim, "채점을 완료하지 못했습니다. 다시 시도해 주세요.")
            return
        }

        writer.complete(event, grade, Instant.now())
        succeed(claim)
    }

    private suspend fun fail(
        event: AnswerGradingRequestedEvent,
        claim: com.buddystudy.backend.study.application.model.StreamInboxClaim,
        message: String,
    ) {
        writer.fail(event, message, Instant.now())
        succeed(claim)
    }

    private suspend fun succeed(claim: com.buddystudy.backend.study.application.model.StreamInboxClaim) {
        check(inbox.markSucceeded(claim, Instant.now())) {
            "Answer grading Inbox claim was lost before completion."
        }
    }

    private companion object {
        const val CONSUMER_GROUP = "bs-backend-answer-grading"
        const val MAX_TIMEOUT_SECONDS = 270L
        const val CLAIM_GRACE_SECONDS = 15L
        const val TIMEOUT_MESSAGE = "채점 시간이 초과되었습니다. 다시 시도해 주세요."
    }
}
