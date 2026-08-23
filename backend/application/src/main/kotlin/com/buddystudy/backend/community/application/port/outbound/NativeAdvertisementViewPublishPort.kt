package com.buddystudy.backend.community.application.port.outbound

import com.buddystudy.backend.community.application.model.NativeAdvertisementViewedEvent

interface NativeAdvertisementViewPublishPort {
    suspend fun publish(event: NativeAdvertisementViewedEvent): Boolean
}
