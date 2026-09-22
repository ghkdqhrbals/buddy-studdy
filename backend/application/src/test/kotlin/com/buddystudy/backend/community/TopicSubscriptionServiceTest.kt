package com.buddystudy.backend.community

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.application.port.outbound.TopicSubscriptionPort
import com.buddystudy.backend.community.application.service.TopicSubscriptionService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TopicSubscriptionServiceTest {
    private val account = Principal(7, "device-7", 1, false)
    private val rows = mutableMapOf<Long, List<String>>()
    private val service = TopicSubscriptionService(object : TopicSubscriptionPort {
        override suspend fun findTopics(userId: Long) = rows[userId].orEmpty()
        override suspend fun replaceTopics(userId: Long, topics: List<String>) { rows[userId] = topics }
    })

    @Test
    fun `normalization retains first display label and deduplicates case and unicode separators`(): Unit = runBlocking {
        val result = service.replace(account, listOf("  Swift\u2003UI ", "swift-ui", "SWIFT_UI", "SwiftUI", "자료\u00a0구조", "자료구조"))
        assertThat(result.topics).containsExactly("Swift UI", "자료 구조")
        assertThat(service.get(account).topics).containsExactlyElementsOf(result.topics)
    }

    @Test
    fun `replacement unsubscribe and reads are owned by the authenticated account`(): Unit = runBlocking {
        val other = account.copy(userId = 8)
        service.replace(account, listOf("Swift"))
        service.replace(other, listOf("Redis"))
        service.replace(account, emptyList())
        assertThat(service.get(account).topics).isEmpty()
        assertThat(service.get(other).topics).containsExactly("Redis")
    }

    @Test
    fun `invalid replacement never clears previously saved topics`(): Unit = runBlocking {
        service.replace(account, listOf("Swift"))
        listOf(listOf(null), listOf("İ".repeat(120)), listOf(" "), listOf("---_"), listOf("a".repeat(121)), listOf("bad\u0000topic"), (1..31).map { "Topic $it" }).forEach { invalid ->
            assertThatThrownBy { runBlocking { service.replace(account, invalid) } }
                .isInstanceOf(ApiException::class.java).extracting("code").isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        assertThat(service.get(account).topics).containsExactly("Swift")
    }

    @Test
    fun `anonymous devices cannot read or write account topic subscriptions`() {
        val anonymous = account.copy(anonymous = true)
        assertThatThrownBy { runBlocking { service.get(anonymous) } }
            .isInstanceOf(ApiException::class.java).extracting("code").isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        assertThatThrownBy { runBlocking { service.replace(anonymous, listOf("Swift")) } }
            .isInstanceOf(ApiException::class.java).extracting("code").isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        assertThat(rows).isEmpty()
    }
}
