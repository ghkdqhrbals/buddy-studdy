package com.buddystudy.backend.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.learningcontext.application.port.inbound.LearningContextUseCase
import com.buddystudy.backend.mcp.application.service.BuddyStudyMcpService
import com.buddystudy.backend.profile.application.port.inbound.ProfileUseCase
import com.buddystudy.backend.stats.application.port.inbound.GetStudyGrowthUseCase
import com.buddystudy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.VoiceStudyLearningRecordResponse
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.BrowseStudyLearningRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.GetAnswerGradingProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.GetQuestionGenerationProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.RequestQuestionGenerationUseCase
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyCommand
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.time.Instant
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation

class BuddyStudyMcpServiceTest {
    private val profiles = Mockito.mock(ProfileUseCase::class.java)
    private val learningContexts = Mockito.mock(LearningContextUseCase::class.java)
    private val studies = Mockito.mock(StudySyncUseCase::class.java)
    private val records = Mockito.mock(BrowseRecordsUseCase::class.java)
    private val answers = Mockito.mock(StudyUseCase::class.java)
    private val questionRequests = Mockito.mock(RequestQuestionGenerationUseCase::class.java)
    private val questionProcesses = Mockito.mock(GetQuestionGenerationProcessUseCase::class.java)
    private val gradingProcesses = Mockito.mock(GetAnswerGradingProcessUseCase::class.java)
    private val stats = Mockito.mock(GetStudyStatsUseCase::class.java)
    private val growth = Mockito.mock(GetStudyGrowthUseCase::class.java)
    private val voiceTutor = Mockito.mock(VoiceTutorUseCase::class.java)
    private val learningRecords = Mockito.mock(BrowseStudyLearningRecordsUseCase::class.java)
    private val service = BuddyStudyMcpService(
        profiles,
        learningContexts,
        studies,
        records,
        answers,
        questionRequests,
        questionProcesses,
        gradingProcesses,
        stats,
        growth,
        voiceTutor,
        learningRecords,
    )
    private val principal = Principal(7, "device-7", 70, anonymous = false)

    @Test
    fun `node learning history delegates cursor scope and original view without ordinary record mutations`(): Unit = runBlocking {
        val page = StudyLearningRecordsPageResponse(emptyList(), "next-position", true, 3)
        Mockito.`when`(learningRecords.learningRecords(principal, 42L, "subtree", 3, "opaque-position", "ja", "original"))
            .thenReturn(page)

        val result = service.listStudyLearningRecords(principal, 42L, "subtree", 3, "opaque-position", "ja", "original")

        assertThat(result).isSameAs(page)
        Mockito.verify(learningRecords).learningRecords(principal, 42L, "subtree", 3, "opaque-position", "ja", "original")
        Mockito.verifyNoMoreInteractions(learningRecords)
        Mockito.verifyNoInteractions(records, answers, questionRequests, voiceTutor, studies)
    }

    @Test
    fun `voice detail preserves the original exchange score and source identity without using a question id`(): Unit = runBlocking {
        val record = voiceRecord()
        Mockito.`when`(learningRecords.voiceLearningRecord(principal, 91L, "ko", "original")).thenReturn(record)

        val result = service.getVoiceLearningRecord(principal, 91L, "ko", "original")

        assertThat(result).isSameAs(record)
        assertThat(result.answer).isEqualTo("  오래전에 쓴 키부터요.\n")
        assertThat(result.score).isEqualTo(85)
        assertThat(result.questionTurnId).isEqualTo(11)
        assertThat(result.answerTurnIds).containsExactly(12)
        Mockito.verifyNoInteractions(records, answers, questionRequests, voiceTutor, studies)
    }

    @Test
    fun `invalid node history boundaries are rejected before any query`(): Unit = runBlocking {
        val invalidRequests: List<suspend () -> Any> = listOf(
            { service.listStudyLearningRecords(principal, 0L, "node", 5, null, "ko", "original") },
            { service.listStudyLearningRecords(principal, 42L, "all", 5, null, "ko", "original") },
            { service.listStudyLearningRecords(principal, 42L, "node", 0, null, "ko", "original") },
            { service.listStudyLearningRecords(principal, 42L, "node", 31, null, "ko", "original") },
            { service.listStudyLearningRecords(principal, 42L, "node", 5, "", "ko", "original") },
            { service.listStudyLearningRecords(principal, 42L, "node", 5, "x".repeat(513), "ko", "original") },
            { service.listStudyLearningRecords(principal, 42L, "node", 5, null, "ko", "invalid") },
            { service.getVoiceLearningRecord(principal, -1L, "ko", "original") },
        )
        for (request in invalidRequests) {
            val failure = runCatching { request() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ApiException::class.java)
            assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        Mockito.verifyNoInteractions(learningRecords, records, studies, answers, questionRequests)
    }

    @Test
    fun `private learning history rejects anonymous callers and preserves owner not found errors`(): Unit = runBlocking {
        for (request in listOf<suspend () -> Any>(
            { service.listStudyLearningRecords(principal.copy(anonymous = true), 42L, "node", 5, null, "ko", "original") },
            { service.getVoiceLearningRecord(principal.copy(anonymous = true), 91L, "ko", "original") },
        )) {
            val failure = runCatching { request() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ApiException::class.java)
            assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        }
        Mockito.verifyNoInteractions(learningRecords)
        val missing = ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        Mockito.`when`(learningRecords.voiceLearningRecord(principal, 91L, "ko", "original")).thenThrow(missing)
        assertThat(runCatching { service.getVoiceLearningRecord(principal, 91L, "ko", "original") }.exceptionOrNull())
            .isSameAs(missing)
        Mockito.verifyNoInteractions(records, answers, questionRequests)
    }

    @Test
    fun `child list delegates the exact parent search page without question generation`(): Unit = runBlocking {
        val page = StudyPageResponse(emptyList(), 0, 3, 1, Instant.EPOCH)
        Mockito.`when`(studies.study(principal, 3, 1, "Redis", "en", 42L)).thenReturn(page)

        val result = service.listStudies(principal, 3, 1, "Redis", "en", parentStudyId = 42L)

        assertThat(result).isSameAs(page)
        Mockito.verify(studies).study(principal, 3, 1, "Redis", "en", 42L)
        Mockito.verifyNoMoreInteractions(studies)
        Mockito.verifyNoInteractions(questionRequests, answers, records, profiles, learningContexts)
    }

    @Test
    fun `unfiltered list still delegates the original study page contract`(): Unit = runBlocking {
        val page = StudyPageResponse(emptyList(), 0, 100, 0, Instant.EPOCH)
        Mockito.`when`(studies.study(principal, 100, 0, null, "ko")).thenReturn(page)

        assertThat(service.listStudies(principal, 100, 0, null, "ko")).isSameAs(page)

        Mockito.verify(studies).study(principal, 100, 0, null, "ko")
        Mockito.verifyNoMoreInteractions(studies)
    }

    @Test
    fun `invalid child id or page is rejected before querying studies`(): Unit = runBlocking {
        listOf(
            Triple(0L, 10, 0),
            Triple(-1L, 10, 0),
            Triple(42L, 0, 0),
            Triple(42L, 501, 0),
            Triple(42L, 10, -1),
        ).forEach { (parentId, limit, offset) ->
            val failure = runCatching {
                service.listStudies(principal, limit, offset, null, "ko", parentId)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ApiException::class.java)
            assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        Mockito.verifyNoInteractions(studies, questionRequests)
    }

    @Test
    fun `anonymous child list cannot query owned study data`(): Unit = runBlocking {
        val failure = runCatching {
            service.listStudies(principal.copy(anonymous = true), 10, 0, null, "ko", 42L)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        Mockito.verifyNoInteractions(studies)
    }

    @Test
    fun `delete requires explicit confirmation before touching study state`(): Unit = runBlocking {
        val failure = runCatching {
            service.deleteStudy(principal, studyId = 42, confirmed = false)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat(failure.message).contains("confirm must be true")
        Mockito.verifyNoInteractions(studies)
    }

    @Test
    fun `metadata patches forward only provided fields to the existing node use case`(): Unit = runBlocking {
        val rename = UpdateStudyCommand(topic = "Redis Streams")
        val level = UpdateStudyCommand(difficultyLevel = 3)
        val result = studyRoom()
        Mockito.`when`(studies.updateStudy(principal, 42L, rename)).thenReturn(result)
        Mockito.`when`(studies.updateStudy(principal, 42L, level)).thenReturn(result)

        assertThat(service.updateStudy(principal, 42L, rename)).isSameAs(result)
        assertThat(service.updateStudy(principal, 42L, level)).isSameAs(result)

        Mockito.verify(studies).updateStudy(principal, 42L, rename)
        Mockito.verify(studies).updateStudy(principal, 42L, level)
        Mockito.verifyNoMoreInteractions(studies)
        Mockito.verifyNoInteractions(questionRequests, answers, records, learningRecords, voiceTutor, profiles, learningContexts)
    }

    @Test
    fun `metadata patch rejects anonymous and invalid targets and preserves owned not found failures`(): Unit = runBlocking {
        val command = UpdateStudyCommand(difficultyLevel = 4)
        val anonymous = runCatching { service.updateStudy(principal.copy(anonymous = true), 42L, command) }.exceptionOrNull() as ApiException
        assertThat(anonymous.code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        listOf(0L, -1L).forEach { id ->
            val invalid = runCatching { service.updateStudy(principal, id, command) }.exceptionOrNull() as ApiException
            assertThat(invalid.code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        Mockito.verifyNoInteractions(studies)
        val missing = ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        Mockito.`when`(studies.updateStudy(principal, 42L, command)).thenThrow(missing)
        assertThat(runCatching { service.updateStudy(principal, 42L, command) }.exceptionOrNull()).isSameAs(missing)
        Mockito.verifyNoInteractions(questionRequests, answers, records, learningRecords)
    }

    @Test
    fun `confirmed deletion passes the exact expected IDs and retains the legacy null contract`(): Unit = runBlocking {
        val expected = listOf(44L, 42L, 43L)
        val guarded = service.deleteStudy(principal, 42L, true, expected)
        val legacy = service.deleteStudy(principal, 50L, true)

        assertThat(guarded.deleted).isTrue()
        assertThat(guarded.studyId).isEqualTo(42)
        assertThat(legacy.deleted).isTrue()
        Mockito.verify(studies).deleteStudy(principal, 42L, expected)
        Mockito.verify(studies).deleteStudy(principal, 50L, null)
        Mockito.verifyNoMoreInteractions(studies)
        Mockito.verifyNoInteractions(questionRequests, answers, records, learningRecords, voiceTutor)
    }

    @Test
    fun `anonymous device account cannot read private mcp context`(): Unit = runBlocking {
        val anonymous = principal.copy(anonymous = true, status = "ANONYMOUS")

        val failure = runCatching { service.getMyContext(anonymous) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        Mockito.verifyNoInteractions(profiles, learningContexts)
    }

    @Test
    fun `every public mcp operation has an explicit permission boundary`() {
        val methods = BuddyStudyMcpService::class.declaredMemberFunctions
            .filter { it.name in MCP_OPERATION_NAMES }
        val operations = methods
            .associate { function ->
                function.name to function.findAnnotation<RequirePermission>()?.value?.toSet().orEmpty()
            }

        assertThat(operations.keys).containsExactlyInAnyOrderElementsOf(MCP_OPERATION_NAMES)
        assertThat(operations.values).allSatisfy { permissions -> assertThat(permissions).isNotEmpty() }
        assertThat(methods.filter { it.name == "listStudies" }).hasSize(2).allSatisfy { function ->
            assertThat(function.findAnnotation<RequirePermission>()?.value?.toSet())
                .containsExactly(Permissions.STUDY_READ)
        }
        assertThat(operations.getValue("deleteStudy")).containsExactly(Permissions.STUDY_DELETE)
        assertThat(operations.getValue("updateStudy")).containsExactly(Permissions.STUDY_UPDATE)
        assertThat(operations.getValue("submitAnswer")).containsExactly(Permissions.RECORD_UPDATE)
        assertThat(operations.getValue("getMyContext")).containsExactly(Permissions.PROFILE_READ)
        assertThat(operations.getValue("listStudyLearningRecords"))
            .containsExactlyInAnyOrder(Permissions.STUDY_READ, Permissions.RECORD_READ, Permissions.VOICE_TUTOR_READ)
        assertThat(operations.getValue("getVoiceLearningRecord")).containsExactly(Permissions.VOICE_TUTOR_READ)
        assertThat(operations.getValue("getQuestionProcess"))
            .describedAs("polling an accepted question must remain available after question quota is exhausted")
            .containsExactly(Permissions.RECORD_READ)
        assertThat(
            listOf("listVoiceTutorSessions", "getVoiceTutorSession", "getVoiceTutorQuota")
                .map(operations::getValue),
        ).allSatisfy { permissions -> assertThat(permissions).containsExactly(Permissions.VOICE_TUTOR_READ) }
    }

    private companion object {
        fun studyRoom() = StudyRoomResponse(
            id = 42L, parentStudyId = 40L, sortOrder = 2, topic = "Redis Streams", difficultyLevel = 3,
            intervalMinutes = 30, enabled = false, activeForQuestions = true, notificationSound = "bell.caf",
            customPrompt = "Preserved", openaiModel = "fixture-model", maxHistoryCount = 100,
            nextDueAt = Instant.EPOCH, lastSentAt = null, lastError = null, pendingQuestion = null,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )

        fun voiceRecord() = VoiceStudyLearningRecordResponse(
            id = "91", sessionId = "synthetic-prior-session", studyId = 42L, parentStudyId = 40L,
            topic = "Redis", difficulty = 3, createdAt = Instant.EPOCH, kind = VoiceTutorExchangeKind.TUTOR_QUESTION,
            question = "어떤 키를 제거하나요?", answer = "  오래전에 쓴 키부터요.\n", score = 85,
            strengths = listOf("최근 사용 시점을 짚음"), improvements = emptyList(), depthSummary = "LRU를 살펴봄",
            feedback = "85점입니다.", questionTurnId = 11L, answerTurnIds = listOf(12L), feedbackTurnIds = listOf(13L),
            sourceLanguage = "ko", requestedLanguage = "ko", displayLanguage = "ko", translationPending = false,
        )

        val MCP_OPERATION_NAMES = setOf(
            "getMyContext",
            "updateMyLearningContext",
            "listStudies",
            "getStudy",
            "updateStudy",
            "createStudy",
            "createStudyTopic",
            "deleteStudy",
            "listPendingQuestions",
            "requestQuestion",
            "getQuestionProcess",
            "submitAnswer",
            "getGradingProcess",
            "listRecords",
            "getRecord",
            "listStudyLearningRecords",
            "getVoiceLearningRecord",
            "getTopicStats",
            "getStudyGrowth",
            "listVoiceTutorSessions",
            "getVoiceTutorSession",
            "getVoiceTutorQuota",
        )
    }
}
