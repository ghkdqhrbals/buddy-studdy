package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.stream.StreamRetryScheduledException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.*
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.openai.OpenAIRequestFailure
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationExecutionWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.*
import com.buddystudy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.backend.study.application.service.QuestionGenerationProcessor
import com.buddystudy.backend.study.application.service.QuestionSimilarityPolicy
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.CannotAcquireLockException
import java.io.IOException
import java.time.Instant

class QuestionGenerationProcessorTest {
    @Test
    fun `durable retries share a maximum of three logical question candidates`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.ai.questionFailure = IOException("temporary")
        for (attempt in 1..2) {
            fixture.claim = fixture.claim.copy(inbox = fixture.claim.inbox.copy(attempt = attempt))
            assertThatThrownBy { runBlocking { fixture.processor.process(fixture.event, "study.question.request.v1") } }
                .isInstanceOf(StreamRetryScheduledException::class.java)
        }
        fixture.claim = fixture.claim.copy(inbox = fixture.claim.inbox.copy(attempt = 3))
        fixture.ai.questionFailure = null
        fixture.ai.generatedText = "Explain the consistency tradeoffs in a distributed system."
        fixture.processor.process(fixture.event, "study.question.request.v1")
        assertThat(fixture.ai.generateCalls).isEqualTo(3)
        assertThat(fixture.retries).isEqualTo(2)
        assertThat(fixture.failed).isEqualTo(1)
    }

    @Test
    fun `embedding failures retry existing generated text without generating another question`() = runBlocking<Unit> {
        for (failures in listOf(2, 10)) {
            val fixture = Fixture()
            fixture.ai.embeddingFailures = failures
            fixture.processor.process(fixture.event, "study.question.request.v1")
            assertThat(fixture.ai.generateCalls).isEqualTo(1)
            assertThat(fixture.ai.embeddingCalls).isEqualTo(3)
            assertThat(fixture.retries).isZero()
            assertThat(fixture.failed).isEqualTo(if (failures == 2) 0 else 1)
        }
    }

    @Test
    fun `transient database failure before provider work can release its durable claim for retry`() {
        val fixture = Fixture()
        fixture.userFailure = CannotAcquireLockException("temporary read failure")
        assertThatThrownBy { runBlocking { fixture.processor.process(fixture.event, "study.question.request.v1") } }
            .isInstanceOf(StreamRetryScheduledException::class.java)
        assertThat(fixture.ai.generateCalls).isZero()
        assertThat(fixture.retries).isEqualTo(1)
        assertThat(fixture.failed).isZero()
    }
    @Test
    fun `rolled back completion retries the same question without paying for generation again`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.writeFailures = 2
        fixture.processor.process(fixture.event, "study.question.request.v1")
        assertThat(fixture.ai.generateCalls).isEqualTo(1)
        assertThat(fixture.writeAttempts).isEqualTo(3)
        assertThat(fixture.completed).hasSize(1)
        assertThat(fixture.retries).isZero()
        assertThat(fixture.failed).isZero()
        assertThat(fixture.succeeded).isEqualTo(1)
    }

    @Test
    fun `unknown error after commit recovers durable result without retrying generation or rolling back`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.commitReplyFailure = IllegalStateException("commit acknowledgement lost")
        fixture.processor.process(fixture.event, "study.question.request.v1")
        assertThat(fixture.ai.generateCalls).isEqualTo(1)
        assertThat(fixture.writeAttempts).isEqualTo(1)
        assertThat(fixture.committedReads).isEqualTo(1)
        assertThat(fixture.completed).hasSize(1)
        assertThat(fixture.retries).isZero()
        assertThat(fixture.failed).isZero()
        assertThat(fixture.completedFailures).isZero()
        assertThat(fixture.succeeded).isEqualTo(1)
    }

    @Test
    fun `transient recovery reads preserve rolled back write retries and committed results`() = runBlocking<Unit> {
        for (committed in listOf(false, true)) {
            val fixture = Fixture()
            fixture.committedReadFailures = 2
            if (committed) fixture.commitReplyFailure = IllegalStateException("commit acknowledgement lost")
            else fixture.writeFailures = 2

            fixture.processor.process(fixture.event, "study.question.request.v1")

            assertThat(fixture.ai.generateCalls).isEqualTo(1)
            assertThat(fixture.writeAttempts).isEqualTo(if (committed) 1 else 3)
            assertThat(fixture.committedReads).isEqualTo(if (committed) 3 else 4)
            assertThat(fixture.completed).hasSize(1)
            assertThat(fixture.retries).isZero()
            assertThat(fixture.failed).isZero()
            assertThat(fixture.completedFailures).isZero()
            assertThat(fixture.succeeded).isEqualTo(1)
        }
    }

    @Test
    fun `exhausted completion writes use existing rollback instead of replaying provider`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.writeFailures = 10
        fixture.processor.process(fixture.event, "study.question.request.v1")
        assertThat(fixture.ai.generateCalls).isEqualTo(1)
        assertThat(fixture.writeAttempts).isEqualTo(3)
        assertThat(fixture.retries).isZero()
        assertThat(fixture.failed).isEqualTo(1)
        assertThat(fixture.completedFailures).isEqualTo(1)
        assertThat(fixture.completed).isEmpty()
    }

    @Test
    fun `terminal provider and malformed response failures do not replay paid work`() = runBlocking<Unit> {
        for (failure in listOf(OpenAIRequestFailure(false, IOException("provider detail")),
            IllegalArgumentException("invalid generated result"))) {
            val fixture = Fixture()
            fixture.ai.questionFailure = failure
            fixture.processor.process(fixture.event, "study.question.request.v1")
            assertThat(fixture.ai.generateCalls).isEqualTo(1)
            assertThat(fixture.retries).isZero()
            assertThat(fixture.failed).isEqualTo(1)
            assertThat(fixture.completedFailures).isEqualTo(1)
        }
    }

    @Test
    fun `transient provider retries remain bounded by the durable attempt count`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.claim = fixture.claim.copy(inbox = fixture.claim.inbox.copy(attempt = 3))
        fixture.ai.questionFailure = OpenAIRequestFailure(true, IOException("temporarily unavailable"))
        fixture.processor.process(fixture.event, "study.question.request.v1")
        assertThat(fixture.retries).isZero()
        assertThat(fixture.failed).isEqualTo(1)
        assertThat(fixture.ai.generateCalls).isEqualTo(1)
    }
    @Test
    fun `optional coverage failure still saves one real question with the existing reservation`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.ai.coverageFailure = IOException("Request failed")

        fixture.processor.process(fixture.event, "study.question.request.v1")

        val prepared = fixture.completed.single()
        assertThat(prepared.question.question).isEqualTo("서비스 사이의 데이터 일관성을 어떻게 보장하나요?")
        assertThat(prepared.embedding).containsExactly(0f, 1f)
        assertThat(prepared.questionKey.quotaReservation?.reservationKey).isEqualTo(fixture.event.correlationId)
        assertThat(prepared.coverage?.angleKey).isEqualTo("definition")
        assertThat(fixture.blueprint.single().name).isEqualTo("MSA")
        assertThat(fixture.blueprint.single().angles.map { it.key })
            .containsExactly("definition", "trade_off", "failure_mode", "debugging")
        assertThat(fixture.ai.generateCalls).isEqualTo(1)
        assertThat(fixture.retries).isZero()
        assertThat(fixture.succeeded).isEqualTo(1)
    }

    @Test
    fun `actual question failure still retries without saving a fabricated question`() {
        val fixture = Fixture()
        fixture.ai.coverageFailure = IOException("optional planning failed")
        fixture.ai.questionFailure = IOException("question provider failed")

        assertThatThrownBy { runBlocking { fixture.processor.process(fixture.event, "study.question.request.v1") } }
            .isInstanceOf(StreamRetryScheduledException::class.java)
        assertThat(fixture.completed).isEmpty()
        assertThat(fixture.retries).isEqualTo(1)
        assertThat(fixture.succeeded).isZero()
    }

    @Test
    fun `worker cancellation does not turn into fallback generation or a scheduled failure`() {
        val fixture = Fixture()
        fixture.ai.coverageFailure = CancellationException("worker cancelled")

        assertThatThrownBy { runBlocking { fixture.processor.process(fixture.event, "study.question.request.v1") } }
            .isInstanceOf(CancellationException::class.java)
        assertThat(fixture.ai.generateCalls).isZero()
        assertThat(fixture.blueprint).isEmpty()
        assertThat(fixture.completed).isEmpty()
        assertThat(fixture.retries).isZero()
    }

    private class Fixture {
        val now = Instant.parse("2026-09-10T20:53:22Z")
        val event = QuestionGenerationRequestedEvent(
            eventId = "event-1", correlationId = "reserved-question-1", userId = 7,
            studyId = 86, topicId = 86, source = QuestionGenerationSource.MANUAL, occurredAt = now,
        )
        var claim = ClaimedQuestionGeneration(
            saga = QuestionGenerationSaga(
                correlationId = event.correlationId, userId = 7, studyId = 86, topicId = 86, questionId = null,
                source = event.source, status = QuestionGenerationStatus.GENERATING,
                currentStep = QuestionGenerationStep.GENERATING, idempotencyKey = "manual:1",
                quotaPeriodStartedAt = now, quotaRefundedAt = null, failedStep = null,
                errorCode = null, errorMessage = null, createdAt = now, updatedAt = now, completedAt = null,
            ),
            inbox = StreamInboxClaim(event.eventId, "question-generation", "claim-1", 1),
        )
        val ai = FailingOpenAI()
        val completed = mutableListOf<PreparedQuestionGeneration>()
        var blueprint = emptyList<QuestionCoveragePort.CoverageConceptBlueprint>()
        var retries = 0
        var succeeded = 0
        var writeFailures = 0
        var writeAttempts = 0
        var committedReads = 0
        var committedReadFailures = 0
        var committedResult: QuestionWriteResult? = null
        var commitReplyFailure: Exception? = null
        var failed = 0
        var completedFailures = 0
        var userFailure: Exception? = null
        val properties = BuddyStudyProperties().apply { openai.userContentApiKey = "test-key" }
        val user = UserEntity(id = 7)
        val study = StudyEntity(id = 86, userId = 7, topic = "MSA", difficultyLevel = 8)
        val coverage = Mockito.mock(QuestionCoveragePort::class.java) { invocation ->
            when (invocation.method.name) {
                "ensureCoverage" -> {
                    blueprint = invocation.getArgument(2)
                    Unit
                }
                "selectNext" -> blueprint.firstOrNull()?.let { concept ->
                    QuestionCoverageSelection(
                        conceptId = 1, coverageId = 1, conceptKey = concept.key, conceptName = concept.name,
                        angleKey = concept.angles.first().key, angleName = concept.angles.first().name,
                    )
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val writer = Mockito.mock(QuestionGenerationExecutionWriteUseCase::class.java) { invocation ->
            when (invocation.method.name) {
                "claim" -> claim
                "complete" -> {
                    writeAttempts++
                    if (writeAttempts <= writeFailures) throw CannotAcquireLockException("rolled back")
                    val prepared: PreparedQuestionGeneration = invocation.getArgument(1)
                    completed += prepared
                    val result = QuestionWriteResult(prepared.question, emptyList())
                    committedResult = result
                    commitReplyFailure?.let { throw it }
                    result
                }
                "findCommitted" -> {
                    committedReads++
                    if (committedReads <= committedReadFailures) throw CannotAcquireLockException("temporary recovery read failure")
                    committedResult
                }
                "retry" -> { retries += 1; Unit }
                "succeed" -> { succeeded += 1; Unit }
                "fail" -> { failed += 1; null }
                "completeFailure" -> { completedFailures += 1; Unit }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val processor = QuestionGenerationProcessor(
            properties = properties,
            studies = Mockito.mock(StudyPort::class.java) { listOf(study) },
            questions = Mockito.mock(QuestionPort::class.java) { emptyList<String>() },
            users = Mockito.mock(UserPort::class.java) { userFailure?.let { throw it }; user },
            openAI = ai,
            questionEmbeddings = Mockito.mock(QuestionEmbeddingPort::class.java) { emptyList<QuestionEmbeddingCandidate>() },
            questionCoverage = coverage,
            questionKeys = OpenAIQuestionKeyProvider(UserContentOpenAIKeyProvider(properties), Mockito.mock(QuestionMembershipPort::class.java)),
            questionPrompts = QuestionPromptProvider(), questionDiversity = QuestionDiversityPolicy(),
            questionSimilarity = QuestionSimilarityPolicy(), writer = writer,
            publisher = Mockito.mock(PublishOutboxUseCase::class.java) { OutboxPublishSummary(0, 0, 0) },
        )
    }

    private class FailingOpenAI : OpenAIPort by Mockito.mock(OpenAIPort::class.java) {
        var coverageFailure: Exception? = null
        var questionFailure: Exception? = null
        var generateCalls = 0
        var embeddingCalls = 0
        var embeddingFailures = 0
        var generatedText = "서비스 사이의 데이터 일관성을 어떻게 보장하나요?"
        override suspend fun generateQuestionCoverageBlueprint(
            apiKey: String, model: String, topic: String, level: Int, customPrompt: String,
        ): List<OpenAIPort.QuestionCoverageConcept> {
            coverageFailure?.let { throw it }
            return emptyList()
        }
        override suspend fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion {
            generateCalls += 1
            questionFailure?.let { throw it }
            return GeneratedQuestion(generatedText, null)
        }
        override suspend fun embedText(apiKey: String, text: String): List<Float> {
            embeddingCalls++
            if (embeddingCalls <= embeddingFailures) throw IOException("temporary embedding failure")
            return listOf(0f, 1f)
        }
    }
}
