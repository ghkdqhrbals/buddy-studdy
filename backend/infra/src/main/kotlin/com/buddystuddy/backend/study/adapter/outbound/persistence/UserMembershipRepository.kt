package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.account.domain.entity.UserMembershipEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserMembershipRepository : JpaRepository<UserMembershipEntity, Long> {
    fun findFirstByUserIdAndStatusOrderByUpdatedAtDesc(userId: Long, status: String): UserMembershipEntity?
}
