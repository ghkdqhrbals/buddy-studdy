package com.buddystuddy.backend.settings.adapter.inbound.web

import com.buddystuddy.backend.auth.PrincipalResolver
import com.buddystuddy.backend.settings.adapter.inbound.web.dto.ScheduleRequest
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystuddy.backend.settings.application.port.inbound.SettingsUseCase
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
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
    fun schedule(@Valid @RequestBody body: ScheduleRequest, request: HttpServletRequest) =
        settings.schedule(body, request)

    @GetMapping("/me/settings")
    fun settings(request: HttpServletRequest) = settings.settings(request)
}

interface SettingsWebPort {
    fun schedule(body: ScheduleRequest, request: HttpServletRequest): Any
    fun settings(request: HttpServletRequest): Any
}

@Component
class SettingsWebAdapter(
    private val settings: SettingsUseCase,
    private val principals: PrincipalResolver,
) : SettingsWebPort {
    override fun schedule(body: ScheduleRequest, request: HttpServletRequest) =
        settings.upsertSchedule(principals.authenticate(request), body.toCommand())

    override fun settings(request: HttpServletRequest) = settings.settings(principals.authenticate(request))
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
