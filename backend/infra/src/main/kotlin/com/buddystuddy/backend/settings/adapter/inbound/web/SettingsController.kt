package com.buddystuddy.backend.settings.adapter.inbound.web

import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.settings.adapter.inbound.web.dto.ScheduleRequest
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystuddy.backend.settings.application.port.inbound.SettingsUseCase
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class SettingsController(
    private val settings: SettingsWebPort,
) {
    @PutMapping("/me/schedule", "/me/settings")
    fun schedule(@Valid @RequestBody body: ScheduleRequest, authentication: Authentication) =
        settings.schedule(body, authentication)

    @GetMapping("/me/settings")
    fun settings(authentication: Authentication) = settings.settings(authentication)
}

interface SettingsWebPort {
    fun schedule(body: ScheduleRequest, authentication: Authentication): Any
    fun settings(authentication: Authentication): Any
}

@Component
class SettingsWebAdapter(
    private val settings: SettingsUseCase,
) : SettingsWebPort {
    override fun schedule(body: ScheduleRequest, authentication: Authentication) =
        settings.upsertSchedule(authentication.principalOrThrow(), body.toCommand())

    override fun settings(authentication: Authentication) = settings.settings(authentication.principalOrThrow())
}

private fun ScheduleRequest.toCommand() = ScheduleCommand(
    topic = topic,
    difficultyLevel = difficultyLevel,
    intervalMinutes = intervalMinutes,
    enabled = enabled,
    openaiApiKey = openaiApiKey,
    notificationSound = notificationSound,
    customPrompt = customPrompt,
    appLanguage = appLanguage,
    openaiModel = openaiModel,
    maxHistoryCount = maxHistoryCount,
    isQuestionPublic = isQuestionPublic,
    schedules = schedules?.map {
        ScheduleItemCommand(
            topic = it.topic,
            difficultyLevel = it.difficultyLevel,
            customPrompt = it.customPrompt,
            openaiModel = it.openaiModel,
        )
    },
)
