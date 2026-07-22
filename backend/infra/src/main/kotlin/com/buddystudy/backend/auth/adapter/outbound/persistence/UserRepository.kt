package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.account.domain.entity.UserEntity
import kotlinx.coroutines.flow.toList
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component

interface UserRepository : CoroutineCrudRepository<UserEntity, Long> {
    suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity?
    suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity?
}

@Component
class UserPersistenceAdapter(
    private val repository: UserRepository,
) : UserPort {
    override suspend fun save(entity: UserEntity) = repository.save(entity)
    override suspend fun findById(id: Long) = repository.findById(id)
    override suspend fun findAllById(ids: Iterable<Long>) = repository.findAllById(ids).toList()
    override suspend fun findByProviderAndProviderId(provider: String, providerId: String) =
        repository.findByProviderAndProviderId(provider, providerId)
    override suspend fun findByEmailAndProvider(email: String, provider: String) =
        repository.findByEmailAndProvider(email, provider)
}
