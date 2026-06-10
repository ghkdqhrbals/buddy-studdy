package com.buddystuddy.backend.auth.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.model.AccessResponse

interface AccessUseCase {
    fun access(principal: Principal): AccessResponse
}
