package com.buddystudy.backend.common.adapter.outbound.redis

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate

class RedisStreamRetiredTopologyCleanerTest {
    @Test
    fun `startup removes only exact retired stream keys that exist`() {
        val redis = mock(StringRedisTemplate::class.java)
        `when`(redis.hasKey(anyString())).thenAnswer { invocation ->
            invocation.getArgument<String>(0) in EXISTING_RETIRED_KEYS
        }
        `when`(redis.delete(EXISTING_RETIRED_KEYS)).thenReturn(EXISTING_RETIRED_KEYS.size.toLong())

        RedisStreamRetiredTopologyCleaner(redis).clean()

        verify(redis).delete(EXISTING_RETIRED_KEYS)
        RETIRED_KEYS.forEach { verify(redis).hasKey(it) }
        verifyNoMoreInteractions(redis)
    }

    private companion object {
        val RETIRED_KEYS = listOf(
            "buddystudy-events-v1",
            "buddystudy-question-generation-v1",
            "buddystudy-question-generated-v1",
            "buddystudy-content-translation-v1",
            "buddystudy-push-v1",
            "buddystudy-native-push-test-20260728",
        )
        val EXISTING_RETIRED_KEYS = listOf(
            "buddystudy-events-v1",
            "buddystudy-push-v1",
        )
    }
}
