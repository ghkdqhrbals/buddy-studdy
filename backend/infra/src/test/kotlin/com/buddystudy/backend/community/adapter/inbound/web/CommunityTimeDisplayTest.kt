package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.adapter.inbound.web.RecordTimeDisplayMapper
import com.buddystudy.backend.community.application.model.CommunityFeedItemResponse
import com.buddystudy.backend.community.application.model.CommunityQuestionResponse
import com.buddystudy.backend.community.application.model.CommunityQuestionsResponse
import com.buddystudy.backend.community.application.model.NativeAdSlotResponse
import com.buddystudy.backend.community.application.model.PublicFeedScope
import com.buddystudy.backend.community.application.model.PublicFeedSort
import com.buddystudy.backend.community.application.port.inbound.CommunityUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import java.time.Instant

class CommunityTimeDisplayTest {
    private val principal = Principal(7, "device", 11, false)
    private val authentication = UsernamePasswordAuthenticationToken(principal, null)
    private val useCase = mock(CommunityUseCase::class.java)
    private val adapter = CommunityWebAdapter(useCase)
    private val now = Instant.parse("2026-09-28T12:00:00Z")
    private val question = CommunityQuestionResponse(
        id = "42", question = "Which structure?", answer = "Answer", gradingResult = null,
        topic = "Anatomy", difficultyLevel = 3, status = "answered", source = "manual",
        createdAt = now.minusSeconds(86_400), answeredAt = now.minusSeconds(120), author = null,
    )
    private val slot = CommunityFeedItemResponse.nativeAdSlot(NativeAdSlotResponse("slot-1", "COMMUNITY_FEED"))
    private val page = CommunityQuestionsResponse(
        questions = listOf(question),
        items = listOf(slot, CommunityFeedItemResponse.publicQuestion(question)),
        totalCount = 3, limit = 20, offset = 0,
    )

    @Test
    fun `public mapping uses one snapshot in both arrays and preserves ad order and original response`() {
        val mapped = RecordTimeDisplayMapper("ko", now).publicQuestions(page)
        assertThat(mapped.items[0]).isSameAs(slot)
        assertThat(mapped.questions[0].timeDisplay!!.relativeText).isEqualTo("2분 전")
        assertThat(mapped.questions[0].timeDisplay).isEqualTo(mapped.items[1].question!!.timeDisplay)
        assertThat(mapped.questions[0].timeDisplay!!.generatedAt).isEqualTo(now)
        assertThat(page.questions[0].timeDisplay).isNull()
        assertThat(page.items[1].question!!.timeDisplay).isNull()
        assertThat(mapped.totalCount).isEqualTo(page.totalCount)
    }

    @Test
    fun `public v1 omits metadata while v2 feed search liked and detail include requested display locale`(): Unit = runBlocking {
        `when`(useCase.getPublicQuestions(principal, null, "en", "original", 20, 0)).thenReturn(page)
        `when`(useCase.getPublicQuestionFeedV2(principal, "en", "original", 20, 0, PublicFeedSort.RECOMMENDED, PublicFeedScope.ALL)).thenReturn(page)
        `when`(useCase.getPublicQuestionsV2(principal, "bone", "en", "original", 20, 0, PublicFeedSort.RECOMMENDED, PublicFeedScope.ALL)).thenReturn(page)
        `when`(useCase.getLikedPublicQuestions(principal, null, "en", "original", 20, 0)).thenReturn(page)
        `when`(useCase.getPublicQuestion(principal, 42, "en", "original")).thenReturn(question)
        val client = WebTestClient.bindToController(CommunityController(adapter), CommunitySearchV2Controller(adapter))
            .webFilter<WebTestClient.ControllerSpec>(WebFilter { exchange, chain ->
                chain.filter(exchange.mutate().principal(Mono.just(authentication)).build())
            }).build()
        val suffix = "tl=en&language=ko&view=original"

        client.get().uri("/api/v1/public/questions?$suffix").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.questions[0].timeDisplay").doesNotExist()
            .jsonPath("$.items[1].question.timeDisplay").doesNotExist()
        for (route in listOf("questions?$suffix", "questions/search?query=bone&$suffix", "questions/liked?$suffix")) {
            client.get().uri("/api/v2/public/$route").exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.questions[0].timeDisplay.language").isEqualTo("en")
                .jsonPath("$.questions[0].timeDisplay.timestamp").isEqualTo(question.answeredAt.toString())
                .jsonPath("$.items[0].nativeAdSlot.slotId").isEqualTo("slot-1")
                .jsonPath("$.items[1].question.timeDisplay.language").isEqualTo("en")
        }
        client.get().uri("/api/v2/public/questions/42?$suffix").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.timeDisplay.language").isEqualTo("en")
            .jsonPath("$.timeDisplay.timestamp").isEqualTo(question.answeredAt.toString())
            .jsonPath("$.createdAt").isEqualTo(question.createdAt.toString())
        verify(useCase).getPublicQuestion(principal, 42, "en", "original")
    }

    @Test
    fun `v2 liked retains permission and principal requirements`() {
        val route = CommunitySearchV2Controller::class.java.methods.single { it.name == "getLikedPublicQuestionsV2" }
        assertThat(route.getAnnotation(RequirePermission::class.java).value).containsExactly(Permissions.PUBLIC_QUESTION_LIKE)
        assertThatThrownBy {
            runBlocking { adapter.getLikedPublicQuestionsV2(null, "ko", "localized", 20, 0, UsernamePasswordAuthenticationToken("anonymous", null)) }
        }.isInstanceOf(com.buddystudy.backend.common.application.error.ApiException::class.java)
    }
}
