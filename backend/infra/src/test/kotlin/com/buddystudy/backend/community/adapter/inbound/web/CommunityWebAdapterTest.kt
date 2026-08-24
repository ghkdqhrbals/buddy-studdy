package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.application.model.CommunityQuestionsResponse
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

class CommunityWebAdapterTest {
    private val principal = Principal(
        userId = 7,
        deviceId = "device-1",
        sessionId = 11,
        anonymous = false,
    )
    private val authentication = UsernamePasswordAuthenticationToken(principal, null)

    @Test
    fun `liked questions adapter requires the principal and clamps pagination`(): Unit = runBlocking {
        val useCase = mock(CommunityUseCase::class.java)
        val expected = emptyResponse(limit = 1, offset = 0)
        `when`(
            useCase.getLikedPublicQuestions(
                principal = principal,
                query = " Swift ",
                language = "ja",
                view = "original",
                limit = 1,
                offset = 0,
            ),
        ).thenReturn(expected)
        val adapter = CommunityWebAdapter(useCase)

        val response = adapter.getLikedPublicQuestions(
            query = " Swift ",
            language = "ja",
            view = "original",
            limit = 0,
            offset = -7,
            authentication = authentication,
        )

        assertThat(response).isSameAs(expected)
        verify(useCase).getLikedPublicQuestions(principal, " Swift ", "ja", "original", 1, 0)
    }

    @Test
    fun `liked questions controller gives tl precedence and forwards every query parameter`(): Unit = runBlocking {
        val web = mock(CommunityWebPort::class.java)
        val expected = emptyResponse(limit = 35, offset = 7)
        `when`(
            web.getLikedPublicQuestions(
                query = "Redis",
                language = "en",
                view = "localized",
                limit = 35,
                offset = 7,
                authentication = authentication,
            ),
        ).thenReturn(expected)
        val client = WebTestClient.bindToController(CommunityController(web))
            .webFilter<WebTestClient.ControllerSpec>(
                WebFilter { exchange, chain ->
                    chain.filter(exchange.mutate().principal(Mono.just(authentication)).build())
                },
            )
            .build()

        client.get()
            .uri { builder ->
                builder.path("/api/v1/public/questions/liked")
                    .queryParam("query", "Redis")
                    .queryParam("limit", "35")
                    .queryParam("offset", "7")
                    .queryParam("tl", " en ")
                    .queryParam("language", "ko")
                    .queryParam("view", "localized")
                    .build()
            }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.limit").isEqualTo(35)
            .jsonPath("$.offset").isEqualTo(7)

        verify(web).getLikedPublicQuestions("Redis", "en", "localized", 35, 7, authentication)
    }

    @Test
    fun `liked questions route declares authentication permission and rejects a missing principal`() {
        val method = CommunityController::class.java.methods.single { it.name == "getLikedPublicQuestions" }
        val mapping = method.getAnnotation(GetMapping::class.java)
        val permission = method.getAnnotation(RequirePermission::class.java)

        assertThat(mapping.value).containsExactly("/public/questions/liked")
        assertThat(permission.value).containsExactly(Permissions.PUBLIC_QUESTION_LIKE)

        val adapter = CommunityWebAdapter(mock(CommunityUseCase::class.java))
        val invalidAuthentication = UsernamePasswordAuthenticationToken("anonymous", null)
        assertThatThrownBy {
            runBlocking {
                adapter.getLikedPublicQuestions(null, "ko", "localized", 20, 0, invalidAuthentication)
            }
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED)
    }

    private fun emptyResponse(limit: Int, offset: Int) = CommunityQuestionsResponse(
        questions = emptyList(),
        totalCount = 0,
        limit = limit,
        offset = offset,
    )
}
