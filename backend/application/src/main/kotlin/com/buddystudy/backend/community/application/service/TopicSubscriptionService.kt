package com.buddystudy.backend.community.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.application.model.TopicSubscriptionPolicy
import com.buddystudy.backend.community.application.model.TopicSubscriptionsResponse
import com.buddystudy.backend.community.application.port.inbound.TopicSubscriptionUseCase
import com.buddystudy.backend.community.application.port.outbound.TopicSubscriptionPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TopicSubscriptionService(private val subscriptions: TopicSubscriptionPort) : TopicSubscriptionUseCase {
    @Transactional(readOnly = true)
    override suspend fun get(principal: Principal): TopicSubscriptionsResponse {
        requireAccount(principal)
        return TopicSubscriptionsResponse(subscriptions.findTopics(principal.userId))
    }

    @Transactional
    override suspend fun replace(principal: Principal, topics: List<String?>): TopicSubscriptionsResponse {
        requireAccount(principal)
        if (topics.size > TopicSubscriptionPolicy.MAX_TOPICS) invalid("Subscribe to at most 30 topics.")
        val normalized = linkedMapOf<String, String>()
        topics.forEach { raw ->
            val topic = TopicSubscriptionPolicy.display(raw ?: invalid("Topics cannot contain null."))
            val key = TopicSubscriptionPolicy.key(topic)
            if (key.isEmpty() || topic.length > TopicSubscriptionPolicy.MAX_TOPIC_LENGTH ||
                key.length > TopicSubscriptionPolicy.MAX_TOPIC_LENGTH ||
                topic.any { it.isISOControl() }
            ) invalid("Each topic must contain 1..120 characters.")
            normalized.putIfAbsent(key, topic)
        }
        val result = normalized.values.toList()
        subscriptions.replaceTopics(principal.userId, result)
        return TopicSubscriptionsResponse(result)
    }

    private fun requireAccount(principal: Principal) {
        if (principal.anonymous) throw ApiException(
            HttpStatus.FORBIDDEN, ApiErrorCode.ACCOUNT_FORBIDDEN, "Sign in to subscribe to topics.",
        )
    }

    private fun invalid(message: String): Nothing =
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)
}
