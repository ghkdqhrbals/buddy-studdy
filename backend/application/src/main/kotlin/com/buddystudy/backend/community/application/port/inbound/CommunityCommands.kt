package com.buddystudy.backend.community.application.port.inbound

data class ReportQuestionCommand(val reason: String, val message: String = "")

data class SubmitFeedbackCommand(
    val category: String,
    val message: String,
)
