package com.buddystuddy.backend.admin.adapter.inbound.web

import com.buddystuddy.backend.admin.application.port.inbound.AdminUseCase
import com.buddystuddy.backend.auth.PrincipalResolver
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class AdminController(
    private val admin: AdminUseCase,
    private val principals: PrincipalResolver,
) {
    @GetMapping("/openai/models")
    fun models() = admin.models()

    @GetMapping("/me/api")
    fun api(request: HttpServletRequest) = admin.apiStatus(principals.authenticate(request))

    @PostMapping("/me/api/validate")
    fun validateApi(request: HttpServletRequest) = admin.validateApi(principals.authenticate(request))
}
