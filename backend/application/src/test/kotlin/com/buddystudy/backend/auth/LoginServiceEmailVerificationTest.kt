package com.buddystudy.backend.auth

import kotlinx.coroutines.runBlocking

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import com.buddystudy.backend.auth.application.port.inbound.EmailLoginCommand
import com.buddystudy.backend.auth.application.port.inbound.PushTokenCommand
import com.buddystudy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationCodePort
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationSenderPort
import com.buddystudy.backend.auth.application.port.outbound.GoogleIdentity
import com.buddystudy.backend.auth.application.port.outbound.GoogleIdentityPort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.service.AccountSessionManager
import com.buddystudy.backend.auth.application.service.AuthenticatedLoginManager
import com.buddystudy.backend.auth.application.service.LoginService
import com.buddystudy.backend.auth.application.service.RandomDisplayNameProvider
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
    private val googleIdentities = StubGoogleIdentityPort()
    private val properties = BuddyStudyProperties().apply {
        auth.jwtSecret = "test-jwt-secret"
        email.verificationTtlSeconds = 180
    }
    private val sessions = AccountSessionManager(users, devices, userDevices)
    private val tokenProvider = TokenProvider(properties)
    private val login = LoginService(
        properties = properties,
        users = users,
        devices = devices,
        tokenService = tokenProvider,
        sessions = sessions,
        tokens = RandomTokenGenerator(),
        emailCodes = emailCodes,
        emailSender = emailSender,
        roles = roles,
        googleIdentities = googleIdentities,
        authenticatedLogins = AuthenticatedLoginManager(
            users,
            devices,
            sessions,
            roles,
            tokenProvider,
            RandomDisplayNameProvider(),
        ),
    )

    @Test
    fun `email code is generated stored and sent`(): Unit = runBlocking {
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
    fun `device token reissue reuses authenticated user status`(): Unit = runBlocking {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        users.findByIdCalls = 0

        val response = login.token(device.deviceId, device.clientSecret)

        assertThat(response.accessToken).isNotBlank()
        assertThat(users.findByIdCalls).isEqualTo(1)
    }

    @Test
    fun `device registration is idempotent for the same installation`(): Unit = runBlocking {
        val installationId = "installation-id-that-is-stable-for-this-test"

        val first = login.register(
            RegisterDeviceCommand(
                installationId = installationId,
                apnsToken = "first-token",
                language = "ko",
            )
        )
        val second = login.register(
            RegisterDeviceCommand(
                installationId = installationId,
                apnsToken = "second-token",
                language = "en",
            )
        )

        assertThat(second.deviceId).isEqualTo(first.deviceId)
        assertThat(second.clientSecret).isNotEqualTo(first.clientSecret)
        assertThat(devices.count()).isEqualTo(1)
        assertThat(users.countByProviderAndProviderId("ANONYMOUS", first.deviceId)).isEqualTo(1)
        assertThat(devices.findByDeviceId(first.deviceId)?.apnsToken).isEqualTo("second-token")
        assertThat(devices.findByDeviceId(first.deviceId)?.language).isEqualTo("en")
        assertThat(login.token(second.deviceId, second.clientSecret).accessToken).isNotBlank()
    }

    @Test
    fun `push token update reuses principal user status`(): Unit = runBlocking {
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
    fun `push token update keeps only current user device token and login keeps one active session`(): Unit = runBlocking {
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
    fun `logout marks current device session as logged out and clears active user from device`(): Unit = runBlocking {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        val principal = Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true, status = "ANONYMOUS")

        login.logout(principal)

        val session = userDevices.findByIdAndUserId(1, 1)
        assertThat(session?.loggedOutAt).isNotNull()
        assertThat(devices.findByDeviceId(device.deviceId)?.userId).isNull()
    }

    @Test
    fun `device token reissue after logout reuses existing anonymous user`(): Unit = runBlocking {
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
    fun `logged in devices returns active sessions only`(): Unit = runBlocking {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", platform = "ios", language = "ko", timezone = "Asia/Seoul"))
        val principal = Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true, status = "ANONYMOUS")

        val response = login.loggedInDevices(principal)

        assertThat(response.devices).hasSize(1)
        assertThat(response.devices.first().deviceId).isEqualTo(device.deviceId)
        assertThat(response.devices.first().current).isTrue()
        assertThat(response.devices.first().platform).isEqualTo("ios")
    }

    @Test
    fun `new email user is created only when verification code matches`(): Unit = runBlocking {
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
        assertThat(response.profile.displayName).matches("[A-Z][a-z]+-[A-Z][a-z]+-\\d{4}")
        assertThat(response.profile.displayName).isNotEqualTo("new")
        assertThat(emailCodes.consumed).isTrue()
        assertThat(roles.grantRoleCalls).isEqualTo(2)
        assertThat(roles.grantRoleCallsFor(user!!.id, "REGISTERED_USER")).isEqualTo(1)
    }

    @Test
    fun `new email user is rejected when verification code is invalid`(): Unit = runBlocking {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        login.emailCode("new@example.com")

        assertThatThrownBy {
            runBlocking {
                login.emailLogin(
                    Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true),
                    EmailLoginCommand(email = "new@example.com", password = "password123", verificationCode = "000000"),
                )
            }
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED)

        assertThat(users.findByEmailAndProvider("new@example.com", "EMAIL")).isNull()
    }

    @Test
    fun `google login uses identity returned by verifier`(): Unit = runBlocking {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))

        val response = login.googleLogin(
            Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true),
            "google-id-token",
        )

        assertThat(response.profile.email).isEqualTo("google@example.com")
        assertThat(response.profile.displayName).matches("[A-Z][a-z]+-[A-Z][a-z]+-\\d{4}")
        assertThat(response.profile.displayName).isNotEqualTo("Google User")
        assertThat(users.findByProviderAndProviderId("GOOGLE", "google-provider-id")).isNotNull
    }

    @Test
    fun `google login rejects token not accepted by verifier`(): Unit = runBlocking {
        val device = login.register(RegisterDeviceCommand(apnsToken = "", language = "ko"))
        googleIdentities.identity = null

        assertThatThrownBy {
            runBlocking {
                login.googleLogin(
                    Principal(userId = 1, deviceId = device.deviceId, sessionId = 1, anonymous = true),
                    "invalid-token",
                )
            }
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN)
    }

    private class CapturingEmailCodePort : EmailVerificationCodePort {
        lateinit var savedEmail: String
        lateinit var savedCode: String
        lateinit var savedTtl: Duration
        var consumed = false
            private set

        override suspend fun save(email: String, code: String, ttl: Duration) {
            savedEmail = email
            savedCode = code
            savedTtl = ttl
            consumed = false
        }

        override suspend fun consume(email: String, code: String): Boolean {
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

        override suspend fun send(email: String, code: String, ttl: Duration) {
            sentEmail = email
            sentCode = code
        }
    }

    private class StubGoogleIdentityPort : GoogleIdentityPort {
        var identity: GoogleIdentity? = GoogleIdentity(
            providerId = "google-provider-id",
            email = "google@example.com",
            name = "Google User",
        )

        override suspend fun verify(idToken: String): GoogleIdentity? = identity
    }

    private class InMemoryUserPort : UserPort {
        private val users = linkedMapOf<Long, UserEntity>()
        private var nextId = 1L
        var findByIdCalls = 0

        override suspend fun save(entity: UserEntity): UserEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            users[entity.id] = entity
            return entity
        }

        override suspend fun findById(id: Long): UserEntity? {
            findByIdCalls += 1
            return users[id]
        }
        override suspend fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = ids.mapNotNull { users[it] }.toMutableList()
        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? =
            users.values.firstOrNull { it.provider == provider && it.providerId == providerId }

        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? =
            users.values.firstOrNull { it.email == email && it.provider == provider }

        fun countByProviderAndProviderId(provider: String, providerId: String): Int =
            users.values.count { it.provider == provider && it.providerId == providerId }
    }

    private class InMemoryDevicePort : DevicePort {
        private val devices = linkedMapOf<String, DeviceEntity>()
        private var nextId = 1L

        fun count(): Int = devices.size

        override suspend fun save(entity: DeviceEntity): DeviceEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            devices[entity.deviceId] = entity
            return entity
        }

        override suspend fun findByDeviceId(deviceId: String): DeviceEntity? = devices[deviceId]
        override suspend fun findByInstallationKeyHash(installationKeyHash: String): DeviceEntity? =
            devices.values.firstOrNull { it.installationKeyHash == installationKeyHash }
        override suspend fun findAllByUserId(userId: Long): List<DeviceEntity> =
            devices.values.filter { it.userId == userId }
    }

    private class InMemoryUserDevicePort : UserDevicePort {
        private val sessions = linkedMapOf<Long, UserDeviceEntity>()
        private var nextId = 1L

        override suspend fun save(entity: UserDeviceEntity): UserDeviceEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            sessions[entity.id] = entity
            return entity
        }

        override suspend fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity? =
            sessions.values.firstOrNull { it.userId == userId && it.deviceId == deviceId }

        override suspend fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity? =
            sessions[id]?.takeIf { it.userId == userId }

        override suspend fun findActiveByUserId(userId: Long): List<UserDeviceEntity> =
            sessions.values.filter { it.userId == userId && it.isActive() }

        override suspend fun hasActiveSession(userId: Long, deviceId: String): Boolean =
            sessions.values.any { it.userId == userId && it.deviceId == deviceId && it.isActive() }
    }

    private class InMemoryRoleAssignmentPort : RoleAssignmentPort {
        private val roles = mutableSetOf<Pair<Long, String>>()
        private val calls = mutableListOf<Pair<Long, String>>()
        val grantRoleCalls: Int
            get() = calls.size

        override suspend fun grantRoleIfMissing(userId: Long, roleCode: String) {
            calls += userId to roleCode
            roles += userId to roleCode
        }

        override suspend fun countUserRoles(userId: Long, roleCode: String): Long =
            if (userId to roleCode in roles) 1 else 0

        fun grantRoleCallsFor(userId: Long, roleCode: String): Int =
            calls.count { it == userId to roleCode }
    }
}
