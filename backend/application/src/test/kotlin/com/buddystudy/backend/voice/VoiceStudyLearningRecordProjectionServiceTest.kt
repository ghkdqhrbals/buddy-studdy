package com.buddystudy.backend.voice

import com.buddystudy.backend.study.application.service.VoiceStudyLearningRecordProjectionService
import com.buddystudy.backend.voice.application.port.outbound.VoiceStudyLearningRecordAppendPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceStudyLearningRecordProjectionCandidate
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Orchestration-only regression tests: no providers, database, stream, or Spring context. */
class VoiceStudyLearningRecordProjectionServiceTest {
    @Test
    fun `recovery clamps each requested candidate batch to one through ten`(): Unit = runBlocking {
        val candidates = (1..12).map { candidate("bounded-$it") }
        val cases = listOf(Int.MIN_VALUE to 1, 0 to 1, 1 to 1, 5 to 5, 10 to 10, 11 to 10, Int.MAX_VALUE to 10)

        for ((requested, expected) in cases) {
            val records = RecordingAppendPort(candidates)
            val completed = VoiceStudyLearningRecordProjectionService(records).reconcileLearningRecords(requested)

            assertThat(records.requestedLimits).describedAs("requested limit %s", requested).containsExactly(expected)
            assertThat(completed).describedAs("requested limit %s", requested).isEqualTo(expected)
            assertThat(records.attempted.map { it.sessionId })
                .containsExactlyElementsOf(candidates.take(expected).map { it.sessionId })
            assertThat(records.persisted).hasSize(expected)
        }
    }

    @Test
    fun `no completed candidates returns zero without append work`(): Unit = runBlocking {
        val records = RecordingAppendPort(emptyList())

        val completed = VoiceStudyLearningRecordProjectionService(records).reconcileLearningRecords(7)

        assertThat(completed).isZero()
        assertThat(records.requestedLimits).containsExactly(7)
        assertThat(records.attempted).isEmpty()
        assertThat(records.persisted).isEmpty()
    }

    @Test
    fun `one failed append preserves earlier success and still processes the next candidate`(): Unit = runBlocking {
        val candidates = listOf(candidate("before"), candidate("failed"), candidate("after"))
        val records = RecordingAppendPort(candidates).apply {
            failures["failed"] = IllegalStateException("Synthetic projection failure.")
        }

        val completed = VoiceStudyLearningRecordProjectionService(records).reconcileLearningRecords(10)

        assertThat(completed).isEqualTo(2)
        assertThat(records.requestedLimits).containsExactly(10)
        assertThat(records.attempted.map { it.sessionId }).containsExactly("before", "failed", "after")
        assertThat(records.persisted.map { it.sessionId }).containsExactly("before", "after")
        // Persistence owns each append transaction; this orchestrator must not wrap the batch in one.
        // Actual SQL rollback behavior is covered by the separate persistence-adapter tests.
        val serviceClass = VoiceStudyLearningRecordProjectionService::class.java
        assertThat(serviceClass.getAnnotation(Transactional::class.java)).isNull()
        assertThat(serviceClass.declaredMethods.single { it.name == "reconcileLearningRecords" }
            .getAnnotation(Transactional::class.java)).isNull()
    }

    @Test
    fun `cancellation propagates and does not attempt any remaining candidates`() {
        val cancellation = CancellationException("Synthetic recovery cancellation.")
        val records = RecordingAppendPort(listOf(candidate("before"), candidate("cancelled"), candidate("after")))
            .apply { failures["cancelled"] = cancellation }

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { VoiceStudyLearningRecordProjectionService(records).reconcileLearningRecords(10) }
        }

        assertThat(thrown).hasMessage(cancellation.message)
        assertThat(records.requestedLimits).containsExactly(10)
        assertThat(records.attempted.map { it.sessionId }).containsExactly("before", "cancelled")
        assertThat(records.persisted.map { it.sessionId }).containsExactly("before")
    }

    @Test
    fun `recovery only replays stored extraction evidence without summary generation grading or quota dependencies`(): Unit = runBlocking {
        val first = candidate("already-extracted", userId = 7)
        val second = candidate("another-owner", userId = 19)
        val records = RecordingAppendPort(listOf(first, second))

        val completed = VoiceStudyLearningRecordProjectionService(records).reconcileLearningRecords(2)

        assertThat(completed).isEqualTo(2)
        assertThat(records.attempted.map { it.userId to it.sessionId })
            .containsExactly(7L to first.sessionId, 19L to second.sessionId)
        assertThat(records.persisted[0].explorations).isSameAs(first.explorations)
        assertThat(records.persisted[1].explorations).isSameAs(second.explorations)
        assertThat(records.persisted[0].explorations.single().exchanges.map { it.score })
            .containsExactly(85, null)
        assertThat(records.persisted[0].explorations.single().exchanges.map { it.questionTurnId })
            .containsExactly(3L, 6L)
        assertThat(records.persisted.all { it.now != Instant.EPOCH }).isTrue()
        // The lower append port is the entire dependency surface, not a live tutor/generator/quota service.
        assertThat(VoiceStudyLearningRecordProjectionService::class.java.declaredConstructors.single().parameterTypes)
            .containsExactly(VoiceStudyLearningRecordAppendPort::class.java)
    }

    private fun candidate(sessionId: String, userId: Long = 7) = VoiceStudyLearningRecordProjectionCandidate(
        userId = userId,
        sessionId = sessionId,
        explorations = listOf(
            VoiceTutorExploration(
                topic = "Redis 캐시", studyId = 43, difficulty = 4,
                depthSummary = "TTL과 만료된 키의 삭제 시점을 다뤘습니다.",
                exchanges = listOf(
                    VoiceTutorLearningExchange(
                        kind = VoiceTutorExchangeKind.TUTOR_QUESTION,
                        question = "TTL을 설정하는 이유는 무엇인가요?",
                        answer = "오래된 데이터와 메모리 사용을 줄이기 위해서요.",
                        score = 85,
                        strengths = listOf("데이터 최신성과 메모리 사용을 함께 설명했습니다."),
                        improvements = listOf("실제 삭제 시점도 구분해 보세요."),
                        questionTurnId = 3, answerTurnIds = listOf(4), feedbackTurnIds = listOf(5),
                    ),
                    VoiceTutorLearningExchange(
                        kind = VoiceTutorExchangeKind.LEARNER_QUESTION,
                        question = "TTL이 끝나면 메모리에서 바로 삭제되나요?",
                        answer = "접근할 때 삭제하거나 주기적으로 만료된 키를 정리합니다.",
                        score = null, strengths = emptyList(), improvements = emptyList(),
                        questionTurnId = 6, answerTurnIds = listOf(7), feedbackTurnIds = emptyList(),
                    ),
                ),
            ),
        ),
    )

    private data class AppendCall(
        val userId: Long,
        val sessionId: String,
        val explorations: List<VoiceTutorExploration>,
        val now: Instant,
    )

    private class RecordingAppendPort(
        private val candidates: List<VoiceStudyLearningRecordProjectionCandidate>,
    ) : VoiceStudyLearningRecordAppendPort {
        val requestedLimits = mutableListOf<Int>()
        val attempted = mutableListOf<AppendCall>()
        val persisted = mutableListOf<AppendCall>()
        val failures = mutableMapOf<String, Exception>()

        override suspend fun completedCandidates(limit: Int): List<VoiceStudyLearningRecordProjectionCandidate> {
            requestedLimits += limit
            return candidates.take(limit)
        }

        override suspend fun appendCompletedSession(
            userId: Long,
            sessionId: String,
            explorations: List<VoiceTutorExploration>,
            now: Instant,
        ) {
            val call = AppendCall(userId, sessionId, explorations, now)
            attempted += call
            failures[sessionId]?.let { throw it }
            persisted += call
        }
    }
}
