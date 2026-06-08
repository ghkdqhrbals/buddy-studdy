package com.buddystuddy.backend.admin.adapter.inbound.web

import com.buddystuddy.backend.admin.application.port.inbound.AdminUseCase
import com.buddystuddy.backend.auth.PrincipalResolver
import jakarta.servlet.http.HttpServletRequest
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
    fun api(request: HttpServletRequest) = admin.api(request)

    @PostMapping("/me/api/validate")
    fun validateApi(request: HttpServletRequest) = admin.validateApi(request)
}

interface AdminWebPort {
    fun models(): Any
    fun api(request: HttpServletRequest): Any
    fun validateApi(request: HttpServletRequest): Any
}

@Component
class AdminWebAdapter(
    private val admin: AdminUseCase,
    private val principals: PrincipalResolver,
) : AdminWebPort {
    override fun models() = admin.models()

    override fun api(request: HttpServletRequest) = admin.apiStatus(principals.authenticate(request))

    override fun validateApi(request: HttpServletRequest) = admin.validateApi(principals.authenticate(request))
}
