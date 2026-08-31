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
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.GetAnswerGradingProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.GetQuestionGenerationProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.RequestQuestionGenerationUseCase
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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
    )
    private val principal = Principal(7, "device-7", 70, anonymous = false)

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
        assertThat(operations.getValue("submitAnswer")).containsExactly(Permissions.RECORD_UPDATE)
        assertThat(operations.getValue("getMyContext")).containsExactly(Permissions.PROFILE_READ)
        assertThat(operations.getValue("getQuestionProcess"))
            .describedAs("polling an accepted question must remain available after question quota is exhausted")
            .containsExactly(Permissions.RECORD_READ)
        assertThat(
            listOf("listVoiceTutorSessions", "getVoiceTutorSession", "getVoiceTutorQuota")
                .map(operations::getValue),
        ).allSatisfy { permissions -> assertThat(permissions).containsExactly(Permissions.VOICE_TUTOR_READ) }
    }

    private companion object {
        val MCP_OPERATION_NAMES = setOf(
            "getMyContext",
            "updateMyLearningContext",
            "listStudies",
            "getStudy",
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
            "getTopicStats",
            "getStudyGrowth",
            "listVoiceTutorSessions",
            "getVoiceTutorSession",
            "getVoiceTutorQuota",
        )
    }
}
