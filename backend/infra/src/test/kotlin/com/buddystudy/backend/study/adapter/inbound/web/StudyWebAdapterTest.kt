package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.stats.application.port.inbound.GetStudyGrowthUseCase
import com.buddystudy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.GetAnswerGradingProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.GetQuestionGenerationProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaUseCase
import com.buddystudy.backend.study.application.port.inbound.RequestQuestionGenerationUseCase
import com.buddystudy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyTreeUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class StudyWebAdapterTest {
    @Test
    fun `clear records invokes the authenticated user use case before returning no content`(): Unit = runBlocking {
        val study = mock(StudyUseCase::class.java)
        val adapter = StudyWebAdapter(
            studyUseCase = study,
            recordsUseCase = mock(BrowseRecordsUseCase::class.java),
            statsUseCase = mock(GetStudyStatsUseCase::class.java),
            studyGrowthUseCase = mock(GetStudyGrowthUseCase::class.java),
            studySyncUseCase = mock(StudySyncUseCase::class.java),
            studyTreeUseCase = mock(StudyTreeUseCase::class.java),
            questionQuotaUseCase = mock(QuestionQuotaUseCase::class.java),
            answerGrading = mock(GetAnswerGradingProcessUseCase::class.java),
            requestQuestionGeneration = mock(RequestQuestionGenerationUseCase::class.java),
            getQuestionGenerationProcess = mock(GetQuestionGenerationProcessUseCase::class.java),
        )
        val principal = Principal(
            userId = 7,
            deviceId = "device-1",
            sessionId = 11,
            anonymous = false,
        )
        val authentication = UsernamePasswordAuthenticationToken(principal, null)

        val response = adapter.clearRecords(authentication)

        verify(study).clear(principal)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }
}
