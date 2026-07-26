package com.buddystudy.backend.common.adapter.outbound.redis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisStreamSubscriptionTest {
    @Test
    fun `push subscription creates stable unique names for ten workers`() {
        val subscription = RedisStreamSubscription(
            group = "push",
            consumerPrefix = "buddystudy-push",
            concurrency = 10,
            count = 10,
            timeout = java.time.Duration.ofSeconds(1),
        )

        assertThat((0 until subscription.concurrency).map(subscription::consumerName)).containsExactly(
            "buddystudy-push",
            "buddystudy-push-2",
            "buddystudy-push-3",
            "buddystudy-push-4",
            "buddystudy-push-5",
            "buddystudy-push-6",
            "buddystudy-push-7",
            "buddystudy-push-8",
            "buddystudy-push-9",
            "buddystudy-push-10",
        )
    }
}
