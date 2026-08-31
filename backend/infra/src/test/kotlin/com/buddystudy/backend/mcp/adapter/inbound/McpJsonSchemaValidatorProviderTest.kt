package com.buddystudy.backend.mcp.adapter.inbound

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor
import java.util.ServiceLoader

class McpJsonSchemaValidatorProviderTest {
    private val schema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "study_id" to mapOf("type" to "integer", "minimum" to 1, "maximum" to Long.MAX_VALUE),
            "topic" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 100),
        ),
        "required" to listOf("study_id", "topic"),
        "additionalProperties" to false,
    )

    @Test
    fun `explicit SDK validator works when both incompatible default suppliers are installed`() {
        val suppliers = ServiceLoader.load(JsonSchemaValidatorSupplier::class.java).map { it.javaClass.name }
        assertThat(suppliers).contains(
            "io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier",
            "io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier",
        )
        val validator = McpJsonSchemaValidatorProvider.create()
        assertThat(validator.validateSchema(schema).valid()).isTrue()
        val result = validator.validate(schema, mapOf("study_id" to Long.MAX_VALUE, "topic" to "응"))
        assertThat(result.valid()).isTrue()
        assertThat(JsonMapperProvider.mapper.readTree(result.jsonStructuredOutput()).path("study_id").longValue())
            .isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `SDK validation still rejects missing fractional overflowing extra and wrong typed input`() {
        val validator = McpJsonSchemaValidatorProvider.create()
        val invalid = listOf(
            emptyMap<String, Any>(),
            mapOf("study_id" to 1.5, "topic" to "Topic"),
            mapOf("study_id" to 0, "topic" to "Topic"),
            mapOf("study_id" to Long.MAX_VALUE.toBigInteger().add(java.math.BigInteger.ONE), "topic" to "Topic"),
            mapOf("study_id" to "7", "topic" to "Topic"),
            mapOf("study_id" to 7, "topic" to "Topic", "foreign" to true),
            mapOf("study_id" to 7, "topic" to ""),
            mapOf("study_id" to 7, "topic" to "x".repeat(101)),
        )
        invalid.forEach { assertThat(validator.validate(schema, it).valid()).isFalse() }
    }

    @Test
    fun `structurally invalid tool schemas remain rejected at registration`() {
        val validator = McpJsonSchemaValidatorProvider.create()
        assertThat(validator.validateSchema(mapOf("type" to "INVALID_TYPE")).valid()).isFalse()
    }

    @Test
    fun `invalid arguments never survive in validation error text used by SDK logging wrappers`() {
        val result = McpJsonSchemaValidatorProvider.create().validate(
            schema, mapOf("study_id" to "PRIVATE_VALUE_AND_TOKEN", "topic" to "Topic"),
        )
        assertThat(result.valid()).isFalse()
        assertThat(result.errorMessage()).isEqualTo("The data does not conform to the declared JSON schema.")
        assertThat(result.jsonStructuredOutput()).isNull()
    }

    @Test
    fun `Spring AI Jackson3 structured output validation keeps its binary compatible dependency`() {
        // Constructor compiles the actual schema through NetworkNT's Jackson 3
        // overload. A global downgrade to the MCP Jackson 2 dependency breaks it.
        val advisor = StructuredOutputValidationAdvisor.builder()
            .outputJsonSchema(JsonMapperProvider.mapper.writeValueAsString(schema))
            .maxRepeatAttempts(0)
            .build()
        assertThat(advisor.name).isEqualTo("Structured Output Validation Advisor")
    }
}
