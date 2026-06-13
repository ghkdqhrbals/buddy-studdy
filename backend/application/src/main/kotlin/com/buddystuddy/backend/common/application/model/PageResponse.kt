package com.buddystuddy.backend.common.application.model

interface PageResponse {
    val totalCount: Long
    val limit: Int
    val offset: Int
}
