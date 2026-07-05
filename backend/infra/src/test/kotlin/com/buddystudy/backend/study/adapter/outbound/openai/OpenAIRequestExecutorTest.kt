package com.buddystudy.backend.study.adapter.outbound.openai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenAIRequestExecutorTest {
    @Test
    fun `coverage blueprint parser preserves unlimited nested concepts`() {
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
}
