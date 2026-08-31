package com.buddystudy.backend.mcp.adapter.inbound

import io.modelcontextprotocol.json.schema.JsonSchemaValidator
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import tools.jackson.databind.json.JsonMapper

/** Explicit selection avoids classpath-order-dependent Jackson 2/3 SDK suppliers. */
object McpJsonSchemaValidatorProvider {
    fun create(): JsonSchemaValidator {
        // JSON Maps cross this boundary, not the application's Jackson 2 nodes
        // or ObjectMapper. Keep Spring AI's NetworkNT/Jackson 3 ABI intact.
        val delegate = DefaultJsonSchemaValidator(JsonMapper.builder().build())
        return object : JsonSchemaValidator {
            override fun validate(schema: Map<String, Any>, structuredContent: Any): JsonSchemaValidator.ValidationResponse =
                redacted(delegate.validate(schema, structuredContent))

            override fun validateSchema(schema: Map<String, Any>): JsonSchemaValidator.ValidationResponse =
                redacted(delegate.validateSchema(schema))
        }
    }

    private fun redacted(result: JsonSchemaValidator.ValidationResponse): JsonSchemaValidator.ValidationResponse =
        if (result.valid()) result else JsonSchemaValidator.ValidationResponse.asInvalid(
            // SDK HTTP wrappers log validation errors. The validator's original
            // error may quote private tool arguments or structured result data.
            "The data does not conform to the declared JSON schema.",
        )
}
