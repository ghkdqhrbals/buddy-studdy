package com.buddystudy.backend

import com.buddystudy.backend.config.OpenAIConfigurationGuard
import com.buddystudy.backend.config.PropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class OpenAIConfigurationGuardTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java, OpenAIConfigurationGuard::class.java)

    @Test
    fun `development permits missing OpenAI workload keys`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=dev")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `production requires the user-content OpenAI key`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.openai.system-api-key=system-key",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "OPENAI_API_KEY_USER is required in production.",
                )
            }
    }

    @Test
    fun `production requires the system OpenAI key`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.openai.user-content-api-key=user-content-key",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "OPENAI_API_KEY_SYSTEM is required in production.",
                )
            }
    }

    @Test
    fun `production rejects a shared OpenAI key`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.openai.user-content-api-key=shared-key",
                "buddystudy.openai.system-api-key=shared-key",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "OPENAI_API_KEY_USER and OPENAI_API_KEY_SYSTEM must be different in production.",
                )
            }
    }

    @Test
    fun `production accepts distinct OpenAI workload keys`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.openai.user-content-api-key=user-content-key",
                "buddystudy.openai.system-api-key=system-key",
            )
            .run { context -> assertThat(context).hasNotFailed() }
    }
}
