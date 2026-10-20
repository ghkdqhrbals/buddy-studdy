package com.buddystudy.backend.mcpoauth

import kotlinx.coroutines.reactor.mono
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/** Bounded cleanup; no runtime checks or user-content logging. */
@Component
class McpOAuthCleanupScheduler(
    private val oauth: McpOAuthUseCase,
) {
    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    fun cleanup(): Mono<Long> = mono { oauth.cleanup() }
}
