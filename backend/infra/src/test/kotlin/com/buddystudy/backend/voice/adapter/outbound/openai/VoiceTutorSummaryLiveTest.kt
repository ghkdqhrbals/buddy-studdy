package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorGeneratedResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Explicitly paid, opt-in, one-call evaluation of the production summary HTTP
 * adapter. Its eight synthetic turns and expectations are frozen before any
 * provider output is observed. No real user data, database, application API,
 * microphone, audio, quota, HTTP fake, alternate model, or automatic retry.
 * Logs and assertion failures contain fixed reason codes and counts only.
 */
@EnabledIfEnvironmentVariable(named = "BUDDYSTUDY_LIVE_VOICE_SUMMARY", matches = "1")
class VoiceTutorSummaryLiveTest {
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SAME_THREAD)
    fun `one production GPT summary extracts the frozen Korean lesson with source evidence`() = runBlocking<Unit> {
        val startedAt = System.nanoTime()
        var summaryCalls = 0
        try {
            withTimeout(170_000) {
                // Credentials are accessed only inside the explicitly enabled
                // test. Never copy them into diagnostics or assertion messages.
                val key = System.getenv("OPENAI_API_KEY_USER")?.takeIf { it.isNotBlank() }
                    ?: fail(Failure.MISSING_KEY)
                val properties = BuddyStudyProperties().apply { openai.userContentApiKey = key }
                verify(properties.voiceTutor.summaryModel == "gpt-5.4", Failure.MODEL_CHANGED)
                verify(properties.voiceTutor.summaryPromptVersion == "voice-tutor-summary-v2", Failure.PROMPT_CHANGED)

                val session = syntheticSession()
                val turns = syntheticTranscript(session.id)
                verify(turns.size == 8, Failure.FIXTURE_CHANGED)
                var snapshotReads = 0
                val studyContext = object : VoiceTutorStudyContextPort {
                    override suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot> =
                        fail(Failure.UNEXPECTED_SNAPSHOT_WRITE)

                    override suspend fun remember(
                        userId: Long,
                        sessionId: String,
                        studyIds: List<Long>,
                    ): List<VoiceTutorStudySnapshot> = fail(Failure.UNEXPECTED_SNAPSHOT_WRITE)

                    override suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> {
                        verify(userId == SYNTHETIC_USER_ID && sessionId == SESSION_ID, Failure.SNAPSHOT_SCOPE)
                        snapshotReads += 1
                        verify(snapshotReads == 1, Failure.EXTRA_SNAPSHOT_READ)
                        return listOf(VoiceTutorStudySnapshot(43, 42, "Redis 캐시", 4))
                    }
                }
                val adapter = OpenAIVoiceTutorSummaryAdapter(
                    UserContentOpenAIKeyProvider(properties), properties, studyContext,
                )

                // The only summarize invocation in this test. The public
                // production constructor creates its real HTTP client.
                summaryCalls += 1
                val result = adapter.summarize(session, turns)
                verify(summaryCalls == 1 && snapshotReads == 1, Failure.CALL_BOUND)
                verifyResult(result)

                val exchanges = result.explorations.flatMap { it.exchanges }
                println(
                    "voice_summary_live status=PASS summary_calls=$summaryCalls transcript_turns=8 " +
                        "snapshot_reads=$snapshotReads explorations=${result.explorations.size} " +
                        "exchanges=${exchanges.size} unanswered_stored=${exchanges.any { it.questionTurnId == 8L }} " +
                        "elapsed_ms=${elapsedMilliseconds(startedAt)} real_app_writes=0 microphone=false app_quota=false",
                )
            }
        } catch (_: TimeoutCancellationException) {
            reportFailure(Failure.TIMEOUT, summaryCalls, startedAt)
            fail(Failure.TIMEOUT)
        } catch (_: CancellationException) {
            reportFailure(Failure.CANCELLED, summaryCalls, startedAt)
            // Preserve cancellation, without retaining a provider-supplied
            // message or cause in the JUnit report.
            throw CancellationException("Live voice summary verification cancelled.")
        } catch (error: VerificationFailure) {
            reportFailure(error.code, summaryCalls, startedAt)
            throw error
        } catch (_: Exception) {
            reportFailure(Failure.PROVIDER_FAILED, summaryCalls, startedAt)
            // No original exception, response, transcript, or credential enters
            // either the assertion detail or its cause chain.
            fail(Failure.PROVIDER_FAILED)
        }
    }

    private fun verifyResult(result: VoiceTutorGeneratedResult) {
        verify(result.summaryMarkdown.isNotBlank(), Failure.EMPTY_SUMMARY)
        verify(result.model == "gpt-5.4", Failure.MODEL_CHANGED)
        verify(result.promptVersion == "voice-tutor-summary-v2", Failure.PROMPT_CHANGED)
        val located = result.explorations.flatMap { topic -> topic.exchanges.map { topic to it } }
        val exchanges = located.map { it.second }
        verify(exchanges.none { it.questionTurnId == 1L }, Failure.READINESS_AS_LEARNING)
        verify(exchanges.all { it.questionTurnId in setOf(3L, 6L, 8L) }, Failure.UNSUPPORTED_QUESTION)
        verify(exchanges.all { it.score == null || it.questionTurnId == 3L }, Failure.UNSUPPORTED_SCORE)

        val (savedTopic, tutorQuestion) = located.singleOrNull { it.second.questionTurnId == 3L }
            ?: fail(Failure.MISSING_TUTOR_QUESTION)
        verify(savedTopic.studyId == 43L && savedTopic.difficulty == 4, Failure.CHILD_TOPIC_CONTEXT)
        verify(tutorQuestion.kind == VoiceTutorExchangeKind.TUTOR_QUESTION, Failure.TUTOR_QUESTION_KIND)
        verify(tutorQuestion.question.isNotBlank() && tutorQuestion.answer.isNotBlank(), Failure.MISSING_TUTOR_CONTENT)
        verify(tutorQuestion.answerTurnIds == listOf(4L), Failure.TUTOR_ANSWER_EVIDENCE)
        verify(tutorQuestion.feedbackTurnIds == listOf(5L), Failure.TUTOR_FEEDBACK_EVIDENCE)
        verify(tutorQuestion.score == 85, Failure.SPOKEN_SCORE)
        verify(tutorQuestion.strengths.isNotEmpty() && tutorQuestion.improvements.isNotEmpty(), Failure.MISSING_FEEDBACK)

        val learnerQuestion = exchanges.singleOrNull { it.questionTurnId == 6L }
            ?: fail(Failure.MISSING_LEARNER_QUESTION)
        verify(learnerQuestion.kind == VoiceTutorExchangeKind.LEARNER_QUESTION, Failure.LEARNER_QUESTION_KIND)
        verify(learnerQuestion.question.isNotBlank() && learnerQuestion.answer.isNotBlank(), Failure.MISSING_LEARNER_CONTENT)
        verify(learnerQuestion.answerTurnIds == listOf(7L), Failure.LEARNER_ANSWER_EVIDENCE)
        verify(
            learnerQuestion.score == null && learnerQuestion.feedbackTurnIds.isEmpty() &&
                learnerQuestion.strengths.isEmpty() && learnerQuestion.improvements.isEmpty(),
            Failure.LEARNER_QUESTION_GRADED,
        )

        val unanswered = exchanges.filter { it.questionTurnId == 8L }
        // The production prompt permits, but does not require, retaining an
        // unanswered question. Its omission is not an extraction failure.
        verify(unanswered.size <= 1, Failure.DUPLICATE_UNANSWERED)
        unanswered.forEach { exchange ->
            verify(
                exchange.kind == VoiceTutorExchangeKind.TUTOR_QUESTION && exchange.answer.isEmpty() &&
                    exchange.answerTurnIds.isEmpty() && exchange.score == null &&
                    exchange.feedbackTurnIds.isEmpty() && exchange.strengths.isEmpty() && exchange.improvements.isEmpty(),
                Failure.INVENTED_UNANSWERED_CONTENT,
            )
        }
    }

    private fun syntheticSession() = VoiceTutorSession(
        id = SESSION_ID, userId = SYNTHETIC_USER_ID, studyId = 42,
        idempotencyKey = "synthetic-live-summary-idempotency", providerSessionId = null,
        status = VoiceTutorSessionStatus.COMPLETED, resultStatus = VoiceTutorResultStatus.PROCESSING,
        language = "ko", model = "synthetic-realtime", voice = "synthetic-voice", topic = "Redis", difficulty = 3,
        periodStartedAt = FIXTURE_TIME.minusSeconds(60), periodEndsAt = FIXTURE_TIME.plusSeconds(3_600),
        reservedSeconds = 0, chargedSeconds = 0, maxSessionSeconds = 60,
        hardEndsAt = FIXTURE_TIME.plusSeconds(60), connectedAt = FIXTURE_TIME,
        relayHeartbeatAt = FIXTURE_TIME.plusSeconds(8), acceptedAudioBytes = 0,
        endedAt = FIXTURE_TIME.plusSeconds(8), finalizedAt = FIXTURE_TIME.plusSeconds(8),
        endReason = "SYNTHETIC_FIXTURE", failureCode = null, failureMessage = null,
        createdAt = FIXTURE_TIME, updatedAt = FIXTURE_TIME.plusSeconds(8),
    )

    private fun syntheticTranscript(sessionId: String): List<VoiceTutorTranscriptTurn> {
        val content = listOf(
            VoiceTutorTranscriptRole.TUTOR to "안녕하세요, 저는 AI 선생님입니다. 오늘 학습을 시작할 준비가 됐나요?",
            VoiceTutorTranscriptRole.USER to "네.",
            VoiceTutorTranscriptRole.TUTOR to "학습을 시작할게요. 하위 주제 Redis 캐시에서 캐시에 TTL을 설정하는 이유는 무엇인가요?",
            VoiceTutorTranscriptRole.USER to "오래된 데이터가 계속 사용되는 것을 막고 불필요한 캐시가 메모리를 계속 차지하지 않게 하려는 거예요.",
            VoiceTutorTranscriptRole.TUTOR to "이번 답변은 100점 만점에 85점입니다. 잘한 점은 데이터의 신선도와 메모리 관리를 함께 설명한 것이고, 보완할 점은 TTL이 정한 만료 시점과 실제 메모리 삭제 시점이 다를 수 있다는 점을 구분하는 것입니다.",
            VoiceTutorTranscriptRole.USER to "TTL이 끝나면 메모리에서 바로 삭제돼요?",
            VoiceTutorTranscriptRole.TUTOR to "바로 삭제된다고 단정할 수는 없어요. Lazy expiration은 키를 조회할 때 만료를 확인해 삭제하고, active expiration은 주기적으로 키를 검사해 만료된 키를 제거하므로 실제 삭제 시점은 다를 수 있어요.",
            VoiceTutorTranscriptRole.TUTOR to "Redis 캐시에서 LRU와 LFU의 차이는 무엇인가요?",
        )
        return content.mapIndexed { index, (role, text) ->
            val id = index.toLong() + 1
            VoiceTutorTranscriptTurn(
                id = id, sessionId = sessionId, providerItemId = "synthetic-summary-turn-$id",
                role = role, transcript = text, sequenceNumber = id, occurredAt = FIXTURE_TIME.plusSeconds(id),
                studyQuestionTurnId = if (id == 4L) 3L else null,
                studyAnswerTurnId = if (id == 5L) 4L else null,
                askedStudyQuestion = id == 6L,
                isStudyQuestion = id == 3L || id == 8L,
            )
        }
    }

    private fun verify(condition: Boolean, failure: Failure) {
        if (!condition) fail(failure)
    }

    private fun fail(failure: Failure): Nothing = throw VerificationFailure(failure)

    private fun reportFailure(failure: Failure, calls: Int, startedAt: Long) {
        println(
            "voice_summary_live status=FAIL reason=${failure.name} summary_calls=$calls " +
                "elapsed_ms=${elapsedMilliseconds(startedAt)} real_app_writes=0 microphone=false app_quota=false",
        )
    }

    private fun elapsedMilliseconds(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private class VerificationFailure(val code: Failure) :
        AssertionError("Live voice summary verification failed (${code.name}).")

    private enum class Failure {
        MISSING_KEY, MODEL_CHANGED, PROMPT_CHANGED, FIXTURE_CHANGED,
        UNEXPECTED_SNAPSHOT_WRITE, SNAPSHOT_SCOPE, EXTRA_SNAPSHOT_READ, CALL_BOUND,
        TIMEOUT, CANCELLED, PROVIDER_FAILED, EMPTY_SUMMARY, READINESS_AS_LEARNING,
        UNSUPPORTED_QUESTION, UNSUPPORTED_SCORE, MISSING_TUTOR_QUESTION, CHILD_TOPIC_CONTEXT,
        TUTOR_QUESTION_KIND, MISSING_TUTOR_CONTENT, TUTOR_ANSWER_EVIDENCE, TUTOR_FEEDBACK_EVIDENCE,
        SPOKEN_SCORE, MISSING_FEEDBACK, MISSING_LEARNER_QUESTION, LEARNER_QUESTION_KIND,
        MISSING_LEARNER_CONTENT, LEARNER_ANSWER_EVIDENCE, LEARNER_QUESTION_GRADED,
        DUPLICATE_UNANSWERED, INVENTED_UNANSWERED_CONTENT,
    }

    private companion object {
        const val SYNTHETIC_USER_ID = 9_000_000_000_002L
        const val SESSION_ID = "synthetic-live-summary"
        val FIXTURE_TIME: Instant = Instant.parse("2026-08-31T12:00:00Z")
    }
}
