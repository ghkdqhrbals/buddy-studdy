package com.buddystuddy.backend.admin.adapter.inbound.web

import com.buddystuddy.backend.admin.application.port.inbound.AdminUseCase
import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class AdminController(
    private val admin: AdminWebPort,
) {
    @GetMapping("/openai/models")
    fun models() = admin.models()

    @GetMapping("/me/api")
    fun api(authentication: Authentication) = admin.api(authentication)

    @PostMapping("/me/api/validate")
    fun validateApi(authentication: Authentication) = admin.validateApi(authentication)
}

interface AdminWebPort {
    fun models(): Any
    fun api(authentication: Authentication): Any
    fun validateApi(authentication: Authentication): Any
}

@Component
class AdminWebAdapter(
    private val admin: AdminUseCase,
) : AdminWebPort {
    override fun models() = admin.models()

    override fun api(authentication: Authentication) = admin.apiStatus(authentication.principalOrThrow())

    override fun validateApi(authentication: Authentication) = admin.validateApi(authentication.principalOrThrow())
}
