package com.buddystudy.backend.test

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.externalapi.adapter.outbound.history.ExternalApiHistoryRecorder
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.port.inbound.ExternalApiCallHistoryUseCase
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

fun testExternalApiHistoryRecorder(): ExternalApiHistoryRecorder {
    val mapper = jacksonObjectMapper().findAndRegisterModules()
    return ExternalApiHistoryRecorder(
        history = object : ExternalApiCallHistoryUseCase {
            override suspend fun start(command: StartExternalApiCallCommand) = Unit
            override suspend fun finish(command: FinishExternalApiCallCommand) = Unit
        },
        redactor = SensitiveDataRedactor(mapper),
        objectMapper = mapper,
    )
}
