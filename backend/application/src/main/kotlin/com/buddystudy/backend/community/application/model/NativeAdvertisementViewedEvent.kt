package com.buddystudy.backend.community.application.model

import java.time.Instant

data class NativeAdvertisementViewedEvent(
    val eventId: String,
    val selectionId: String,
    val userId: Long,
    val deviceId: String,
    val occurredAt: Instant,
)
