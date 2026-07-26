package com.buddystudy.backend

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.PropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LocalOpenAISecretMappingTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java)

    @Test
    fun `local openai key is sourced from the namespaced aws secret`() {
        contextRunner
            .withPropertyValues(
                "local-secret.OPENAI_API_KEY=sk-from-aws",
                "buddystudy.openai.api-key=\${OPENAI_API_KEY:\${local-secret.OPENAI_API_KEY:}}",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(properties.openai.apiKey).isEqualTo("sk-from-aws")
            }
    }

    @Test
    fun `explicit local openai key overrides the aws secret`() {
        contextRunner
            .withPropertyValues(
                "OPENAI_API_KEY=sk-from-environment",
                "local-secret.OPENAI_API_KEY=sk-from-aws",
                "buddystudy.openai.api-key=\${OPENAI_API_KEY:\${local-secret.OPENAI_API_KEY:}}",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(properties.openai.apiKey).isEqualTo("sk-from-environment")
            }
    }
}
