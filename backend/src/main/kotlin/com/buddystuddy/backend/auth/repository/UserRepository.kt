package com.buddystuddy.backend.auth.repository

import com.buddystuddy.backend.domain.UserEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity?
    fun findByEmailAndProvider(email: String, provider: String): UserEntity?
}
