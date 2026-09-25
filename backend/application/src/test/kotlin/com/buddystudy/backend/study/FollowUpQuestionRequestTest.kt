package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.openai.SystemQuestionQuotaReservation
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.QuestionGenerationRequestWriteService
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import java.time.Instant

class FollowUpQuestionRequestTest {
    @Test
    fun `first follow-up reserves one normal question and persists stable lineage and outbox`(): Unit = runBlocking {
        val fixture = fixture(listOf(record(20)))
        val response = fixture.writer.enqueueFollowUp(7, 20, "tap-1", now)
        assertThat(response.accepted.correlationId).isEqualTo(fixture.sagas.single().correlationId)
        assertThat(response.outboxes).hasSize(1)
        assertThat(fixture.reservations).isEqualTo(1)
        fixture.sagas.single().let {
            assertThat(it.parentRecordId).isEqualTo(20)
            assertThat(it.rootRecordId).isEqualTo(20)
            assertThat(it.followUpDepth).isEqualTo(1)
            assertThat(it.source).isEqualTo(QuestionGenerationSource.FOLLOW_UP)
            assertThat(it.quotaPeriodStartedAt).isEqualTo(now.minusSeconds(86400))
        }
    }

    @Test
    fun `same request replay preserves correlation and consumes no additional allowance`(): Unit = runBlocking {
        val fixture = fixture(listOf(record(20)))
        val first = fixture.writer.enqueueFollowUp(7, 20, "tap-1", now)
        val replay = fixture.writer.enqueueFollowUp(7, 20, "tap-1", now.plusSeconds(1))
        assertThat(replay.accepted.correlationId).isEqualTo(first.accepted.correlationId)
        assertThat(replay.outboxes).isEmpty()
        assertThat(fixture.reservations).isEqualTo(1)
        assertThat(fixture.sagas).hasSize(1)
    }

    @Test
    fun `different device key cannot create another active child`(): Unit = runBlocking {
        val fixture = fixture(listOf(record(20)))
        fixture.writer.enqueueFollowUp(7, 20, "device-one", now)
        expectError(ApiErrorCode.STUDY_PENDING_QUESTION_EXISTS) {
            fixture.writer.enqueueFollowUp(7, 20, "device-two", now)
        }
        assertThat(fixture.reservations).isEqualTo(1)
    }

    @Test
    fun `second follows latest graded child and retains original root`(): Unit = runBlocking {
        val fixture = fixture(listOf(record(20), record(21, 20, 20, 1)))
        fixture.writer.enqueueFollowUp(7, 21, "tap", now)
        assertThat(fixture.sagas.single().parentRecordId).isEqualTo(21)
        assertThat(fixture.sagas.single().rootRecordId).isEqualTo(20)
        assertThat(fixture.sagas.single().followUpDepth).isEqualTo(2)
    }

    @Test
    fun `earlier parent cannot branch after its child has been created`(): Unit = runBlocking {
        val fixture = fixture(listOf(record(20), record(21, 20, 20, 1)))
        expectError(ApiErrorCode.FOLLOW_UP_NOT_AVAILABLE) { fixture.writer.enqueueFollowUp(7, 20, "tap", now) }
        assertThat(fixture.reservations).isZero()
    }

    @Test
    fun `deleting a child cannot reclaim a follow-up slot or create another branch`(): Unit = runBlocking {
        val deleted = record(21, 20, 20, 1).apply { deletedAt = now }
        val fixture = fixture(listOf(record(20), deleted))
        expectError(ApiErrorCode.FOLLOW_UP_NOT_AVAILABLE) { fixture.writer.enqueueFollowUp(7, 20, "tap", now) }
        assertThat(fixture.reservations).isZero()
    }

    @Test
    fun `third turn is rejected even if a previous child was deleted`(): Unit = runBlocking {
        val fixture = fixture(listOf(record(20), record(21, 20, 20, 1).apply { deletedAt = now }, record(22, 21, 20, 2)))
        expectError(ApiErrorCode.FOLLOW_UP_LIMIT_REACHED) { fixture.writer.enqueueFollowUp(7, 22, "tap", now) }
        assertThat(fixture.reservations).isZero()
    }

    @Test
    fun `deleted original and foreign records cannot be used as context`(): Unit = runBlocking {
        val fixture = fixture(listOf(record(20).apply { deletedAt = now }, record(21, 20, 20, 1)))
        expectError(ApiErrorCode.RECORD_NOT_FOUND) { fixture.writer.enqueueFollowUp(7, 21, "tap", now) }
        expectError(ApiErrorCode.RECORD_NOT_FOUND) { fixture.writer.enqueueFollowUp(99, 21, "tap", now) }
        assertThat(fixture.reservations).isZero()
    }

    @ParameterizedTest
    @EnumSource(value = QuestionStatus::class, names = ["UNGRADED", "GRADING", "FAILED", "SKIPPED", "COMPLETED"])
    fun `only successful grading enables follow-up`(status: QuestionStatus): Unit = runBlocking {
        val fixture = fixture(listOf(record(20).apply { this.status = status }))
        expectError(ApiErrorCode.FOLLOW_UP_NOT_AVAILABLE) { fixture.writer.enqueueFollowUp(7, 20, "tap", now) }
        assertThat(fixture.reservations).isZero()
    }

    @Test
    fun `unscored custom record and a currently pending topic do not reserve allowance`(): Unit = runBlocking {
        val unscored = fixture(listOf(record(20).apply { score = null; source = QuestionSource.CUSTOM_QUESTION }))
        expectError(ApiErrorCode.FOLLOW_UP_NOT_AVAILABLE) { unscored.writer.enqueueFollowUp(7, 20, "tap", now) }
        val pending = fixture(listOf(record(20)), QuestionStatus.UNGRADED)
        expectError(ApiErrorCode.STUDY_PENDING_QUESTION_EXISTS) { pending.writer.enqueueFollowUp(7, 20, "tap", now) }
        assertThat(unscored.reservations + pending.reservations).isZero()
    }

    @Test
    fun `completed voice assessment never starts a question-quota follow-up`() : Unit = runBlocking {
        val voice = record(20).apply {
            recordType = com.buddystudy.study.domain.entity.StudyRecordType.VOICE_TUTOR
            source = QuestionSource.VOICE_TUTOR
            status = QuestionStatus.COMPLETED
            voiceRecordId = 10
        }
        val fixture = fixture(listOf(voice))
        expectError(ApiErrorCode.FOLLOW_UP_NOT_AVAILABLE) { fixture.writer.enqueueFollowUp(7, 20, "tap", now) }
        assertThat(fixture.reservations).isZero()
    }

    private suspend fun expectError(code: ApiErrorCode, block: suspend () -> Any) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(code)
    }

    private suspend fun fixture(thread: List<QuestionEntity>, latestStatus: QuestionStatus = QuestionStatus.GRADED): Fixture {
        val fixture = Fixture()
        val questions = Mockito.mock(QuestionPort::class.java)
        val studies = Mockito.mock(StudyPort::class.java)
        val users = Mockito.mock(UserPort::class.java)
        val keys = Mockito.mock(OpenAIQuestionKeyProvider::class.java)
        val sagaPort = Mockito.mock(QuestionGenerationSagaPort::class.java) { call ->
            when (call.method.name) {
                "findByUserIdAndIdempotencyKey" -> fixture.sagas.firstOrNull {
                    it.userId == call.getArgument<Long>(0) && it.idempotencyKey == call.getArgument<String>(1)
                }
                "findActiveByUserIdAndTopicId" -> fixture.sagas.firstOrNull()
                "insert" -> { fixture.sagas.add(call.getArgument(0)); true }
                else -> null
            }
        }
        val outbox = Mockito.mock(RedisEventOutboxAppendPort::class.java) { call ->
            if (call.method.name.startsWith("append")) 1L else null
        }
        val user = UserEntity(id = 7, providerId = "user-7")
        val study = StudyEntity(id = 11, userId = 7, deviceId = "device", topic = "Current edited topic")
        Mockito.`when`(questions.findByIdAndUserIdAndDeletedAtIsNull(Mockito.anyLong(), Mockito.anyLong())).thenAnswer { call ->
            thread.firstOrNull { it.id == call.getArgument<Long>(0) && it.userId == call.getArgument<Long>(1) && it.deletedAt == null }
        }
        Mockito.`when`(questions.lockByIdAndUserIdAndDeletedAtIsNull(20, 7)).thenReturn(thread.firstOrNull { it.id == 20L && it.deletedAt == null })
        Mockito.`when`(questions.lockThreadByRootAndUser(20, 7)).thenReturn(thread)
        Mockito.`when`(questions.findLatestStatusByStudyId(11)).thenReturn(latestStatus)
        Mockito.`when`(studies.findByIdAndUserId(11, 7)).thenReturn(study)
        Mockito.`when`(studies.findAllByUserId(7)).thenReturn(listOf(study))
        Mockito.`when`(users.findById(7)).thenReturn(user)
        Mockito.`when`(keys.resolveForQuestionGeneration(Mockito.eq(user), Mockito.anyString())).thenAnswer { call ->
            fixture.reservations += 1
            OpenAIQuestionKey("test", SystemQuestionQuotaReservation(7, now.minusSeconds(86400), call.getArgument(1)), user)
        }
        fixture.writer = QuestionGenerationRequestWriteService(studies, questions, users, keys, sagaPort, outbox)
        return fixture
    }

    private class Fixture {
        val sagas = mutableListOf<QuestionGenerationSaga>()
        var reservations = 0
        lateinit var writer: QuestionGenerationRequestWriteService
    }

    private fun record(id: Long, parent: Long? = null, root: Long? = null, depth: Int = 0) = QuestionEntity(
        id = id, userId = 7, studyId = 11, question = "Original question $id", answer = "A submitted answer",
        score = 80, status = QuestionStatus.GRADED, parentRecordId = parent, rootRecordId = root, followUpDepth = depth,
        source = if (depth == 0) QuestionSource.MANUAL else QuestionSource.FOLLOW_UP,
    )

    companion object { private val now = Instant.parse("2026-09-24T00:00:00Z") }
}
