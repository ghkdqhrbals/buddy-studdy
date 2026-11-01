package com.buddystudy.backend.mcp.adapter.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.study.application.model.AnswerGradingProcessResponse
import com.buddystudy.backend.study.application.model.AnswerGradingProgressResponse
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationProcessResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.study.domain.entity.QuestionStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant

class BuddyStudyMcpProcessPollingTest {
    private val owner = Principal(7, "mcp:poll-test", 0, false, oauthScopes = setOf("learning:read"))
    private val now = Instant.parse("2026-11-01T00:00:00Z")

    @Test
    fun `pending generation returns the completed existing process without another mutation`() {
        var calls = 0
        val result = call("get_question_process", mapOf("correlation_id" to "question-request")) { method, args ->
            assertThat(method).isEqualTo("getQuestionProcess")
            assertThat(args.take(2)).containsExactly(owner, "question-request")
            question(completed = ++calls == 2)
        }

        assertThat(calls).isEqualTo(2)
        assertThat(result.isError()).isFalse()
        val body = result.structuredContent() as Map<*, *>
        assertThat(body["terminal"]).isEqualTo(true)
        assertThat(body["questionId"]).isEqualTo("201")
    }

    @Test
    fun `an already completed process returns without any extra polling`() {
        var calls = 0
        val result = call("get_question_process", mapOf("correlation_id" to "question-request")) { _, _ ->
            calls++
            question(completed = true)
        }

        assertThat(calls).isEqualTo(1)
        assertThat(result.isError()).isFalse()
    }

    @Test
    fun `grading keeps the original event cursor and returns terminal failure evidence`() {
        val cursors = mutableListOf<Long>()
        val event = AnswerGradingProgressResponse(
            11, "201", "grading-request", AnswerGradingStatus.QUEUED, QuestionStatus.GRADING, occurredAt = now,
        )
        val result = call("get_grading_process", mapOf("correlation_id" to "grading-request", "after_event_id" to 10)) { method, args ->
            assertThat(method).isEqualTo("getGradingProcess")
            assertThat(args.take(2)).containsExactly(owner, "grading-request")
            cursors += (args[2] as Number).toLong()
            val failed = cursors.size == 2
            AnswerGradingProcessResponse(
                "grading-request", "201", if (failed) AnswerGradingStatus.FAILED else AnswerGradingStatus.QUEUED,
                QuestionStatus.GRADING, failed, if (failed) null else 250,
                if (failed) listOf(event, event.copy(id = 12, status = AnswerGradingStatus.FAILED, errorMessage = "Provider unavailable")) else listOf(event),
                errorMessage = if (failed) "Provider unavailable" else null,
                updatedAt = now,
            )
        }

        assertThat(cursors).containsExactly(10, 10)
        assertThat(result.isError()).isFalse()
        val body = result.structuredContent() as Map<*, *>
        assertThat(body["terminal"]).isEqualTo(true)
        assertThat(body["status"]).isEqualTo("FAILED")
        assertThat(body["events"] as List<*>).hasSize(2)
        assertThat(body["errorMessage"]).isEqualTo("Provider unavailable")
    }

    @Test
    fun `a process still running after the bounded wait remains nonterminal without a timeout error`() {
        var calls = 0
        val result = call("get_question_process", mapOf("correlation_id" to "question-request")) { method, _ ->
            assertThat(method).isEqualTo("getQuestionProcess")
            calls++
            question(completed = false)
        }

        assertThat(calls).isBetween(2, 13)
        assertThat(result.isError()).isFalse()
        val body = result.structuredContent() as Map<*, *>
        assertThat(body["terminal"]).isEqualTo(false)
        assertThat(body["status"]).isEqualTo("GENERATING")
    }

    private fun question(completed: Boolean) = QuestionGenerationProcessResponse(
        "question-request",
        if (completed) QuestionGenerationStatus.COMPLETED else QuestionGenerationStatus.GENERATING,
        if (completed) QuestionGenerationStep.COMPLETED else QuestionGenerationStep.GENERATING,
        completed, if (completed) null else 250, if (completed) "201" else null,
        question = null, failedStep = null, error = null, updatedAt = now, completedAt = if (completed) now else null,
    )

    private fun call(
        name: String,
        arguments: Map<String, Any>,
        handler: (String, List<Any?>) -> Any,
    ): McpSchema.CallToolResult {
        val useCase = Proxy.newProxyInstance(
            BuddyStudyMcpUseCase::class.java.classLoader, arrayOf(BuddyStudyMcpUseCase::class.java),
        ) { _, method, args -> handler(method.name, args?.toList().orEmpty()) } as BuddyStudyMcpUseCase
        val adapter = BuddyStudyMcpAdapter(useCase, jacksonObjectMapper().findAndRegisterModules())
        val specification = adapter.tools().single { it.tool().name() == name }
        val context = McpTransportContext.create(mapOf(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY to owner))
        return specification.callHandler().apply(context, McpSchema.CallToolRequest.builder(name).arguments(arguments).build())
            .block(Duration.ofSeconds(9))!!
    }
}
