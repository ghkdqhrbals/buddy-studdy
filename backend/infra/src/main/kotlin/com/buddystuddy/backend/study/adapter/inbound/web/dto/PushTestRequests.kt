package com.buddystuddy.backend.study.adapter.inbound.web.dto

data class PushTestRequest(
    val title: String? = null,
    val body: String? = null,
    val topic: String? = null,
    val recordId: String? = null,
    val sound: String? = null,
    val deepLink: String? = null,
)
