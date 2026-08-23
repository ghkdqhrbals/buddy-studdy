package com.buddystudy.backend.auth.adapter.outbound.email

import com.buddystudy.backend.test.testExternalApiHistoryRecorder

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.time.Duration

class SmtpEmailVerificationSenderTest {
    @Test
    fun `send builds a verification email with the configured sender`(): Unit = runBlocking {
        val mailSender = RecordingMailSender()
        val properties = BuddyStudyProperties().apply {
            email.from = "BuddyStudy <sender@example.com>"
        }
        val sender = SmtpEmailVerificationSender(mailSender, properties, testExternalApiHistoryRecorder())

        sender.send("tester@example.com", "123456", Duration.ofSeconds(180))

        val message = mailSender.sentMessage
        assertThat(message.allRecipients.map { it.toString() }).containsExactly("tester@example.com")
        assertThat(message.from.map { it.toString() }).containsExactly("BuddyStudy <sender@example.com>")
        assertThat(message.subject).isEqualTo("BuddyStudy verification code")
        assertThat(message.content.toString()).contains("123456").contains("180 seconds")
    }

    @Test
    fun `send maps smtp authentication failure to the email delivery api error`() {
        val properties = BuddyStudyProperties().apply {
            email.from = "BuddyStudy <sender@example.com>"
        }
        val sender = SmtpEmailVerificationSender(
            AuthenticationFailingMailSender(), properties, testExternalApiHistoryRecorder(),
        )

        val error = runCatching {
            runBlocking {
                sender.send("tester@example.com", "123456", Duration.ofSeconds(180))
            }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ApiException::class.java)
        val apiError = error as ApiException
        assertThat(apiError.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(apiError.code).isEqualTo(ApiErrorCode.EMAIL_DELIVERY_FAILED)
        assertThat(apiError.message).doesNotContain("smtp-password")
    }

    private class RecordingMailSender : JavaMailSenderImpl() {
        lateinit var sentMessage: MimeMessage

        override fun send(mimeMessage: MimeMessage) {
            sentMessage = mimeMessage
        }
    }

    private class AuthenticationFailingMailSender : JavaMailSenderImpl() {
        override fun send(mimeMessage: MimeMessage) {
            throw MailAuthenticationException("Authentication failed: smtp-password")
        }
    }
}
