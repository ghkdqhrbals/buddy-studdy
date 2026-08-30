package com.buddystudy.backend.voice.adapter.outbound.config

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorAvailabilityPort
import org.springframework.stereotype.Component

@Component
class ConfiguredVoiceTutorAvailabilityAdapter(
    private val properties: BuddyStudyProperties,
) : VoiceTutorAvailabilityPort {
    override fun isEnabled(): Boolean = properties.voiceTutor.enabled
}
