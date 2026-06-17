package com.buddystuddy.backend.study.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.study.application.model.PushTestCommand
import com.buddystuddy.backend.study.application.model.PushTestResponse

interface SendTestPushUseCase {
    fun sendTestPush(principal: Principal, command: PushTestCommand): PushTestResponse
    fun publishTestPushEvent(principal: Principal, command: PushTestCommand): PushTestResponse
}
