package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.community.application.model.TopicSubscriptionsResponse
import com.buddystudy.backend.community.application.port.inbound.TopicSubscriptionUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.NotNull
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.error.ApiErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TopicSubscriptionsRequest(
    @field:NotNull @field:Size(max = 30) var topics: List<String?>? = null,
)

@RestController
@RequestMapping("/api/v1/me/topic-subscriptions")
class TopicSubscriptionController(private val subscriptions: TopicSubscriptionWebPort) {
    @GetMapping
    @RequirePermission(Permissions.PROFILE_READ)
    suspend fun get(authentication: Authentication) = subscriptions.get(authentication)

    @PutMapping
    @RequirePermission(Permissions.PROFILE_UPDATE)
    suspend fun replace(@Valid @RequestBody body: TopicSubscriptionsRequest, authentication: Authentication) =
        subscriptions.replace(body.topics ?: throw ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Topics are required.",
        ), authentication)
}

interface TopicSubscriptionWebPort {
    suspend fun get(authentication: Authentication): TopicSubscriptionsResponse
    suspend fun replace(topics: List<String?>, authentication: Authentication): TopicSubscriptionsResponse
}

@Component
class TopicSubscriptionWebAdapter(private val subscriptions: TopicSubscriptionUseCase) : TopicSubscriptionWebPort {
    override suspend fun get(authentication: Authentication) = subscriptions.get(authentication.principalOrThrow())
    override suspend fun replace(topics: List<String?>, authentication: Authentication) =
        subscriptions.replace(authentication.principalOrThrow(), topics)
}
