package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.PropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ApnsSecretMappingTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java)

    @Test
    fun `apns properties can be sourced from canonical aws secret names`(): Unit = runBlocking {
        contextRunner
            .withPropertyValues(
                "APNS_TEAM_ID=TEAM123",
                "APNS_KEY_ID=KEY123",
                "APNS_AUTH_KEY_BASE64=BASE64_PRIVATE_KEY",
                "APNS_BUNDLE_ID=io.github.ghkdqhrbals.StudyMate",
                "buddystudy.apns.team-id=\${APNS_TEAM_ID:}",
                "buddystudy.apns.key-id=\${APNS_KEY_ID:}",
                "buddystudy.apns.auth-key-p8=\${APNS_AUTH_KEY_P8:\${APNS_AUTH_KEY_BASE64:}}",
                "buddystudy.apns.bundle-id=\${APNS_BUNDLE_ID:io.github.ghkdqhrbals.StudyMate}",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(properties.apns.teamId).isEqualTo("TEAM123")
                assertThat(properties.apns.keyId).isEqualTo("KEY123")
                assertThat(properties.apns.authKeyP8).isEqualTo("BASE64_PRIVATE_KEY")
                assertThat(properties.apns.bundleId).isEqualTo("io.github.ghkdqhrbals.StudyMate")
            }
    }

    @Test
    fun `dev profile can map prefixed aws secret names`(): Unit = runBlocking {
        contextRunner
            .withPropertyValues(
                "local-secret.APNS_TEAM_ID=TEAM123",
                "local-secret.APNS_KEY_ID=KEY123",
                "local-secret.APNS_AUTH_KEY_BASE64=BASE64_PRIVATE_KEY",
                "local-secret.APNS_BUNDLE_ID=io.github.ghkdqhrbals.StudyMate",
                "buddystudy.apns.team-id=\${APNS_TEAM_ID:\${local-secret.APNS_TEAM_ID:}}",
                "buddystudy.apns.key-id=\${APNS_KEY_ID:\${local-secret.APNS_KEY_ID:}}",
                "buddystudy.apns.auth-key-p8=\${APNS_AUTH_KEY_P8:\${APNS_AUTH_KEY_BASE64:\${local-secret.APNS_AUTH_KEY_BASE64:}}}",
                "buddystudy.apns.bundle-id=\${APNS_BUNDLE_ID:\${local-secret.APNS_BUNDLE_ID:io.github.ghkdqhrbals.StudyMate}}",
            )
            .run { context ->
                val properties = context.getBean(BuddyStudyProperties::class.java)

                assertThat(properties.apns.teamId).isEqualTo("TEAM123")
                assertThat(properties.apns.keyId).isEqualTo("KEY123")
                assertThat(properties.apns.authKeyP8).isEqualTo("BASE64_PRIVATE_KEY")
                assertThat(properties.apns.bundleId).isEqualTo("io.github.ghkdqhrbals.StudyMate")
            }
    }
}
