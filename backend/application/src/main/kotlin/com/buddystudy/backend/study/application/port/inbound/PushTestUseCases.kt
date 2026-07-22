package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.study.application.model.PushTestCommand
import com.buddystudy.backend.study.application.model.PushTestResponse

interface SendTestPushUseCase {
    suspend fun sendTestPush(principal: Principal, command: PushTestCommand): PushTestResponse
    suspend fun publishTestPushEvent(principal: Principal, command: PushTestCommand): PushTestResponse
}
