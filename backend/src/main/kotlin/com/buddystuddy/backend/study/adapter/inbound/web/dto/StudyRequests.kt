package com.buddystuddy.backend.study.adapter.inbound.web.dto

data class CreateQuestionRequest(val topic: String? = null)
data class AnswerRequest(val answer: String)
data class RecordPublicityRequest(val isPublic: Boolean)
