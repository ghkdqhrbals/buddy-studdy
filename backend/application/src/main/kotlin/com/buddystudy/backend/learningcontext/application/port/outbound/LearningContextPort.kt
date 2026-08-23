package com.buddystudy.backend.learningcontext.application.port.outbound

import com.buddystudy.learningcontext.domain.entity.UserLearningContextEntity

interface LearningContextPort {
    suspend fun findByUserId(userId: Long): UserLearningContextEntity?
    suspend fun save(entity: UserLearningContextEntity): UserLearningContextEntity
    suspend fun deleteByUserId(userId: Long): Long
}
