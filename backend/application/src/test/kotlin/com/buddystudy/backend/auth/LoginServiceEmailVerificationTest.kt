package com.buddystudy.backend.auth

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import com.buddystudy.backend.auth.application.port.inbound.EmailLoginCommand
import com.buddystudy.backend.auth.application.port.inbound.PushTokenCommand
import com.buddystudy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationCodePort
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationSenderPort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.service.AccountSessionManager
import com.buddystudy.backend.auth.application.service.LoginService
import com.buddystudy.backend.auth.application.service.RandomTokenGenerator
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional

class LoginServiceEmailVerificationTest {
    private val users = InMemoryUserPort()
    private val devices = InMemoryDevicePort()
    private val userDevices = InMemoryUserDevicePort()
    private val emailCodes = CapturingEmailCodePort()
    private val emailSender = CapturingEmailSender()
    private val roles = InMemoryRoleAssignmentPort()
    private val properties = BuddyStudyProperties().apply {
        auth.jwtSecret = "test-jwt-secret"
        email.verificationTtlSeconds = 180
    }
    private val login = LoginService(
        properties = properties,
        users = users,
        devices = devices,
        tokenService = TokenProvider(properties),
        sessions = AccountSessionManager(users, devices, userDevices),
        tokens = RandomTokenGenerator(),
        emailCodes = emailCodes,
        emailSender = emailSender,
        roles = roles,
    )

    @Test
    fun `email code is generated stored and sent`() {
        val response = login.emailCode(" Tester@Example.COM ")

        assertThat(response.email).isEqualTo("tester@example.com")
        assertThat(response.expiresInSeconds).isEqualTo(180)
        assertThat(emailCodes.savedEmail).isEqualTo("tester@example.com")
        assertThat(emailCodes.savedCode).matches("\\d{6}")
        assertThat(emailCodes.savedTtl).isEqualTo(Duration.ofSeconds(180))
        assertThat(emailSender.sentEmail).isEqualTo("tester@example.com")
        assertThat(emailSender.sentCode).isEqualTo(emailCodes.savedCode)
    }

    @Test
    fun `device token reissue reuses authenticated user status`() {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        users.findByIdCalls = 0

        val response = login.token(device.deviceId, device.clientSecret)

        assertThat(response.accessToken).isNotBlank()
        assertThat(users.findByIdCalls).isEqualTo(1)
    }

    @Test
    fun `push token update reuses principal user status`() {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        users.findByIdCalls = 0

        login.updatePushToken(
            Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true, status = "ANONYMOUS"),
            PushTokenCommand(apnsToken = "apns-token", apnsEnvironment = "sandbox"),
        )

        assertThat(devices.findByDeviceId(device.deviceId)?.apnsToken).isEqualTo("apns-token")
        assertThat(users.findByIdCalls).isZero()
    }

    @Test
    fun `push token update keeps only current user device token and login keeps one active session`() {
        val device = login.register(RegisterDeviceCommand(apnsToken = "first-token", language = "ko"))
        devices.save(
            DeviceEntity(
                deviceId = "dev-old",
                userId = 1,
                clientSecretHash = "old-secret",
                apnsToken = "old-token",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                lastSeenAt = Instant.now(),
            )
        )
        userDevices.save(
            UserDeviceEntity(
                userId = 1,
                deviceId = "dev-old",
                lastLoginAt = Instant.now(),
                lastSeenAt = Instant.now(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )

        login.updatePushToken(
            Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true, status = "ANONYMOUS"),
            PushTokenCommand(apnsToken = "current-token", apnsEnvironment = "sandbox"),
        )
        login.token(device.deviceId, device.clientSecret)

        assertThat(devices.findByDeviceId(device.deviceId)?.apnsToken).isEqualTo("current-token")
        assertThat(devices.findByDeviceId("dev-old")?.apnsToken).isBlank()
        assertThat(userDevices.findActiveByUserId(1).map { it.deviceId }).containsExactly(device.deviceId)
    }

    @Test
    fun `logout marks current device session as logged out and clears active user from device`() {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        val principal = Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true, status = "ANONYMOUS")

        login.logout(principal)

        val session = userDevices.findByIdAndUserId(1, 1)
        assertThat(session?.loggedOutAt).isNotNull()
        assertThat(devices.findByDeviceId(device.deviceId)?.userId).isNull()
    }

    @Test
    fun `device token reissue after logout reuses existing anonymous user`() {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        val principal = Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true, status = "ANONYMOUS")
        login.logout(principal)

        val response = login.token(device.deviceId, device.clientSecret)

        assertThat(response.accessToken).isNotBlank()
        assertThat(devices.findByDeviceId(device.deviceId)?.userId).isEqualTo(1)
        assertThat(users.countByProviderAndProviderId("ANONYMOUS", device.deviceId)).isEqualTo(1)
        assertThat(roles.grantRoleCallsFor(1, "ANONYMOUS_USER")).isEqualTo(2)
    }

    @Test
    fun `logged in devices returns active sessions only`() {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", platform = "ios", language = "ko", timezone = "Asia/Seoul"))
        val principal = Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true, status = "ANONYMOUS")

        val response = login.loggedInDevices(principal)

        assertThat(response.devices).hasSize(1)
        assertThat(response.devices.first().deviceId).isEqualTo(device.deviceId)
        assertThat(response.devices.first().current).isTrue()
        assertThat(response.devices.first().platform).isEqualTo("ios")
    }

    @Test
    fun `new email user is created only when verification code matches`() {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        login.emailCode("new@example.com")

        val response = login.emailLogin(
            Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true),
            EmailLoginCommand(email = "new@example.com", password = "password123", verificationCode = emailCodes.savedCode),
        )

        val user = users.findByEmailAndProvider("new@example.com", "EMAIL")
        assertThat(user).isNotNull
        assertThat(user?.status).isEqualTo("PENDING_TERMS")
        assertThat(user?.passwordHash).isEqualTo(sha256("password123"))
        assertThat(response.profile.displayName).isEqualTo("new")
        assertThat(emailCodes.consumed).isTrue()
        assertThat(roles.grantRoleCalls).isEqualTo(2)
        assertThat(roles.grantRoleCallsFor(user!!.id, "REGISTERED_USER")).isEqualTo(1)
    }

    @Test
    fun `new email user is rejected when verification code is invalid`() {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        login.emailCode("new@example.com")

        assertThatThrownBy {
            login.emailLogin(
                Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true),
                EmailLoginCommand(email = "new@example.com", password = "password123", verificationCode = "000000"),
            )
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED)

        assertThat(users.findByEmailAndProvider("new@example.com", "EMAIL")).isNull()
    }

    private class CapturingEmailCodePort : EmailVerificationCodePort {
        lateinit var savedEmail: String
        lateinit var savedCode: String
        lateinit var savedTtl: Duration
        var consumed = false
            private set

        override fun save(email: String, code: String, ttl: Duration) {
            savedEmail = email
            savedCode = code
            savedTtl = ttl
            consumed = false
        }

        override fun consume(email: String, code: String): Boolean {
            if (email != savedEmail || code != savedCode || consumed) {
                return false
            }
            consumed = true
            return true
        }
    }

    private class CapturingEmailSender : EmailVerificationSenderPort {
        lateinit var sentEmail: String
        lateinit var sentCode: String

        override fun send(email: String, code: String, ttl: Duration) {
            sentEmail = email
            sentCode = code
        }
    }

    private class InMemoryUserPort : UserPort {
        private val users = linkedMapOf<Long, UserEntity>()
        private var nextId = 1L
        var findByIdCalls = 0

        override fun save(entity: UserEntity): UserEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            users[entity.id] = entity
            return entity
        }

        override fun findById(id: Long): Optional<UserEntity> {
            findByIdCalls += 1
            return Optional.ofNullable(users[id])
        }
        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = ids.mapNotNull { users[it] }.toMutableList()
        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? =
            users.values.firstOrNull { it.provider == provider && it.providerId == providerId }

        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? =
            users.values.firstOrNull { it.email == email && it.provider == provider }

        fun countByProviderAndProviderId(provider: String, providerId: String): Int =
            users.values.count { it.provider == provider && it.providerId == providerId }
    }

    private class InMemoryDevicePort : DevicePort {
        private val devices = linkedMapOf<String, DeviceEntity>()
        private var nextId = 1L

        override fun save(entity: DeviceEntity): DeviceEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            devices[entity.deviceId] = entity
            return entity
        }

        override fun findByDeviceId(deviceId: String): DeviceEntity? = devices[deviceId]
        override fun findAllByUserId(userId: Long): List<DeviceEntity> =
            devices.values.filter { it.userId == userId }
    }

    private class InMemoryUserDevicePort : UserDevicePort {
        private val sessions = linkedMapOf<Long, UserDeviceEntity>()
        private var nextId = 1L

        override fun save(entity: UserDeviceEntity): UserDeviceEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            sessions[entity.id] = entity
            return entity
        }

        override fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity? =
            sessions.values.firstOrNull { it.userId == userId && it.deviceId == deviceId }

        override fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity? =
            sessions[id]?.takeIf { it.userId == userId }

        override fun findActiveByUserId(userId: Long): List<UserDeviceEntity> =
            sessions.values.filter { it.userId == userId && it.isActive() }

        override fun hasActiveSession(userId: Long, deviceId: String): Boolean =
            sessions.values.any { it.userId == userId && it.deviceId == deviceId && it.isActive() }
    }

    private class InMemoryRoleAssignmentPort : RoleAssignmentPort {
        private val roles = mutableSetOf<Pair<Long, String>>()
        private val calls = mutableListOf<Pair<Long, String>>()
        val grantRoleCalls: Int
            get() = calls.size

        override fun grantRoleIfMissing(userId: Long, roleCode: String) {
            calls += userId to roleCode
            roles += userId to roleCode
        }

        override fun countUserRoles(userId: Long, roleCode: String): Long =
            if (userId to roleCode in roles) 1 else 0

        fun grantRoleCallsFor(userId: Long, roleCode: String): Int =
            calls.count { it == userId to roleCode }
    }
}
