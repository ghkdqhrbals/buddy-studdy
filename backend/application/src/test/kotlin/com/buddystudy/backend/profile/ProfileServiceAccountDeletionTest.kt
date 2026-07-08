package com.buddystudy.backend.profile

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.permission.Roles
import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.service.AccountSessionManager
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.profile.application.service.ProfileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class ProfileServiceAccountDeletionTest {
    private val users = InMemoryUserPort()
    private val devices = InMemoryDevicePort()
    private val userDevices = InMemoryUserDevicePort()
    private val roles = InMemoryRoleAssignmentPort()
    private val deletion = CapturingAccountDeletionPort(users, userDevices, devices)
    private val properties = BuddyStudyProperties().apply {
        auth.jwtSecret = "test-jwt-secret"
    }
    private val service = ProfileService(
        users = users,
        devices = devices,
        sessions = AccountSessionManager(users, devices, userDevices),
        roles = roles,
        tokenService = TokenProvider(properties),
        accountDeletion = deletion,
    )

    @Test
    fun `update profile returns the requested avatar symbol and color`() {
        val activeUser = users.save(
            UserEntity(
                provider = "GOOGLE",
                providerId = "google-subject",
                email = "user@example.com",
                status = "ACTIVE",
                displayName = "Jamma",
                avatarSymbolName = "pixel-cat-laptop",
                avatarColorSeed = "avatar-color-mint",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )

        val response = service.updateProfile(
            Principal(
                userId = activeUser.id,
                deviceId = "dev-current",
                sessionId = 1,
                anonymous = false,
                status = "ACTIVE",
            ),
            com.buddystudy.backend.profile.application.port.inbound.ProfileUpdateCommand(
                displayName = "Jamma",
                bio = "",
                avatarSymbolName = "pixel-cat-geek",
                avatarColorSeed = "avatar-color-teal",
            )
        )

        assertThat(response.avatarSymbolName).isEqualTo("pixel-cat-geek")
        assertThat(response.avatarColorSeed).isEqualTo("avatar-color-teal")
        assertThat(users.findById(activeUser.id).orElseThrow().avatarSymbolName).isEqualTo("pixel-cat-geek")
    }

    @Test
    fun `withdraw deletes member data and reconnects current device to a new anonymous user`() {
        val activeUser = users.save(
            UserEntity(
                provider = "GOOGLE",
                providerId = "google-subject",
                email = "user@example.com",
                status = "ACTIVE",
                displayName = "Jamma",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        devices.save(
            DeviceEntity(
                deviceId = "dev-current",
                clientSecretHash = "secret",
                userId = activeUser.id,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                lastSeenAt = Instant.now(),
            )
        )
        userDevices.save(
            UserDeviceEntity(
                userId = activeUser.id,
                deviceId = "dev-current",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                lastSeenAt = Instant.now(),
            )
        )

        val response = service.withdrawProfile(
            Principal(
                userId = activeUser.id,
                deviceId = "dev-current",
                sessionId = 1,
                anonymous = false,
                status = "ACTIVE",
            )
        )

        assertThat(response.accessToken).isNotBlank()
        assertThat(deletion.deleted).containsExactly(DeletedAccount(activeUser.id, "dev-current"))
        assertThat(users.findByProviderAndProviderId("GOOGLE", "google-subject")).isNull()
        val anonymousUserId = devices.findByDeviceId("dev-current")?.userId
        assertThat(anonymousUserId).isNotNull()
        assertThat(anonymousUserId).isNotEqualTo(activeUser.id)
        assertThat(users.findById(anonymousUserId!!).orElseThrow().status).isEqualTo("ANONYMOUS")
        assertThat(roles.grants).contains(anonymousUserId to Roles.ANONYMOUS_USER)
        assertThat(userDevices.findActiveByUserId(activeUser.id)).isEmpty()
        assertThat(userDevices.findAllByUserId(activeUser.id)).isEmpty()
        assertThat(userDevices.findActiveByUserId(anonymousUserId).map { it.deviceId }).containsExactly("dev-current")
    }

    private data class DeletedAccount(val userId: Long, val currentDeviceId: String)

    private class CapturingAccountDeletionPort(
        private val users: InMemoryUserPort,
        private val userDevices: InMemoryUserDevicePort,
        private val devices: InMemoryDevicePort,
    ) : AccountDeletionPort {
        val deleted = mutableListOf<DeletedAccount>()

        override fun deleteAccountData(userId: Long, currentDeviceId: String, now: Instant) {
            deleted += DeletedAccount(userId, currentDeviceId)
            userDevices.deleteAllForUser(userId)
            devices.detachUser(userId, now)
            users.deleteById(userId)
        }
    }

    private class InMemoryUserPort : UserPort {
        private val users = linkedMapOf<Long, UserEntity>()
        private var nextId = 1L

        override fun save(entity: UserEntity): UserEntity {
            if (entity.id == 0L) entity.id = nextId++
            users[entity.id] = entity
            return entity
        }

        override fun findById(id: Long): Optional<UserEntity> = Optional.ofNullable(users[id])

        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> =
            ids.mapNotNull { users[it] }.toMutableList()

        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? =
            users.values.firstOrNull { it.provider == provider && it.providerId == providerId }

        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? =
            users.values.firstOrNull { it.provider == provider && it.email == email }

        fun deleteById(id: Long) {
            users.remove(id)
        }
    }

    private class InMemoryDevicePort : DevicePort {
        private val devices = linkedMapOf<String, DeviceEntity>()
        private var nextId = 1L

        override fun save(entity: DeviceEntity): DeviceEntity {
            if (entity.id == 0L) entity.id = nextId++
            devices[entity.deviceId] = entity
            return entity
        }

        override fun findByDeviceId(deviceId: String): DeviceEntity? = devices[deviceId]

        override fun findAllByUserId(userId: Long): List<DeviceEntity> =
            devices.values.filter { it.userId == userId }

        fun detachUser(userId: Long, now: Instant) {
            devices.values
                .filter { it.userId == userId }
                .forEach {
                    it.userId = null
                    it.updatedAt = now
                }
        }
    }

    private class InMemoryUserDevicePort : UserDevicePort {
        private val sessions = linkedMapOf<Long, UserDeviceEntity>()
        private var nextId = 1L

        override fun save(entity: UserDeviceEntity): UserDeviceEntity {
            if (entity.id == 0L) entity.id = nextId++
            sessions[entity.id] = entity
            return entity
        }

        override fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity? =
            sessions.values.firstOrNull { it.userId == userId && it.deviceId == deviceId }

        override fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity? =
            sessions[id]?.takeIf { it.userId == userId }

        override fun findActiveByUserId(userId: Long): List<UserDeviceEntity> =
            sessions.values.filter { it.userId == userId && it.isActive() }

        fun findAllByUserId(userId: Long): List<UserDeviceEntity> =
            sessions.values.filter { it.userId == userId }

        override fun hasActiveSession(userId: Long, deviceId: String): Boolean =
            sessions.values.any { it.userId == userId && it.deviceId == deviceId && it.isActive() }

        fun deleteAllForUser(userId: Long) {
            val ids = sessions.values.filter { it.userId == userId }.map { it.id }
            ids.forEach { sessions.remove(it) }
        }
    }

    private class InMemoryRoleAssignmentPort : RoleAssignmentPort {
        val grants = mutableListOf<Pair<Long, String>>()

        override fun grantRoleIfMissing(userId: Long, roleCode: String) {
            grants += userId to roleCode
        }

        override fun countUserRoles(userId: Long, roleCode: String): Long =
            grants.count { it == userId to roleCode }.toLong()
    }
}
