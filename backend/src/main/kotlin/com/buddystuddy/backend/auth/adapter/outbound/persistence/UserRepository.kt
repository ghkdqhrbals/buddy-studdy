package com.buddystuddy.backend.auth.adapter.outbound.persistence

import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.domain.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<UserEntity, Long>, UserPort {
    override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity?
    override fun findByEmailAndProvider(email: String, provider: String): UserEntity?

    @Query("select u from UserEntity u where u.id = :id")
    fun findEntityById(@Param("id") id: Long): UserEntity?
}
