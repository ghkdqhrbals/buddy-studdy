package com.buddystudy.backend.community.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.community.application.model.TopicSubscriptionsResponse

interface TopicSubscriptionUseCase {
    suspend fun get(principal: Principal): TopicSubscriptionsResponse
    suspend fun replace(principal: Principal, topics: List<String?>): TopicSubscriptionsResponse
}
