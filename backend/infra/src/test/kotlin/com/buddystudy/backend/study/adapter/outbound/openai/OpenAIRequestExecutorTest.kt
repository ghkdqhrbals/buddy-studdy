package com.buddystudy.backend.study.adapter.outbound.openai

import com.buddystudy.backend.test.testExternalApiHistoryRecorder

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.GradingResponseStyle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

class OpenAIRequestExecutorTest {
    @Test
    fun `coverage blueprint parser preserves unlimited nested concepts`(): Unit = runBlocking {
        val concepts = parseQuestionCoverageConcepts(
            """
            {
              "concepts": [
                {
                  "key": "persistence",
                  "name": "Persistence",
                  "children": [
                    {
                      "key": "aof",
                      "name": "AOF",
                      "children": [
                        {
                          "key": "recovery",
                          "name": "Recovery",
                          "angles": [
                            { "key": "failure_mode", "name": "Failure Mode" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThat(concepts).hasSize(1)
        val persistence = concepts.single()
        assertThat(persistence.key).isEqualTo("persistence")
        val aof = persistence.children.single()
        assertThat(aof.key).isEqualTo("aof")
        val recovery = aof.children.single()
        assertThat(recovery.key).isEqualTo("recovery")
        assertThat(recovery.angles.single().key).isEqualTo("failure_mode")
    }

    @Test
    fun `grading rubric parser normalizes criterion weights to one hundred`() {
        val rubric = parseGradingRubric(
            mapOf(
                "version" to "question-rubric-v1",
                "assessmentType" to "comparison",
                "criteria" to listOf(
                    mapOf(
                        "id" to "role_difference",
                        "description" to "Explains the role difference.",
                        "weight" to 2,
                        "essential" to true,
                        "expectedEvidence" to listOf("availability", "sharding"),
                    ),
                    mapOf(
                        "id" to "use_case",
                        "description" to "Selects an appropriate use case.",
                        "weight" to 1,
                    ),
                ),
                "fatalMisconceptions" to listOf("Claims both systems are identical."),
            )
        )

        assertThat(rubric).isNotNull
        assertThat(rubric!!.criteria.map { it.weight }).containsExactly(67, 33)
        assertThat(rubric.criteria.sumOf { it.weight }).isEqualTo(100)
        assertThat(rubric.criteria.first().essential).isTrue()
        assertThat(rubric.fatalMisconceptions).containsExactly("Claims both systems are identical.")
    }

    @Test
    fun `grading rubric parser rejects missing observable criteria`() {
        val rubric = parseGradingRubric(
            mapOf(
                "assessmentType" to "explanation",
                "criteria" to listOf(
                    mapOf("id" to "empty_description", "description" to "", "weight" to 100)
                ),
            )
        )

        assertThat(rubric).isNull()
    }

    @Test
    fun `grading response style falls back to structured brief`() {
        assertThat(GradingResponseStyle.from(null)).isEqualTo(GradingResponseStyle.STRUCTURED_BRIEF)
        assertThat(GradingResponseStyle.from("unknown")).isEqualTo(GradingResponseStyle.STRUCTURED_BRIEF)
        assertThat(GradingResponseStyle.from("ACTION-COACH-V1")).isEqualTo(GradingResponseStyle.ACTION_COACH)
    }

    @Test
    fun `judge prompt requests concise reusable response parts`() {
        val executor = OpenAIRequestExecutor(BuddyStudyProperties(), testExternalApiHistoryRecorder())

        val prompt = executor.buildJudgeSystemPrompt(false)

        assertThat(prompt)
            .contains("\"summary\":\"...\"")
            .contains("\"strongPoint\":\"...\"")
            .contains("\"improvement\":\"...\"")
            .contains("\"nextAction\":\"...\"")
            .contains("only when it materially helps")
            .contains("score is at least 95")
            .contains("use a natural polite tone")
    }

    @Test
    fun `OpenAI calls use bounded timeout and retries`() {
        val properties = BuddyStudyProperties().apply {
            openai.requestTimeoutSeconds = 45
            openai.requestMaxRetries = 1
        }
        val executor = OpenAIRequestExecutor(properties, testExternalApiHistoryRecorder())

        val options = executor.options("test-key", "gpt-test", json = true)

        assertThat(options.timeout).isEqualTo(Duration.ofSeconds(45))
        assertThat(options.maxRetries).isEqualTo(1)
    }

    @Test
    fun `grading deadline always finishes before Redis recovery`() {
        val properties = BuddyStudyProperties().apply {
            openai.gradingTimeoutSeconds = 600
        }
        val executor = OpenAIRequestExecutor(properties, testExternalApiHistoryRecorder())

        assertThat(executor.gradingTimeoutMillis()).isEqualTo(270_000)
        assertThat(executor.gradingTimeoutMillis()).isLessThan(300_000)
    }

    @Test
    fun `grading deadline returns without waiting for a non cooperative call`() {
        val executor = OpenAIRequestExecutor(BuddyStudyProperties(), testExternalApiHistoryRecorder())
        var elapsedMillis = 0L

        assertThatThrownBy {
            runBlocking {
                elapsedMillis = measureTimeMillis {
                    executor.awaitGradingResult(timeoutMillis = 50) {
                        withContext(NonCancellable) {
                            delay(5_000)
                        }
                    }
                }
            }
        }.isInstanceOf(TimeoutException::class.java)

        assertThat(elapsedMillis).isLessThan(500)
        executor.destroy()
    }

    @Test
    fun `response styles render the same judgement with distinct layouts`() {
        val values = mapOf(
            "summary" to "핵심 차이를 정확히 구분했습니다.",
            "strongPoint" to "Sentinel은 HA, Cluster는 샤딩으로 구분했습니다.",
            "improvement" to "Sentinel의 감시 역할을 보완하세요.",
            "nextAction" to "마스터 선출 과정을 한 문장 추가하세요.",
        )

        val compact = renderGradingResponse(
            GradingResponseStyle.COMPACT_SUMMARY,
            "ko",
            values.getValue("summary"),
            values.getValue("strongPoint"),
            values.getValue("improvement"),
            values.getValue("nextAction"),
        )
        val structured = renderGradingResponse(
            GradingResponseStyle.STRUCTURED_BRIEF,
            "ko",
            values.getValue("summary"),
            values.getValue("strongPoint"),
            values.getValue("improvement"),
            values.getValue("nextAction"),
        )
        val action = renderGradingResponse(
            GradingResponseStyle.ACTION_COACH,
            "ko",
            values.getValue("summary"),
            values.getValue("strongPoint"),
            values.getValue("improvement"),
            values.getValue("nextAction"),
        )

        assertThat(compact.feedback).isEqualTo(values.getValue("summary"))
        assertThat(compact.explanation).isEqualTo(values.getValue("improvement"))
        assertThat(structured.explanation).isEqualTo(
            "Sentinel은 HA, Cluster는 샤딩으로 구분했습니다. " +
                "Sentinel의 감시 역할을 보완하세요. 마스터 선출 과정을 한 문장 추가하세요."
        )
        assertThat(action.explanation).isEqualTo(
            "Sentinel은 HA, Cluster는 샤딩으로 구분했습니다. " +
                "마스터 선출 과정을 한 문장 추가하세요. Sentinel의 감시 역할을 보완하세요."
        )
        assertThat(structured.explanation).doesNotContain("-", "**", "잘한 점", "보완할 점")
    }

    @Test
    fun `structured response omits unnecessary coaching and strips markdown labels`() {
        val response = renderGradingResponse(
            GradingResponseStyle.STRUCTURED_BRIEF,
            "ko",
            "핵심 차이를 정확히 설명했어요.",
            "- **잘한 점** 역할과 사용 상황을 모두 구분했어요.",
            "",
            "",
        )

        assertThat(response.feedback).isEqualTo("핵심 차이를 정확히 설명했어요.")
        assertThat(response.explanation).isEqualTo("역할과 사용 상황을 모두 구분했어요.")
    }
}
