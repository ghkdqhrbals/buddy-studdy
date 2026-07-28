package com.buddystudy.backend

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.PropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

class LocalAwsSecretMappingTest {
    private val contextRunner = ApplicationContextRunner()
        .withInitializer { context ->
            YamlPropertySourceLoader()
                .load("application-dev", ClassPathResource("application-dev.yml"))
                .asReversed()
                .forEach(context.environment.propertySources::addLast)
        }
        .withUserConfiguration(PropertiesConfig::class.java)

    @Test
    fun `local OpenAI workload keys are sourced from the namespaced aws secret`() {
        contextRunner
            .withPropertyValues(
                "local-secret.OPENAI_USER_CONTENT_API_KEY=user-content-from-aws",
                "local-secret.OPENAI_SYSTEM_API_KEY=system-from-aws",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(properties.openai.userContentApiKey).isEqualTo("user-content-from-aws")
                assertThat(properties.openai.systemApiKey).isEqualTo("system-from-aws")
            }
    }

    @Test
    fun `explicit local OpenAI workload keys override the aws secret`() {
        contextRunner
            .withPropertyValues(
                "OPENAI_USER_CONTENT_API_KEY=user-content-from-environment",
                "OPENAI_SYSTEM_API_KEY=system-from-environment",
                "local-secret.OPENAI_USER_CONTENT_API_KEY=user-content-from-aws",
                "local-secret.OPENAI_SYSTEM_API_KEY=system-from-aws",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(properties.openai.userContentApiKey).isEqualTo("user-content-from-environment")
                assertThat(properties.openai.systemApiKey).isEqualTo("system-from-environment")
            }
    }

    @Test
    fun `legacy local OpenAI key remains a user-content fallback only`() {
        contextRunner
            .withPropertyValues(
                "local-secret.OPENAI_API_KEY=legacy-user-content-key",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(properties.openai.userContentApiKey).isEqualTo("legacy-user-content-key")
                assertThat(properties.openai.systemApiKey).isEmpty()
            }
    }

    @Test
    fun `local smtp settings are sourced from the namespaced aws secret`() {
        contextRunner
            .withPropertyValues(
                "local-secret.SMTP_HOST=smtp-from-aws.example.com",
                "local-secret.SMTP_PORT=2525",
                "local-secret.SMTP_USERNAME=mailer-from-aws@example.com",
                "local-secret.SMTP_PASSWORD=app-password-from-aws",
                "local-secret.SMTP_FROM=BuddyStudy <mailer-from-aws@example.com>",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(context.environment.getProperty("spring.mail.host")).isEqualTo("smtp-from-aws.example.com")
                assertThat(context.environment.getProperty("spring.mail.port")).isEqualTo("2525")
                assertThat(context.environment.getProperty("spring.mail.username")).isEqualTo("mailer-from-aws@example.com")
                assertThat(context.environment.getProperty("spring.mail.password")).isEqualTo("app-password-from-aws")
                assertThat(properties.email.from).isEqualTo("BuddyStudy <mailer-from-aws@example.com>")
            }
    }

    @Test
    fun `explicit local smtp settings override the aws secret`() {
        contextRunner
            .withPropertyValues(
                "SMTP_USERNAME=mailer-from-environment@example.com",
                "SMTP_PASSWORD=app-password-from-environment",
                "SMTP_FROM=mailer-from-environment@example.com",
                "local-secret.SMTP_USERNAME=mailer-from-aws@example.com",
                "local-secret.SMTP_PASSWORD=app-password-from-aws",
                "local-secret.SMTP_FROM=mailer-from-aws@example.com",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(context.environment.getProperty("spring.mail.username")).isEqualTo("mailer-from-environment@example.com")
                assertThat(context.environment.getProperty("spring.mail.password")).isEqualTo("app-password-from-environment")
                assertThat(properties.email.from).isEqualTo("mailer-from-environment@example.com")
            }
    }
}
