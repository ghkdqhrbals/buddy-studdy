package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.stream.StreamRetryScheduledException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.GeneratedQuestionWithEmbedding
import com.buddystudy.backend.study.application.model.PreparedQuestionGeneration
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.openai.OpenAIRequestRetryPolicy
import com.buddystudy.backend.study.application.port.inbound.ProcessQuestionGenerationUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationExecutionWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.prompt.QuestionCoverageGuide
import com.buddystudy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.StudyRoom
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.springframework.dao.TransientDataAccessException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class QuestionGenerationProcessor(
    private val properties: BuddyStudyProperties,
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val users: UserPort,
    @param:Qualifier("openAIClient")
    private val openAI: OpenAIPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionPrompts: QuestionPromptProvider,
    private val questionDiversity: QuestionDiversityPolicy,
    private val questionSimilarity: QuestionSimilarityPolicy,
    private val writer: QuestionGenerationExecutionWriteUseCase,
    private val publisher: PublishOutboxUseCase,
) : ProcessQuestionGenerationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(event: QuestionGenerationRequestedEvent, streamKey: String) {
        val claimed = writer.claim(event, Instant.now(), streamKey) ?: return
        var providerPreparationCompleted = false
        var candidateRequests = 0
        var receivedCandidate = false
        val result = try {
            check(claimed.inbox.attempt <= MAX_ATTEMPTS) { "Question generation attempt budget was exhausted." }
            val saga = claimed.saga
            val user = checkNotNull(users.findById(saga.userId)) {
                "Question owner was not found."
            }
            val userStudies = studies.findAllByUserId(saga.userId)
            val rootStudy = userStudies.firstOrNull { it.id == saga.studyId }
                ?: error("Question root study was not found.")
            val topicStudy = userStudies.firstOrNull { it.id == saga.topicId }
                ?: error("Question topic study was not found.")
            check(StudyTreeSelector.rootFor(topicStudy, userStudies).id == rootStudy.id) {
                "Question topic does not belong to the requested root study."
            }
            val questionKey = questionKeys.resolveReservedQuestionGeneration(
                user,
                saga.quotaPeriodStartedAt,
                saga.correlationId,
            )
            val prepared = prepare(
                event = event,
                rootStudy = rootStudy,
                topicStudy = topicStudy,
                appLanguage = QuestionLanguage.normalize(user.appLanguage.databaseValue),
                questionKey = questionKey,
                candidateBudget = (MAX_ATTEMPTS - claimed.inbox.attempt + 1).coerceIn(1, MAX_ATTEMPTS),
                onCandidateRequest = { candidateRequests++ },
                onCandidateReceived = { receivedCandidate = true },
            )
            providerPreparationCompleted = true
            // Retry the separate rolled-back write transaction with the same
            // generated value, never its upstream paid provider operation.
            completePrepared(event, prepared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            if (!providerPreparationCompleted && !receivedCandidate && candidateRequests <= 1 && claimed.inbox.attempt < MAX_ATTEMPTS &&
                OpenAIRequestRetryPolicy.isRetryable(error)) {
                writer.retry(claimed.inbox, message, Instant.now())
                throw StreamRetryScheduledException(message, error)
            }
            val rollbackOutbox = writer.fail(
                event = event,
                errorCode = "QUESTION_GENERATION_FAILED",
                errorMessage = "질문을 생성하지 못했습니다.",
                now = Instant.now(),
            )
            writer.completeFailure(
                claim = claimed.inbox,
                errorCode = "QUESTION_GENERATION_FAILED",
                errorMessage = message,
                now = Instant.now(),
            )
            rollbackOutbox?.let { reference ->
                runCatching { publisher.publishNow(listOf(reference)) }
                    .onFailure {
                        log.warn(
                            "question_generation_rollback_immediate_publish_failed correlationId={} error={}",
                            event.correlationId,
                            it.message,
                        )
                    }
            }
            log.warn(
                "question_generation_failed correlationId={} attempts={} errorType={} error={}",
                event.correlationId,
                claimed.inbox.attempt,
                error.javaClass.name,
                message,
            )
            return
        }
        writer.succeed(claimed.inbox, Instant.now())
        runCatching { publisher.publishNow(result.outboxes) }
            .onFailure {
                log.warn(
                    "question_generated_immediate_publish_failed correlationId={} questionId={} error={}",
                    event.correlationId,
                    result.question.id,
                    it.message,
                )
            }
        log.info(
            "question_generation_completed correlationId={} questionId={} source={}",
            event.correlationId,
            result.question.id,
            event.source,
        )
    }

    private suspend fun completePrepared(
        event: QuestionGenerationRequestedEvent,
        prepared: PreparedQuestionGeneration,
    ): com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult {
        var retry = 0
        val originalQuestionID = prepared.question.id
        while (true) {
            try {
                return writer.complete(event, prepared, Instant.now())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The transaction may have committed before its response was
                // lost. Read the durable result before any retry or rollback.
                findCommittedWithRetry(event)?.let { return it }
                if (error !is TransientDataAccessException || retry >= MAX_WRITE_RETRIES) throw error
                retry++
                prepared.question.id = originalQuestionID
                log.warn("question_generation_write_retry operation=complete retry={} errorType={}",
                    retry, error.javaClass.simpleName)
                delay(WRITE_RETRY_DELAY_MILLIS * retry)
            }
        }
    }

    private suspend fun findCommittedWithRetry(
        event: QuestionGenerationRequestedEvent,
    ): com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult? {
        var retry = 0
        while (true) {
            try {
                return writer.findCommitted(event)
            } catch (error: TransientDataAccessException) {
                if (retry >= MAX_WRITE_RETRIES) throw error
                retry++
                log.warn("question_generation_write_retry operation=find_committed retry={} errorType={}",
                    retry, error.javaClass.simpleName)
                delay(WRITE_RETRY_DELAY_MILLIS * retry)
            }
        }
    }

    private suspend fun prepare(
        event: QuestionGenerationRequestedEvent,
        rootStudy: StudyEntity,
        topicStudy: StudyEntity,
        appLanguage: String,
        questionKey: OpenAIQuestionKey,
        candidateBudget: Int,
        onCandidateRequest: () -> Unit,
        onCandidateReceived: () -> Unit,
    ): PreparedQuestionGeneration = coroutineScope {
        val room = StudyRoom.of(
            topicStudy.toStudyRoomSchedule(
                appLanguage = appLanguage,
                questionStudyId = topicStudy.id,
                questionSettings = rootStudy,
            ),
            pendingCount = 0,
        )
        val recentQuestionsDeferred = async {
            recentQuestions(event.userId, topicStudy.id, topicStudy.topic, appLanguage)
        }
        val recentEmbeddingsDeferred = async {
            questionEmbeddings.findRecentByStudyIdAndTopic(
                topicStudy.id,
                topicStudy.topic,
                RECENT_EMBEDDING_LIMIT,
            )
        }
        val coverageDeferred = async {
            selectCoverage(questionKey.apiKey, topicStudy, rootStudy)
        }
        val coverage = coverageDeferred.await()
        val generated = generateDistinctQuestion(
            apiKey = questionKey.apiKey,
            model = room.openaiModel.ifBlank { properties.openai.model },
            topic = room.topic,
            level = room.difficultyLevel,
            language = room.appLanguage,
            customPrompt = room.customPrompt,
            studyId = topicStudy.id,
            userId = event.userId,
            recentQuestions = recentQuestionsDeferred.await(),
            recentEmbeddings = recentEmbeddingsDeferred.await(),
            coverageSelection = coverage,
            candidateBudget = candidateBudget,
            onCandidateRequest = onCandidateRequest,
            onCandidateReceived = onCandidateReceived,
        )
        val now = Instant.now()
        PreparedQuestionGeneration(
            question = room.createQuestion(
                generated.question,
                generated.hint,
                source = event.source.name.lowercase(),
                now = now,
            )
                .toQuestionEntity()
                .applyRubric(generated.generated.rubric)
                .applyCoverage(coverage),
            embedding = generated.embedding,
            coverage = coverage,
            questionKey = questionKey,
        )
    }

    private suspend fun recentQuestions(
        userId: Long,
        studyId: Long,
        topic: String,
        language: String,
    ): List<String> {
        val sameStudy = questions.findRecentQuestionTextsByStudyIdAndTopicAndLanguage(
            studyId,
            topic,
            language,
            org.springframework.data.domain.PageRequest.of(0, 30),
        )
        val sameTopic = questions.findRecentQuestionTextsByUserIdAndTopicAndLanguage(
            userId,
            topic,
            language,
            org.springframework.data.domain.PageRequest.of(0, 30),
        )
        return (sameStudy + sameTopic)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.normalizedGenerationQuestionKey() }
            .take(40)
    }

    private suspend fun generateDistinctQuestion(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        language: String,
        customPrompt: String,
        studyId: Long,
        userId: Long,
        recentQuestions: List<String>,
        recentEmbeddings: List<QuestionEmbeddingCandidate>,
        coverageSelection: QuestionCoverageSelection?,
        candidateBudget: Int,
        onCandidateRequest: () -> Unit,
        onCandidateReceived: () -> Unit,
    ): GeneratedQuestionWithEmbedding {
        val maxAttempts = minOf(properties.openai.questionSimilarityMaxAttempts.coerceIn(1, MAX_ATTEMPTS), candidateBudget)
        val rejectedQuestions = mutableListOf<String>()
        repeat(maxAttempts) { attempt ->
            val history = recentQuestions + rejectedQuestions
            val prompt = questionPrompts.buildQuestionGenerationPrompt(
                topic = topic,
                level = level,
                language = language,
                customPrompt = customPrompt,
                recentQuestions = history,
                diversity = questionDiversity.choose(topic, studyId, userId, history),
                coverage = coverageSelection?.let {
                    QuestionCoverageGuide(it.conceptName, it.angleName, it.conceptPath)
                },
            )
            onCandidateRequest()
            val generated = openAI.generateQuestion(apiKey, model, prompt)
            onCandidateReceived()
            if (!QuestionLanguage.matches(generated.question, language)) {
                rejectedQuestions += generated.question
                if (attempt == maxAttempts - 1) {
                    error("Generated question did not match the requested language.")
                }
                return@repeat
            }
            val embedding = embedGeneratedQuestion(apiKey, generated.question)
            if (
                questionSimilarity.findSimilar(
                    embedding,
                    recentEmbeddings,
                    properties.openai.questionSimilarityThreshold,
                ) == null
            ) {
                return GeneratedQuestionWithEmbedding(generated, embedding)
            }
            rejectedQuestions += generated.question
            if (attempt == maxAttempts - 1) {
                error("Generated question is too similar to a previous question.")
            }
        }
        error("Question generation attempts were exhausted.")
    }

    private suspend fun embedGeneratedQuestion(apiKey: String, question: String): List<Float> {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return openAI.embedText(apiKey, question)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (attempt == MAX_ATTEMPTS - 1 || !OpenAIRequestRetryPolicy.isRetryable(error)) throw error
                delay(WRITE_RETRY_DELAY_MILLIS * (attempt + 1))
            }
        }
        error("Embedding retry budget exhausted.")
    }

    private suspend fun selectCoverage(
        apiKey: String,
        topicStudy: StudyEntity,
        rootStudy: StudyEntity,
    ): QuestionCoverageSelection? {
        questionCoverage.selectNext(topicStudy.id)?.let { return it }
        val generatedBlueprint = try {
            openAI.generateQuestionCoverageBlueprint(
                apiKey = apiKey,
                model = rootStudy.openaiModel.ifBlank { properties.openai.model },
                topic = topicStudy.topic,
                level = topicStudy.difficultyLevel,
                customPrompt = rootStudy.customPrompt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // This optional guide must not retry the whole reserved question job. The actual
            // question and its rubric still use the regular generation and failure path.
            log.warn("question_coverage_fallback studyId={} errorType={}", topicStudy.id, error.javaClass.simpleName)
            emptyList()
        }
        val blueprint = generatedBlueprint.map { concept ->
            QuestionCoveragePort.CoverageConceptBlueprint(
                key = concept.key,
                name = concept.name,
                angles = concept.angles.map { QuestionCoveragePort.CoverageAngleBlueprint(it.key, it.name) },
                children = concept.children.toCoverageBlueprints(),
            )
        }
        questionCoverage.ensureCoverage(
            topicStudy.id,
            topicStudy.topic,
            blueprint.ifEmpty { defaultCoverageBlueprint(topicStudy.topic) },
        )
        return questionCoverage.selectNext(topicStudy.id)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_WRITE_RETRIES = 2
        const val WRITE_RETRY_DELAY_MILLIS = 50L
        const val RECENT_EMBEDDING_LIMIT = 200
    }
}

private fun String.normalizedGenerationQuestionKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
