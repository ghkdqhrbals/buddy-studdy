package com.buddystudy.backend.community.adapter.inbound.web.dto

data class ReportQuestionRequest(val reason: String, val message: String = "")
data class CommunityCommentRequest(val body: String)
