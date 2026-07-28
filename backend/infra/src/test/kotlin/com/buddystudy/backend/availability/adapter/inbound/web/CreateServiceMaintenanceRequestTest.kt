package com.buddystudy.backend.availability.adapter.inbound.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CreateServiceMaintenanceRequestTest {
    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .findAndRegisterModules()

    @Test
    fun `deserializes maintenance creation payload`() {
        val request = objectMapper.readValue<CreateServiceMaintenanceRequest>(
            """
            {
              "titleKo": "서비스 점검",
              "titleEn": "Service maintenance",
              "titleJa": "サービスメンテナンス",
              "messageKo": "점검 중입니다.",
              "messageEn": "Maintenance is in progress.",
              "messageJa": "メンテナンス中です。",
              "startsAt": "2026-07-28T05:00:00Z",
              "endsAt": "2026-07-28T05:30:00Z"
            }
            """.trimIndent(),
        )

        assertThat(request.titleKo).isEqualTo("서비스 점검")
        assertThat(request.startsAt).isEqualTo(Instant.parse("2026-07-28T05:00:00Z"))
        assertThat(request.endsAt).isEqualTo(Instant.parse("2026-07-28T05:30:00Z"))
    }
}
