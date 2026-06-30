package com.buddystudy.backend.common.application.model

interface PageResponse {
    val totalCount: Long
    val limit: Int
    val offset: Int
}
