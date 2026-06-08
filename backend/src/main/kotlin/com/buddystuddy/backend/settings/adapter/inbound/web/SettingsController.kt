package com.buddystuddy.backend.settings.adapter.inbound.web

import com.buddystuddy.backend.auth.PrincipalService
import com.buddystuddy.backend.dto.ScheduleRequest
import com.buddystuddy.backend.settings.application.port.inbound.SettingsUseCase
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class SettingsController(
    private val settings: SettingsUseCase,
    private val principals: PrincipalService,
) {
    @PutMapping("/me/schedule", "/me/settings")
    fun schedule(@Valid @RequestBody body: ScheduleRequest, request: HttpServletRequest) =
        settings.upsertSchedule(principals.authenticate(request), body)

    @GetMapping("/me/settings")
    fun settings(request: HttpServletRequest) = settings.settings(principals.authenticate(request))
}
