package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorProviderFailurePolicyTest {
    @Test
    fun `structured item call id length rejection exposes only bounded correlation fields`() {
        val eventId = "buddystudy-internal-server-tool-call-12345678-1234-1234-1234-123456789abc"
        val node = mapper.readTree(
            """
            {
              "type":"error",
              "error":{
                "type":"invalid_request_error",
                "code":"string_above_max_length",
                "param":"item.call_id",
                "event_id":"$eventId",
                "message":"private arguments and provider details must not escape"
              }
            }
            """.trimIndent(),
        )

        assertThat(safeRealtimeItemCreateRejection(node)).isEqualTo(
            VoiceTutorProviderItemCreateRejection(
                eventId = eventId,
                errorCode = "string_above_max_length",
                parameter = "item.call_id",
            ),
        )
    }

    @Test
    fun `only allowlisted validation codes and synthetic item fields classify as item create rejection`() {
        val eventId = "buddystudy-internal-server-tool-call-safe"
        val safe = listOf(
            error(eventId, "invalid_type", "item.arguments"),
            error(eventId, "invalid_value", "item.id"),
            error(eventId, "missing_required_parameter", "item.name"),
            error(eventId, "string_below_min_length", "item.call_id"),
            error(eventId, "unknown_parameter", "item.status"),
        )
        assertThat(safe.mapNotNull(::safeRealtimeItemCreateRejection)).hasSize(safe.size)

        val unsafe = listOf(
            error(eventId, "rate_limit_exceeded", "item.call_id"),
            error(eventId, "string_above_max_length", "session.instructions"),
            error(eventId, "string_above_max_length", "item.arguments.secret"),
            error(eventId, "string_above_max_length", null),
            error("invalid event id", "string_above_max_length", "item.call_id"),
            mapper.readTree(
                """{"type":"error","error":{"type":"server_error","code":"string_above_max_length","param":"item.call_id","event_id":"$eventId"}}""",
            ),
        )
        assertThat(unsafe.map(::safeRealtimeItemCreateRejection)).containsOnlyNulls()
    }

    private fun error(eventId: String, code: String, parameter: String?) =
        mapper.valueToTree<JsonNode>(
            mapOf(
                "type" to "error",
                "error" to mapOf(
                    "type" to "invalid_request_error",
                    "code" to code,
                    "param" to parameter,
                    "event_id" to eventId,
                ),
            ),
        )

    private companion object {
        val mapper = JsonMapperProvider.mapper
    }
}
