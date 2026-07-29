package com.buddystudy.backend.auth.adapter.outbound.email

import com.buddystudy.backend.config.PropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl

class SmtpMailConfigurationContextTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java, SmtpMailConfiguration::class.java)

    @Test
    fun `context binds smtp properties and creates the mail sender bean`() {
        contextRunner
            .withPropertyValues(*validProperties())
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(JavaMailSender::class.java)

                val mailSender = context.getBean(JavaMailSender::class.java) as JavaMailSenderImpl
                assertThat(mailSender.host).isEqualTo("smtp.example.com")
                assertThat(mailSender.port).isEqualTo(2525)
                assertThat(mailSender.username).isEqualTo("mailer@example.com")
                assertThat(mailSender.password).isEqualTo("app-password")
                assertThat(mailSender.defaultEncoding).isEqualTo(Charsets.UTF_8.name())
                assertThat(mailSender.javaMailProperties)
                    .containsEntry("mail.smtp.auth", "true")
                    .containsEntry("mail.smtp.starttls.enable", "true")
                    .containsEntry("mail.smtp.starttls.required", "true")
            }
    }

    @ParameterizedTest(name = "missing {0} prevents context startup")
    @ValueSource(strings = ["host", "username", "password", "from"])
    fun `context fails during bean creation when a required smtp property is missing`(missingProperty: String) {
        contextRunner
            .withPropertyValues(*validProperties(excluding = missingProperty))
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(IllegalStateException::class.java)
                    .hasStackTraceContaining("buddystudy.email.$missingProperty")
            }
    }

    @Test
    fun `context fails during bean creation when smtp port is outside the valid range`() {
        contextRunner
            .withPropertyValues(*validProperties("port" to "70000"))
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(IllegalStateException::class.java)
                    .hasStackTraceContaining("buddystudy.email.port must be between 1 and 65535")
            }
    }

    @Test
    fun `context fails during bean creation when smtp host contains whitespace`() {
        contextRunner
            .withPropertyValues(*validProperties("host" to "smtp example.com"))
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(IllegalStateException::class.java)
                    .hasStackTraceContaining("buddystudy.email.host must not contain whitespace")
            }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "BuddyStudy mailer.example.com",
            "first@example.com, second@example.com",
        ],
    )
    fun `context fails during bean creation when smtp from is invalid`(invalidFrom: String) {
        contextRunner
            .withPropertyValues(*validProperties("from" to invalidFrom))
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("javaMailSender")
                    .hasStackTraceContaining("buddystudy.email.from")
            }
    }

    private fun validProperties(
        vararg overrides: Pair<String, String>,
        excluding: String? = null,
    ): Array<String> {
        val values = linkedMapOf(
            "host" to "smtp.example.com",
            "port" to "2525",
            "username" to "mailer@example.com",
            "password" to "app-password",
            "from" to "BuddyStudy <mailer@example.com>",
        )
        overrides.forEach { (key, value) -> values[key] = value }
        excluding?.let(values::remove)
        return values.map { (key, value) -> "buddystudy.email.$key=$value" }.toTypedArray()
    }
}
