package com.buddystudy.backend.community.application.port.outbound

interface TopicSubscriptionPort {
    suspend fun findTopics(userId: Long): List<String>
    /** Atomically replaces only this account's subscriptions, serializing concurrent replacements. */
    suspend fun replaceTopics(userId: Long, topics: List<String>)
}
