package com.buddystudy.backend.voice

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.mcp.adapter.inbound.McpJsonSchemaValidatorProvider
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorUserInputContractTest {
    private val mapper = JsonMapperProvider.mapper
    private val validator = McpJsonSchemaValidatorProvider.create()
    private val schema = VoiceTutorUserInputContract.definition.parameters.mapValues { requireNotNull(it.value) }

    @Test
    fun `tool guidance reserves blocking forms for necessary study decisions without duplicating curriculum cards`() {
        assertThat(VoiceTutorUserInputContract.definition.description)
            .contains("necessary unresolved", "learner-requested options", "Learning is the default")
            .contains("never ask for a mode", "clear start agreement", "routine next step")
            .contains("do not duplicate a server curriculum card", "wait silently for its result", "Cancellation is not consent")
            .contains("never authorize writes", "original root level", "creates only selected topics", "prepare/confirm")
            .doesNotContain("REQUIRED whenever", "ordinary preference choices")
    }

    @Test
    fun `provider schema keeps one compact question structure and a root object`() {
        assertThat(validator.validateSchema(schema).valid()).isTrue()
        assertThat(schema["type"]).isEqualTo("object")
        assertThat(schema).doesNotContainKeys("anyOf", "oneOf", "allOf", "if", "then", "else")
        assertThat(validator.validate(schema, emptyMap<String, Any>()).valid()).isFalse()
        val item = node(schema).path("properties").path("questions").path("items")
        assertThat(item.has("anyOf")).isFalse()
        assertThat(item.path("properties").path("selectionMode").path("enum").map { it.asText() })
            .containsExactly("single", "multiple", "text")
        assertThat(mapper.writeValueAsBytes(schema).size).isLessThan(2_200)
    }

    @Test
    fun `all three documented question modes pass SDK validation and runtime parsing`() {
        listOf(question("single"), question("multiple"), question("text")).forEach { question ->
            val arguments = form(question)
            assertThat(validator.validate(schema, arguments).valid()).isTrue()
            assertThat(VoiceTutorUserInputContract.validToolArguments(node(arguments))).isTrue()
            assertThat(VoiceTutorUserInputContract.request(node(arguments))?.questions?.single()?.selectionMode)
                .isEqualTo(question["selectionMode"])
        }
        val multiple = form(question("multiple", freeText = false))
        assertThat(validator.validate(schema, multiple).valid()).isTrue()
        assertThat(VoiceTutorUserInputContract.validToolArguments(node(multiple))).isTrue()
    }

    @Test
    fun `runtime rejects conditional mode mismatches and SDK still rejects malformed structure`() {
        val invalidQuestions = listOf(
            question("single") + ("options" to emptyList<Any>()),
            question("multiple") + ("options" to emptyList<Any>()),
            question("text", freeText = false),
            question("text") + ("options" to listOf(option())),
            question("single") + ("selectionMode" to "unknown"),
            question("single") + ("id" to "space is invalid"),
            question("single") + ("options" to listOf(option("invalid/id"))),
            question("single") + ("foreign" to true),
            question("single") + ("options" to listOf(option() + ("selected" to true))),
            question("single") - "allowFreeText",
        )
        invalidQuestions.forEachIndexed { index, question ->
            val arguments = form(question)
            // The compact provider schema shares three modes; their conditional
            // rules remain mandatory at the server boundary, not in duplicated schemas.
            assertThat(validator.validate(schema, arguments).valid()).describedAs("schema case %s", index).isEqualTo(index < 4)
            assertThat(VoiceTutorUserInputContract.validToolArguments(node(arguments)))
                .describedAs("runtime case %s", index).isFalse()
        }
    }

    @Test
    fun `runtime rejects empty partial mixed and extra tool arguments before either workflow`() {
        val invalid = listOf(
            emptyMap<String, Any>(),
            mapOf("title" to "방향 선택"),
            mapOf("questions" to listOf(question("single"))),
            form(question("single")) + ("studyTopicProposal" to proposal()),
            mapOf("title" to "ignored title", "studyTopicProposal" to proposal()),
            mapOf("studyTopicProposal" to proposal(), "foreign" to true),
            form(question("single")) + ("requestId" to "forged-envelope"),
            mapOf("studyTopicProposal" to null),
        )
        invalid.forEach { arguments ->
            assertThat(VoiceTutorUserInputContract.validToolArguments(node(arguments))).isFalse()
            assertThat(VoiceTutorUserInputContract.studyTopicsArguments(node(arguments))).isNull()
        }
    }

    @Test
    fun `topic proposal may omit legacy difficulty and never invents a default`() {
        val arguments = mapOf("studyTopicProposal" to proposal())
        assertThat(validator.validate(schema, arguments).valid()).isTrue()
        val parsed = requireNotNull(VoiceTutorUserInputContract.studyTopicsArguments(node(arguments)))
        assertThat(parsed.parentStudyId).isEqualTo(42)
        assertThat(parsed.topics).containsExactly("서비스 경계", "이벤트 전달")
        assertThat(parsed.difficultyLevel).isNull()
        val legacy = mapOf("studyTopicProposal" to (proposal() + ("difficultyLevel" to 2)))
        assertThat(validator.validate(schema, legacy).valid()).isTrue()
        assertThat(VoiceTutorUserInputContract.studyTopicsArguments(node(legacy))?.difficultyLevel).isEqualTo(2)
    }

    @Test
    fun `invalid proposal data is rejected by SDK and runtime`() {
        val invalid = listOf(
            proposal() - "parentStudyId",
            proposal() + ("parentStudyId" to 0),
            proposal() + ("parentStudyId" to Long.MAX_VALUE.toBigInteger().add(java.math.BigInteger.ONE)),
            proposal() + ("difficultyLevel" to 0),
            proposal() + ("difficultyLevel" to null),
            proposal() + ("difficultyLevel" to 2.5),
            proposal() + ("topics" to emptyList<String>()),
            proposal() + ("topics" to listOf("중복", "중복")),
            proposal() + ("foreign" to true),
        )
        invalid.forEachIndexed { index, proposal ->
            val arguments = mapOf("studyTopicProposal" to proposal)
            assertThat(validator.validate(schema, arguments).valid()).describedAs("schema case %s", index).isFalse()
            assertThat(VoiceTutorUserInputContract.validToolArguments(node(arguments)))
                .describedAs("runtime case %s", index).isFalse()
        }
    }

    @Test
    fun `transport envelope keeps presentation fields without becoming model arguments`() {
        val envelope = form(question("single")) + mapOf(
            "type" to "buddystudy.voice.user_input.request",
            "requestId" to "6d11e285-96b9-4ff0-b2bc-a4e21562eafe",
            "sessionId" to "1476edcb-0cf2-44d4-904a-4fc0fc6fcf48",
            "attemptId" to "b9050189-d56a-432b-ad58-627b5b866766",
            "sequence" to 1,
            "operationId" to "call_curriculum_1",
        )
        assertThat(VoiceTutorUserInputContract.request(node(envelope))).isNotNull()
        assertThat(VoiceTutorUserInputContract.validToolArguments(node(envelope))).isFalse()
        assertThat(VoiceTutorUserInputContract.request(node(envelope + ("studyTopicProposal" to proposal())))).isNull()
    }

    @Test
    fun `runtime preserves semantic duplicate and text safety checks outside schema subset`() {
        val duplicateQuestions = mapOf("title" to "선택", "questions" to listOf(question("single"), question("text")))
        val duplicateOptions = form(question("multiple") + ("options" to listOf(option(), option())))
        val whitespace = form(question("text") + ("prompt" to "   "))
        val unsafeLabel = form(question("single") + ("options" to listOf(option() + ("label" to "unsafe\u0000label"))))
        listOf(duplicateQuestions, duplicateOptions, whitespace, unsafeLabel).forEach {
            assertThat(VoiceTutorUserInputContract.validToolArguments(node(it))).isFalse()
        }
    }

    private fun node(value: Any?): JsonNode = mapper.valueToTree(value)
    private fun option(id: String = "topic_1"): Map<String, Any> = mapOf("id" to id, "label" to "이벤트 전달")
    private fun question(mode: String, freeText: Boolean = true): Map<String, Any> = mapOf(
        "id" to "learning_direction", "prompt" to "어떤 주제로 시작할까요?", "selectionMode" to mode,
        "options" to if (mode == "text") emptyList() else listOf(option()), "allowFreeText" to freeText,
    )
    private fun form(question: Map<String, Any>): Map<String, Any> = mapOf("title" to "학습 방향", "questions" to listOf(question))
    private fun proposal(): Map<String, Any> = mapOf("parentStudyId" to 42, "topics" to listOf("서비스 경계", "이벤트 전달"))
}
