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
import com.buddystudy.backend.study.application.model.RootStudyCreationResponse
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.QuestionItemResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.model.StudyTopicSuggestionsResponse
import com.buddystudy.backend.study.application.model.StudyTopicsCreationResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.VoiceStudyLearningRecordResponse
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.BrowseStudyLearningRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.CreateRootStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicsCommand
import com.buddystudy.backend.study.application.port.inbound.ExpectedStudyMetadata
import com.buddystudy.backend.study.application.port.inbound.GetAnswerGradingProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.GetQuestionGenerationProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.RequestQuestionGenerationUseCase
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyTreeUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyCommand
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.study.domain.entity.QuestionStatus
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
    private val studyTree = Mockito.mock(StudyTreeUseCase::class.java)
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
        studyTree,
    )
    private val principal = Principal(7, "device-7", 70, anonymous = false)

    @Test
    fun `topic recommendations use the same owned catalog without creating studies or reserving quota`(): Unit = runBlocking {
        val response = StudyTopicSuggestionsResponse(42, listOf("Transactions", "Indexes"), depth = 2)
        Mockito.`when`(studyTree.suggestTopics(principal, 42, 5)).thenReturn(response)

        assertThat(service.suggestStudyTopics(principal, 42, 5)).isSameAs(response)
        Mockito.verify(studyTree).suggestTopics(principal, 42, 5)
        Mockito.verifyNoInteractions(studies, questionRequests, answers, records, voiceTutor)
    }

    @Test
    fun `topic recommendations reject anonymous owners invalid parents and unbounded counts`(): Unit = runBlocking {
        val invalid = listOf(
            Triple(principal.copy(anonymous = true), 42L, 5),
            Triple(principal, 0L, 5),
            Triple(principal, 42L, 0),
            Triple(principal, 42L, 11),
        )
        invalid.forEach { (owner, parent, count) ->
            assertThat(runCatching { service.suggestStudyTopics(owner, parent, count) }.exceptionOrNull())
                .isInstanceOf(ApiException::class.java)
        }
        Mockito.verifyNoInteractions(studyTree, studies, questionRequests)
    }

    @Test
    fun `selected topic batch delegates once preserving the immutable parent fence without quota work`(): Unit = runBlocking {
        val command = CreateStudyTopicsCommand(listOf("Transactions", "Indexes"), 8, ExpectedStudyMetadata(null, "Database", 5))
        val response = StudyTopicsCreationResponse(42, emptyList())
        Mockito.`when`(studies.createStudyTopics(principal, 42, command)).thenReturn(response)

        assertThat(service.createStudyTopics(principal, 42, command)).isSameAs(response)
        Mockito.verify(studies).createStudyTopics(principal, 42, command)
        Mockito.verifyNoInteractions(questionRequests, answers, records, voiceTutor, studyTree)
        assertThat(runCatching { service.createStudyTopics(principal.copy(anonymous = true), 42, command) }.exceptionOrNull())
            .isInstanceOf(ApiException::class.java)
        assertThat(runCatching { service.createStudyTopics(principal, 0, command) }.exceptionOrNull())
            .isInstanceOf(ApiException::class.java)
        Mockito.verifyNoMoreInteractions(studies)
    }

    @Test
    fun `pending lookup delegates exact topic filtering before pagination while preserving the legacy overload`(): Unit = runBlocking {
        val filtered = RecordsPageResponse(listOf(questionRecord()), 1, 3, 0)
        val all = RecordsPageResponse(emptyList(), 0, 30, 0)
        Mockito.`when`(records.pending(principal, 3, 0, 42L)).thenReturn(filtered)
        Mockito.`when`(records.pending(principal, 30, 0)).thenReturn(all)

        assertThat(service.listPendingQuestions(principal, 3, 0, 42L)).isSameAs(filtered)
        assertThat(service.listPendingQuestions(principal, 30, 0)).isSameAs(all)

        Mockito.verify(records).pending(principal, 3, 0, 42L)
        Mockito.verify(records).pending(principal, 30, 0)
        Mockito.verifyNoMoreInteractions(records)
        Mockito.verifyNoInteractions(answers, questionRequests, studies, voiceTutor)
    }

    @Test
    fun `skip delegates only the canonical owned question mutation and keeps the returned identity`(): Unit = runBlocking {
        val skipped = questionRecord().copy(questionStatus = QuestionStatus.SKIPPED)
        Mockito.`when`(answers.skip(principal, 91L)).thenReturn(skipped)

        assertThat(service.skipQuestion(principal, 91L)).isSameAs(skipped)
        assertThat(service.skipQuestion(principal, 91L)).isSameAs(skipped)

        Mockito.verify(answers, Mockito.times(2)).skip(principal, 91L)
        Mockito.verifyNoMoreInteractions(answers)
        Mockito.verifyNoInteractions(questionRequests, records, studies, voiceTutor)
    }

    @Test
    fun `question tools reject invalid boundaries and anonymous callers before mutation or lookup`(): Unit = runBlocking {
        for (request in listOf<suspend () -> Any>(
            { service.listPendingQuestions(principal, 3, 0, 0L) },
            { service.listPendingQuestions(principal, 0, 0, 42L) },
            { service.listPendingQuestions(principal, 101, 0, 42L) },
            { service.listPendingQuestions(principal, 3, -1, 42L) },
            { service.skipQuestion(principal, -1L) },
        )) {
            val failure = runCatching { request() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ApiException::class.java)
            assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        for (request in listOf<suspend () -> Any>(
            { service.listPendingQuestions(principal.copy(anonymous = true), 3, 0, 42L) },
            { service.skipQuestion(principal.copy(anonymous = true), 91L) },
        )) {
            val failure = runCatching { request() }.exceptionOrNull()
            assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        }
        Mockito.verifyNoInteractions(records, answers, questionRequests, studies)
    }

    @Test
    fun `skip preserves canonical owner and submitted state errors without requesting a replacement`(): Unit = runBlocking {
        for (failure in listOf(
            ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found."),
            ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Only an unanswered, ungraded question can be skipped."),
        )) {
            Mockito.doThrow(failure).`when`(answers).skip(principal, 91L)
            assertThat(runCatching { service.skipQuestion(principal, 91L) }.exceptionOrNull()).isSameAs(failure)
        }
        Mockito.verifyNoInteractions(questionRequests, records, studies, voiceTutor)
    }

    @Test
    fun `existing answer submission preserves original user text and queues canonical grading only`(): Unit = runBlocking {
        val answer = "  생성자로 의존성을 받아요.\n테스트에서는 대체할 수 있어요.  "
        val submitted = questionRecord().copy(answer = answer)
        Mockito.`when`(answers.answer(principal, 91L, answer, "ko", true)).thenReturn(submitted)

        assertThat(service.submitAnswer(principal, 91L, answer, "ko")).isSameAs(submitted)

        Mockito.verify(answers).answer(principal, 91L, answer, "ko", true)
        Mockito.verifyNoMoreInteractions(answers)
        Mockito.verifyNoInteractions(records, questionRequests, voiceTutor)
    }

    @Test
    fun `new question request reuses canonical idempotency and preserves pending question conflict without skipping`(): Unit = runBlocking {
        val conflict = ApiException(HttpStatus.CONFLICT, ApiErrorCode.STUDY_PENDING_QUESTION_EXISTS, "A pending question already exists for this study.")
        Mockito.`when`(questionRequests.request(principal, 42L, "same-request")).thenThrow(conflict)

        assertThat(runCatching { service.requestQuestion(principal, 42L, " same-request ") }.exceptionOrNull()).isSameAs(conflict)

        Mockito.verify(questionRequests).request(principal, 42L, "same-request")
        Mockito.verifyNoMoreInteractions(questionRequests)
        Mockito.verifyNoInteractions(answers, records, studies, voiceTutor)
    }

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
    fun `create-only root delegates bounded metadata without invoking question or record paths`(): Unit = runBlocking {
        val command = CreateRootStudyCommand(topic = "Operating Systems", difficultyLevel = 7)
        val response = RootStudyCreationResponse(
            created = true,
            id = 42L,
            parentStudyId = null,
            topic = "Operating Systems",
            difficultyLevel = 7,
            enabled = true,
            activeForQuestions = true,
        )
        Mockito.`when`(studies.createRootStudy(principal, command)).thenReturn(response)

        val result = service.createRootStudy(principal, command)

        assertThat(result).isSameAs(response)
        Mockito.verify(studies).createRootStudy(principal, command)
        Mockito.verifyNoMoreInteractions(studies)
        Mockito.verifyNoInteractions(
            questionRequests,
            questionProcesses,
            gradingProcesses,
            answers,
            records,
            learningRecords,
            voiceTutor,
        )
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
        assertThat(operations.getValue("createRootStudy")).containsExactly(Permissions.STUDY_CREATE)
        assertThat(operations.getValue("suggestStudyTopics")).containsExactly(Permissions.STUDY_CREATE)
        assertThat(operations.getValue("createStudyTopics")).containsExactly(Permissions.STUDY_CREATE)
        assertThat(operations.getValue("submitAnswer")).containsExactly(Permissions.RECORD_UPDATE)
        assertThat(operations.getValue("skipQuestion")).containsExactly(Permissions.RECORD_UPDATE)
        assertThat(methods.filter { it.name == "listPendingQuestions" }).hasSize(2).allSatisfy { function ->
            assertThat(function.findAnnotation<RequirePermission>()?.value?.toSet()).containsExactly(Permissions.RECORD_READ)
        }
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
        fun questionRecord() = StudyRecordResponse(
            id = "91", question = QuestionItemResponse("의존성 주입은 무엇인가요?", createdAt = Instant.EPOCH),
            answer = null, gradingResult = null, topic = "DI", difficulty = 3,
            answeredAt = null, isPublic = false, studyId = 42L,
        )
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
            "createRootStudy",
            "createStudyTopic",
            "suggestStudyTopics",
            "createStudyTopics",
            "deleteStudy",
            "listPendingQuestions",
            "skipQuestion",
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
