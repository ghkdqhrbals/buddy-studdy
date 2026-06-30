package com.buddystudy.backend.auth.application.model

import java.time.Instant

data class AccessUserResponse(
    val id: Long,
    val status: String,
    val displayName: String,
    val createdAt: Instant,
)

data class PageAccessResponse(
    val home: Boolean,
    val publicQuestions: Boolean,
    val myStudies: Boolean,
    val studyRoom: Boolean,
    val records: Boolean,
    val stats: Boolean,
    val profile: Boolean,
    val developer: Boolean,
    val admin: Boolean,
)

data class AccessResponse(
    val user: AccessUserResponse,
    val pageAccess: PageAccessResponse,
)
