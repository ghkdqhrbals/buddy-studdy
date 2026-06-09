package com.buddystuddy.backend.auth.adapter.outbound.email

import com.buddystuddy.backend.config.BuddyStuddyProperties
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.time.Duration

class SmtpEmailVerificationSenderTest {
    @Test
    fun `send builds verification email with normalized sender`() {
        val mailSender = RecordingMailSender()
        val properties = BuddyStuddyProperties().apply {
            email.from = "BuddyStuddy sender@example.com"
        }
        val sender = SmtpEmailVerificationSender(mailSender, properties)

        sender.send("tester@example.com", "123456", Duration.ofSeconds(180))

        val message = mailSender.sentMessage
        assertThat(message.allRecipients.map { it.toString() }).containsExactly("tester@example.com")
        assertThat(message.from.map { it.toString() }).containsExactly("BuddyStuddy <sender@example.com>")
        assertThat(message.subject).isEqualTo("BuddyStuddy verification code")
        assertThat(message.content.toString()).contains("123456").contains("180 seconds")
    }

    private class RecordingMailSender : JavaMailSenderImpl() {
        lateinit var sentMessage: MimeMessage

        override fun send(mimeMessage: MimeMessage) {
            sentMessage = mimeMessage
        }
    }
}
