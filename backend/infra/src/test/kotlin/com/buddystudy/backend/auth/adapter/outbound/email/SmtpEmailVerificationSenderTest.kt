package com.buddystudy.backend.auth.adapter.outbound.email

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.config.BuddyStudyProperties
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.time.Duration

class SmtpEmailVerificationSenderTest {
    @Test
    fun `mail sender bean uses the unified BuddyStudy email properties`() {
        val properties = BuddyStudyProperties().apply {
            email.host = "smtp.example.com"
            email.port = 2525
            email.username = "mailer@example.com"
            email.password = "app-password"
            email.from = "BuddyStudy <mailer@example.com>"
        }

        val mailSender = SmtpMailConfiguration().javaMailSender(properties) as JavaMailSenderImpl

        assertThat(mailSender.host).isEqualTo("smtp.example.com")
        assertThat(mailSender.port).isEqualTo(2525)
        assertThat(mailSender.username).isEqualTo("mailer@example.com")
        assertThat(mailSender.password).isEqualTo("app-password")
        assertThat(mailSender.javaMailProperties.getProperty("mail.smtp.starttls.required")).isEqualTo("true")
    }

    @Test
    fun `mail sender bean fails fast when required smtp properties are missing`() {
        val properties = BuddyStudyProperties().apply {
            email.username = "mailer@example.com"
        }

        assertThatThrownBy {
            SmtpMailConfiguration().javaMailSender(properties)
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("buddystudy.email.host")
            .hasMessageContaining("buddystudy.email.password")
            .hasMessageContaining("buddystudy.email.from")
    }

    @Test
    fun `mail sender bean rejects an invalid smtp port`() {
        val properties = BuddyStudyProperties().apply {
            email.host = "smtp.example.com"
            email.port = 70_000
            email.username = "mailer@example.com"
            email.password = "app-password"
            email.from = "BuddyStudy <mailer@example.com>"
        }

        assertThatThrownBy {
            SmtpMailConfiguration().javaMailSender(properties)
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("between 1 and 65535")
    }

    @Test
    fun `send builds verification email with normalized sender`(): Unit = runBlocking {
        val mailSender = RecordingMailSender()
        val properties = BuddyStudyProperties().apply {
            email.from = "BuddyStudy sender@example.com"
        }
        val sender = SmtpEmailVerificationSender(mailSender, properties)

        sender.send("tester@example.com", "123456", Duration.ofSeconds(180))

        val message = mailSender.sentMessage
        assertThat(message.allRecipients.map { it.toString() }).containsExactly("tester@example.com")
        assertThat(message.from.map { it.toString() }).containsExactly("BuddyStudy <sender@example.com>")
        assertThat(message.subject).isEqualTo("BuddyStudy verification code")
        assertThat(message.content.toString()).contains("123456").contains("180 seconds")
    }

    private class RecordingMailSender : JavaMailSenderImpl() {
        lateinit var sentMessage: MimeMessage

        override fun send(mimeMessage: MimeMessage) {
            sentMessage = mimeMessage
        }
    }
}
