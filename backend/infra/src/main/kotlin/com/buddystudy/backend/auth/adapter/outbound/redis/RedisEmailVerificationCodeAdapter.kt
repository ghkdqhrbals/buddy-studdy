package com.buddystudy.backend.auth.adapter.outbound.redis

import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationCodePort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisEmailVerificationCodeAdapter(
    private val redis: StringRedisTemplate,
) : EmailVerificationCodePort {
    override fun save(email: String, code: String, ttl: Duration) {
        redis.opsForValue().set(key(email), code, ttl)
    }

    override fun consume(email: String, code: String): Boolean {
        val key = key(email)
        val stored = redis.opsForValue().get(key) ?: return false
        if (stored != code.trim()) {
            return false
        }
        redis.delete(key)
        return true
    }

    private fun key(email: String): String = "buddystudy:auth:email-verification:${email.trim().lowercase()}"
}
