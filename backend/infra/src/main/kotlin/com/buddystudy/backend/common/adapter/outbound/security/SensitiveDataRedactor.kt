package com.buddystudy.backend.common.adapter.outbound.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

@Component
class SensitiveDataRedactor(
    private val objectMapper: ObjectMapper,
) {
    fun fields(fields: Map<String, String>): Map<String, String> =
        fields.mapValues { (key, value) ->
            if (isSensitive(key)) "[REDACTED]" else if (looksLikeJson(value)) json(value) else value
        }

    fun json(value: String): String =
        runCatching {
            objectMapper.writeValueAsString(redactNode(objectMapper.readTree(value)))
        }.getOrDefault(value)

    private fun redactNode(node: JsonNode): JsonNode {
        when (node) {
            is ObjectNode -> {
                val fields = node.properties().toList()
                fields.forEach { (key, value) ->
                    if (isSensitive(key)) {
                        node.put(key, "[REDACTED]")
                    } else {
                        redactNode(value)
                    }
                }
            }
            is ArrayNode -> node.forEach(::redactNode)
        }
        return node
    }

    private fun isSensitive(key: String): Boolean =
        SENSITIVE_FIELD_PARTS.any { key.contains(it, ignoreCase = true) }

    private fun looksLikeJson(value: String): Boolean {
        val trimmed = value.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    private companion object {
        val SENSITIVE_FIELD_PARTS = listOf(
            "authorization",
            "password",
            "secret",
            "token",
            "privateKey",
            "apiKey",
        )
    }
}
