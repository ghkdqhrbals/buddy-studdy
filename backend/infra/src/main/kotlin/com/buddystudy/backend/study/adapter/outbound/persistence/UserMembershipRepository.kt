package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.account.domain.entity.UserMembershipEntity
import com.buddystudy.account.domain.entity.UserMembershipTierEntity
import com.buddystudy.account.domain.entity.MembershipStatus
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface UserMembershipRepository : CoroutineCrudRepository<UserMembershipEntity, Long> {
    suspend fun findFirstByUserIdAndStatusOrderByUpdatedAtDesc(
        userId: Long,
        status: MembershipStatus,
    ): UserMembershipEntity?
}

interface UserMembershipTierRepository : CoroutineCrudRepository<UserMembershipTierEntity, String> {
    suspend fun findByTierCode(tierCode: String): UserMembershipTierEntity?
}
