package com.buddystuddy.backend

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.config.PropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ApnsSecretMappingTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java)

    @Test
    fun `apns properties can be sourced from aws secret property names`() {
        contextRunner
            .withPropertyValues(
                "notificatoin.push.apns.team-id=TEAM123",
                "notificatoin.push.apns.key-id=KEY123",
                "notificatoin.push.apns.private-key=-----BEGIN PRIVATE KEY----- TEST -----END PRIVATE KEY-----",
                "notificatoin.push.apns.bundle-id=io.github.ghkdqhrbals.StudyMate",
                "buddystuddy.apns.team-id=\${notificatoin.push.apns.team-id:\${APNS_TEAM_ID:}}",
                "buddystuddy.apns.key-id=\${notificatoin.push.apns.key-id:\${APNS_KEY_ID:}}",
                "buddystuddy.apns.auth-key-p8=\${notificatoin.push.apns.private-key:\${APNS_AUTH_KEY_P8:\${APNS_AUTH_KEY_BASE64:}}}",
                "buddystuddy.apns.bundle-id=\${notificatoin.push.apns.bundle-id:\${APNS_BUNDLE_ID:io.github.ghkdqhrbals.StudyMate}}",
            )
            .run { context ->
                val properties = context.getBean(BuddyStuddyProperties::class.java)

                assertThat(properties.apns.teamId).isEqualTo("TEAM123")
                assertThat(properties.apns.keyId).isEqualTo("KEY123")
                assertThat(properties.apns.authKeyP8).contains("BEGIN PRIVATE KEY")
                assertThat(properties.apns.bundleId).isEqualTo("io.github.ghkdqhrbals.StudyMate")
            }
    }
}
