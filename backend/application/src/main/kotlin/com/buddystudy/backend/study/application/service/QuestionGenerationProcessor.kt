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
import kotlinx.coroutines.coroutineScope
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
        val result = try {
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
            val questionKey = questionKeys.resolveReservedQuestionGeneration(user, saga.quotaPeriodStartedAt)
            val prepared = prepare(
                event = event,
                rootStudy = rootStudy,
                topicStudy = topicStudy,
                appLanguage = QuestionLanguage.normalize(user.appLanguage.databaseValue),
                questionKey = questionKey,
            )
            writer.complete(event, prepared, Instant.now())
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            if (claimed.inbox.attempt < MAX_ATTEMPTS) {
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

    private suspend fun prepare(
        event: QuestionGenerationRequestedEvent,
        rootStudy: StudyEntity,
        topicStudy: StudyEntity,
        appLanguage: String,
        questionKey: OpenAIQuestionKey,
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
    ): GeneratedQuestionWithEmbedding {
        val maxAttempts = properties.openai.questionSimilarityMaxAttempts.coerceAtLeast(1)
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
            val generated = openAI.generateQuestion(apiKey, model, prompt)
            if (!QuestionLanguage.matches(generated.question, language)) {
                rejectedQuestions += generated.question
                if (attempt == maxAttempts - 1) {
                    error("Generated question did not match the requested language.")
                }
                return@repeat
            }
            val embedding = openAI.embedText(apiKey, generated.question)
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

    private suspend fun selectCoverage(
        apiKey: String,
        topicStudy: StudyEntity,
        rootStudy: StudyEntity,
    ): QuestionCoverageSelection? {
        questionCoverage.selectNext(topicStudy.id)?.let { return it }
        val blueprint = openAI.generateQuestionCoverageBlueprint(
            apiKey = apiKey,
            model = rootStudy.openaiModel.ifBlank { properties.openai.model },
            topic = topicStudy.topic,
            level = topicStudy.difficultyLevel,
            customPrompt = rootStudy.customPrompt,
        ).map { concept ->
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
        const val RECENT_EMBEDDING_LIMIT = 200
    }
}

private fun String.normalizedGenerationQuestionKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
