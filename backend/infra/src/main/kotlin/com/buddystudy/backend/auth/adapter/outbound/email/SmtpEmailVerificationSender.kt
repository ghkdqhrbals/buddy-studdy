package com.buddystudy.backend.auth.adapter.outbound.email

import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationSenderPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import jakarta.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class SmtpEmailVerificationSender(
    private val mailSender: JavaMailSender,
    private val properties: BuddyStudyProperties,
) : EmailVerificationSenderPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun send(email: String, code: String, ttl: Duration) {
        try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, false, Charsets.UTF_8.name())
            helper.setTo(email)
            helper.setFrom(fromAddress())
            helper.setSubject("BuddyStudy verification code")
            helper.setText(body(code, ttl), false)
            mailSender.send(message)
            logger.info("email_verification_sent email={} ttlSeconds={}", email, ttl.seconds)
        } catch (error: MailException) {
            logger.warn("email_verification_send_failed email={} error={}", email, error.message)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.EMAIL_DELIVERY_FAILED, "Email verification code could not be sent.")
        }
    }

    private fun fromAddress(): InternetAddress {
        val from = properties.email.from.trim()
        if (from.isBlank()) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.EMAIL_DELIVERY_FAILED, "Email sender is not configured.")
        }
        if (from.contains("<") && from.contains(">")) {
            return InternetAddress(from)
        }
        val parts = from.split(Regex("\\s+"))
        val address = parts.lastOrNull { it.contains("@") } ?: from
        val personal = parts.dropLastWhile { it != address }.dropLast(1).joinToString(" ").ifBlank { "BuddyStudy" }
        return InternetAddress(address, personal, Charsets.UTF_8.name())
    }

    private fun body(code: String, ttl: Duration): String =
        """
        BuddyStudy verification code

        Code: $code

        This code expires in ${ttl.seconds} seconds.
        If you did not request this code, you can ignore this email.
        """.trimIndent()
}
