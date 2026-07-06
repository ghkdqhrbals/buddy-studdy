package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.account.domain.entity.UserMembershipEntity
import com.buddystudy.account.domain.entity.UserMembershipTierEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserMembershipRepository : JpaRepository<UserMembershipEntity, Long> {
    fun findFirstByUserIdAndStatusOrderByUpdatedAtDesc(userId: Long, status: String): UserMembershipEntity?
}

interface UserMembershipTierRepository : JpaRepository<UserMembershipTierEntity, String> {
    fun findByTierCode(tierCode: String): UserMembershipTierEntity?
}
