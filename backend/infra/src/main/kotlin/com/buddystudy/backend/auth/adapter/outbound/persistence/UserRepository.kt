package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.account.domain.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<UserEntity, Long>, UserPort {
    override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity?
    override fun findByEmailAndProvider(email: String, provider: String): UserEntity?
}
