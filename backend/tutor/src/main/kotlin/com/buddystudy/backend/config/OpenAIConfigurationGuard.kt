package com.buddystudy.backend.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
class OpenAIConfigurationGuard(
    private val properties: BuddyStudyProperties,
    private val environment: Environment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of("prod", "production"))) return

        val userContentApiKey = properties.openai.userContentApiKey.trim()
        val systemApiKey = properties.openai.systemApiKey.trim()

        require(userContentApiKey.isNotEmpty()) {
            "OPENAI_API_KEY_USER is required in production."
        }
        require(systemApiKey.isNotEmpty()) {
            "OPENAI_API_KEY_SYSTEM is required in production."
        }
        require(userContentApiKey != systemApiKey) {
            "OPENAI_API_KEY_USER and OPENAI_API_KEY_SYSTEM must be different in production."
        }
    }
}
