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

    fun text(value: String): String {
        val trimmed = value.trim()
        if (looksLikeJson(trimmed)) return json(value)
        return SENSITIVE_ASSIGNMENT.replace(value) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
    }

    fun url(value: String): String = value
        .replace(DEVICE_TOKEN_PATH, "/3/device/[REDACTED]")
        .replace(SENSITIVE_QUERY) { match -> "${match.groupValues[1]}=[REDACTED]" }

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

    private fun isSensitive(key: String): Boolean {
        val normalized = key.filter(Char::isLetterOrDigit).lowercase()
        return SENSITIVE_FIELD_PARTS.any { normalized.contains(it.lowercase()) }
    }

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
            "idToken",
            "verificationCode",
            "cookie",
        )
        val SENSITIVE_QUERY = Regex("""(?i)([?&](?:id_token|access_token|api_key|key|token|secret))=[^&]*""")
        val DEVICE_TOKEN_PATH = Regex("""/3/device/[^/?]+""")
        val SENSITIVE_ASSIGNMENT = Regex("""(?i)(\b(?:authorization|api[_-]?key|access[_-]?token|id[_-]?token|password|secret|verification[_-]?code)\s*[:=]\s*)\S+""")
    }
}
