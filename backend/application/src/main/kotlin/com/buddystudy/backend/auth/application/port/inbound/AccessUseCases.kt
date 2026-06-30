package com.buddystudy.backend.auth.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.AccessResponse

interface AccessUseCase {
    fun access(principal: Principal): AccessResponse
}
