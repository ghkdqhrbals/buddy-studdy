package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.community.application.port.inbound.TopicSubscriptionUseCase
import com.buddystudy.backend.community.application.port.outbound.TopicSubscriptionPort
import com.buddystudy.backend.community.application.service.TopicSubscriptionService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class TopicSubscriptionWebAdapterTest {
    private val principal = Principal(7, "device-7", 1, false)
    private val authentication = UsernamePasswordAuthenticationToken(principal, null)

    @Test
    fun `subscription read and replace derive identity solely from authentication`(): Unit = runBlocking {
        val useCase = mock(TopicSubscriptionUseCase::class.java)
        val adapter = TopicSubscriptionWebAdapter(useCase)
        adapter.get(authentication)
        adapter.replace(listOf("Swift"), authentication)
        verify(useCase).get(principal)
        verify(useCase).replace(principal, listOf("Swift"))
        val missing = UsernamePasswordAuthenticationToken("anonymous", null)
        assertThatThrownBy { runBlocking { adapter.replace(emptyList(), missing) } }
            .isInstanceOf(ApiException::class.java).extracting("code").isEqualTo(ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED)
    }

    @Test
    fun `null JSON topic element returns validation error before any mutation`() {
        var writes = 0
        val adapter = TopicSubscriptionWebAdapter(TopicSubscriptionService(object : TopicSubscriptionPort {
            override suspend fun findTopics(userId: Long) = emptyList<String>()
            override suspend fun replaceTopics(userId: Long, topics: List<String>) { writes++ }
        }))
        val body = JsonMapperProvider.mapper.readValue("""{"topics":[null]}""", TopicSubscriptionsRequest::class.java)
        assertThatThrownBy { runBlocking { adapter.replace(body.topics!!, authentication) } }
            .isInstanceOf(ApiException::class.java).extracting("code").isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat(writes).isZero()
    }

    @Test
    fun `subscription routes declare profile permissions`() {
        val methods = TopicSubscriptionController::class.java.methods
        assertThat(methods.single { it.name == "get" }.getAnnotation(RequirePermission::class.java).value)
            .containsExactly(Permissions.PROFILE_READ)
        assertThat(methods.single { it.name == "replace" }.getAnnotation(RequirePermission::class.java).value)
            .containsExactly(Permissions.PROFILE_UPDATE)
    }
}
