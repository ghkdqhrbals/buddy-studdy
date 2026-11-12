package com.buddystudy.backend.study.application.port.outbound

interface QuestionDeliveryCapabilityPort {
    suspend fun supportsMultipleChoice(userId: Long): Boolean
}

object LegacyQuestionDeliveryCapability : QuestionDeliveryCapabilityPort {
    override suspend fun supportsMultipleChoice(userId: Long): Boolean = false
}
