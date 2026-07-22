package com.buddystudy.backend.auth.adapter.outbound.email

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.config.BuddyStudyProperties
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.time.Duration

class SmtpEmailVerificationSenderTest {
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
