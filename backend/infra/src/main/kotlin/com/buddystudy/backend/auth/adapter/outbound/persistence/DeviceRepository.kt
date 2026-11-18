package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.auth.domain.entity.DeviceEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Component

interface DeviceRepository : CoroutineCrudRepository<DeviceEntity, Long> {
    @Modifying
    @Query("update devices set question_api_version = :version where device_id = :deviceId and user_id = :userId and question_api_version <> :version")
    suspend fun updateQuestionApiVersion(deviceId: String, userId: Long, version: Int): Int

    suspend fun findByDeviceId(deviceId: String): DeviceEntity?
    suspend fun findByInstallationKeyHash(installationKeyHash: String): DeviceEntity?
    suspend fun findAllByUserId(userId: Long): List<DeviceEntity>
}

@Component
class DevicePersistenceAdapter(
    private val repository: DeviceRepository,
) : DevicePort {
    override suspend fun updateQuestionApiVersion(deviceId: String, userId: Long, version: Int) =
        repository.updateQuestionApiVersion(deviceId, userId, version)

    override suspend fun save(entity: DeviceEntity) = repository.save(entity)
    override suspend fun findByDeviceId(deviceId: String) = repository.findByDeviceId(deviceId)
    override suspend fun findByInstallationKeyHash(installationKeyHash: String) =
        repository.findByInstallationKeyHash(installationKeyHash)
    override suspend fun findAllByUserId(userId: Long) = repository.findAllByUserId(userId)
}
