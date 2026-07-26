package com.buddystudy.backend.common.adapter.outbound.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SensitiveDataRedactorTest {
    private val redactor = SensitiveDataRedactor(ObjectMapper())

    @Test
    fun `nested json secrets are redacted without flattening the payload`() {
        val redacted = redactor.json(
            """
            {
              "userId": 4,
              "credentials": {
                "authorization": "Bearer secret",
                "apiKey": "sk-secret"
              },
              "devices": [{"deviceToken": "apns-secret"}]
            }
            """.trimIndent(),
        )

        assertThat(redacted).contains("\"userId\":4")
        assertThat(redacted).contains("\"authorization\":\"[REDACTED]\"")
        assertThat(redacted).contains("\"apiKey\":\"[REDACTED]\"")
        assertThat(redacted).contains("\"deviceToken\":\"[REDACTED]\"")
        assertThat(redacted).doesNotContain("Bearer secret", "sk-secret", "apns-secret")
    }

    @Test
    fun `stream fields redact direct secrets and nested payloads`() {
        val redacted = redactor.fields(
            mapOf(
                "authorization" to "Bearer secret",
                "payload" to """{"password":"secret","eventType":"question.created"}""",
            ),
        )

        assertThat(redacted["authorization"]).isEqualTo("[REDACTED]")
        assertThat(redacted["payload"]).contains("\"password\":\"[REDACTED]\"")
        assertThat(redacted["payload"]).contains("\"eventType\":\"question.created\"")
    }
}
