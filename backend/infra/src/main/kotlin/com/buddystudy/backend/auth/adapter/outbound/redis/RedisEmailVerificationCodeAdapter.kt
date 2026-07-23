package com.buddystudy.backend.auth.adapter.outbound.redis

import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationCodePort
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisEmailVerificationCodeAdapter(
    private val redis: ReactiveStringRedisTemplate,
) : EmailVerificationCodePort {
    override suspend fun save(email: String, code: String, ttl: Duration) {
        check(redis.opsForValue().set(key(email), code, ttl).awaitSingle()) {
            "Redis did not store the email verification code."
        }
    }

    override suspend fun consume(email: String, code: String): Boolean =
        redis.execute(COMPARE_AND_DELETE, listOf(key(email)), listOf(code.trim()))
            .next()
            .awaitSingleOrNull()
            ?: false

    private fun key(email: String): String = "buddystudy:auth:email-verification:${email.trim().lowercase()}"

    private companion object {
        val COMPARE_AND_DELETE = DefaultRedisScript(
            """
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            return 0
            """.trimIndent(),
            Boolean::class.java,
        )
    }
}
