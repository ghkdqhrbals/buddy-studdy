package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication

fun Authentication.principalOrThrow(): Principal =
    principal as? Principal
        ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED, "Access token is required.")

fun Authentication?.optionalPrincipal(): Principal? = this?.principal as? Principal
