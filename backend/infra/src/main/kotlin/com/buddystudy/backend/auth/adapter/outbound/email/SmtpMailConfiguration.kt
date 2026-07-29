package com.buddystudy.backend.auth.adapter.outbound.email

import com.buddystudy.backend.config.BuddyStudyProperties
import jakarta.mail.internet.InternetAddress
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.util.Properties

@Configuration(proxyBeanMethods = false)
class SmtpMailConfiguration {
    @Bean
    fun javaMailSender(properties: BuddyStudyProperties): JavaMailSender {
        val email = validated(properties.email)
        return JavaMailSenderImpl().apply {
            host = email.host
            port = email.port
            username = email.username
            password = email.password
            defaultEncoding = Charsets.UTF_8.name()
            javaMailProperties = Properties().apply {
                setProperty("mail.smtp.auth", "true")
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
                setProperty("mail.smtp.connectiontimeout", "5000")
                setProperty("mail.smtp.timeout", "5000")
                setProperty("mail.smtp.writetimeout", "5000")
            }
        }
    }

    private fun validated(email: BuddyStudyProperties.Email): BuddyStudyProperties.Email {
        val requiredValues = mapOf(
            "buddystudy.email.host" to email.host,
            "buddystudy.email.username" to email.username,
            "buddystudy.email.password" to email.password,
            "buddystudy.email.from" to email.from,
        )
        val missing = requiredValues
            .filterValues { it.isBlank() }
            .keys
            .sorted()
        check(missing.isEmpty()) {
            "SMTP configuration is incomplete. Missing: ${missing.joinToString(", ")}"
        }
        check(email.port in 1..65_535) {
            "buddystudy.email.port must be between 1 and 65535."
        }
        check(email.host.none(Char::isWhitespace)) {
            "buddystudy.email.host must not contain whitespace."
        }
        val fromAddresses = runCatching {
            InternetAddress.parse(email.from, true)
        }.getOrElse {
            throw IllegalStateException("buddystudy.email.from must contain a valid email address.", it)
        }
        check(fromAddresses.size == 1) {
            "buddystudy.email.from must contain exactly one email address."
        }
        return email
    }
}
