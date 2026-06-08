package com.buddystuddy.backend.community.application.port.inbound

data class ReportQuestionCommand(val reason: String, val message: String = "")
