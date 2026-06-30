package com.buddystudy.backend.settings.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.settings.adapter.inbound.web.dto.ScheduleRequest
import com.buddystudy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystudy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystudy.backend.settings.application.port.inbound.SettingsUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Settings", description = "Authenticated study settings and schedule configuration APIs.")
@RequirePermission(Permissions.PROFILE_READ)
class SettingsController(
    private val settings: SettingsWebPort,
) {
    @Operation(
        summary = "Save study settings",
        description = "Stores the user's OpenAI API key, question interval, notification sound, app language, public-question preference, and per-study topic/level/model settings. The OpenAI key is encrypted by the backend before persistence.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Settings saved and returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @PutMapping("/settings")
    @RequirePermission(Permissions.PROFILE_UPDATE)
    fun schedule(@Valid @RequestBody body: ScheduleRequest, authentication: Authentication) =
        settings.schedule(body, authentication)

    @Operation(summary = "Fetch study settings", description = "Returns the authenticated user's saved study settings, including per-study rooms and public-question preference. Secret values are returned only in the form intended for the app.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Settings returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @GetMapping("/settings")
    fun settings(authentication: Authentication) = settings.settings(authentication)

    @Operation(summary = "Fetch one study room settings", description = "Returns settings for a single study room. Use this instead of the old broad startup settings state when editing one study.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Study settings returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Study settings not found."),
    )
    @GetMapping("/studies/{studyId}/settings")
    fun studySettings(
        @PathVariable studyId: Long,
        authentication: Authentication,
    ) = settings.studySettings(studyId, authentication)

    @Operation(summary = "Save one study room settings", description = "Updates settings for a single study room. Global interval and OpenAI key fields are accepted for compatibility but the route is scoped to one study room.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Study settings saved."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Study settings not found."),
    )
    @PutMapping("/studies/{studyId}/settings")
    @RequirePermission(Permissions.STUDY_UPDATE)
    fun saveStudySettings(
        @PathVariable studyId: Long,
        @Valid @RequestBody body: ScheduleRequest,
        authentication: Authentication,
    ) = settings.studySettings(studyId, body, authentication)
}

interface SettingsWebPort {
    fun schedule(body: ScheduleRequest, authentication: Authentication): Any
    fun settings(authentication: Authentication): Any
    fun studySettings(studyId: Long, authentication: Authentication): Any
    fun studySettings(studyId: Long, body: ScheduleRequest, authentication: Authentication): Any
}

@Component
class SettingsWebAdapter(
    private val settings: SettingsUseCase,
) : SettingsWebPort {
    override fun schedule(body: ScheduleRequest, authentication: Authentication) =
        settings.upsertSchedule(authentication.principalOrThrow(), body.toCommand())

    override fun settings(authentication: Authentication) = settings.settings(authentication.principalOrThrow())

    override fun studySettings(studyId: Long, authentication: Authentication) =
        settings.studySettings(authentication.principalOrThrow(), studyId)

    override fun studySettings(studyId: Long, body: ScheduleRequest, authentication: Authentication) =
        settings.upsertStudySettings(authentication.principalOrThrow(), studyId, body.toCommand())
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
    schedules = schedules?.map {
        ScheduleItemCommand(
            topic = it.topic,
            difficultyLevel = it.difficultyLevel,
            customPrompt = it.customPrompt,
            openaiModel = it.openaiModel,
        )
    },
)
