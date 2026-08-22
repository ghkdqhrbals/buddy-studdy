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
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.GetAnswerGradingProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.GetQuestionGenerationProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.RequestQuestionGenerationUseCase
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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
    )
    private val principal = Principal(7, "device-7", 70, anonymous = false)

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
        val operations = BuddyStudyMcpService::class.declaredMemberFunctions
            .filter { it.name in MCP_OPERATION_NAMES }
            .associate { function ->
                function.name to function.findAnnotation<RequirePermission>()?.value?.toSet().orEmpty()
            }

        assertThat(operations.keys).containsExactlyInAnyOrderElementsOf(MCP_OPERATION_NAMES)
        assertThat(operations.values).allSatisfy { permissions -> assertThat(permissions).isNotEmpty() }
        assertThat(operations.getValue("deleteStudy")).containsExactly(Permissions.STUDY_DELETE)
        assertThat(operations.getValue("submitAnswer")).containsExactly(Permissions.RECORD_UPDATE)
        assertThat(operations.getValue("getMyContext")).containsExactly(Permissions.PROFILE_READ)
        assertThat(operations.getValue("getQuestionProcess"))
            .describedAs("polling an accepted question must remain available after question quota is exhausted")
            .containsExactly(Permissions.RECORD_READ)
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
        )
    }
}
