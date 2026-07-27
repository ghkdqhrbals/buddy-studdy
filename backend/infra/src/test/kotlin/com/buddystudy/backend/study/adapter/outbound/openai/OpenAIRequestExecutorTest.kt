package com.buddystudy.backend.study.adapter.outbound.openai

import kotlinx.coroutines.runBlocking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
}
