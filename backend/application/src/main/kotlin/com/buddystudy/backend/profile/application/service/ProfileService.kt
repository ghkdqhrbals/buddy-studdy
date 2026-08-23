package com.buddystudy.backend.profile.application.service

import com.buddystudy.account.domain.entity.AvatarMode
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.model.AccessTokenResponse
import com.buddystudy.backend.auth.application.permission.Roles
import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.service.AccountSessionManager
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import com.buddystudy.backend.profile.application.model.AvatarCatalogResponse
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.profile.application.model.toAvatarConfigJson
import com.buddystudy.backend.profile.application.model.toAvatarConfigMap
import com.buddystudy.backend.profile.application.model.toCompatibleBases
import com.buddystudy.backend.profile.application.model.toProfile
import com.buddystudy.backend.profile.application.model.toResponse
import com.buddystudy.backend.profile.application.port.inbound.AvatarUpdateCommand
import com.buddystudy.backend.profile.application.port.inbound.ProfileUpdateCommand
import com.buddystudy.backend.profile.application.port.inbound.ProfileUseCase
import com.buddystudy.backend.profile.application.port.outbound.AccountWithdrawalEventPort
import com.buddystudy.backend.profile.application.port.outbound.AvatarCatalogPort
import com.buddystudy.backend.profile.application.port.outbound.ProfilePhotoStoragePort
import com.buddystudy.backend.profile.application.port.outbound.StoredProfilePhoto
import com.buddystudy.backend.profile.application.port.outbound.UnavailableProfilePhotoStoragePort
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ProfileService(
    private val users: UserPort,
    private val devices: DevicePort,
    private val sessions: AccountSessionManager,
    private val roles: RoleAssignmentPort,
    private val tokenService: TokenProvider,
    private val accountDeletion: AccountDeletionPort,
    private val withdrawalEvents: AccountWithdrawalEventPort,
    private val avatarCatalog: AvatarCatalogPort,
    private val profilePhotos: ProfilePhotoStoragePort = UnavailableProfilePhotoStoragePort,
) : ProfileUseCase {
    private val log = LoggerFactory.getLogger(ProfileService::class.java)

    @Transactional(readOnly = true)
    override suspend fun profile(principal: Principal): UserProfileResponse = user(principal.userId).toProfile()

    @Transactional(readOnly = true)
    override suspend fun avatarCatalog(principal: Principal): AvatarCatalogResponse {
        val user = user(principal.userId)
        val categories = avatarCatalog.activeCategories()
        val items = avatarCatalog.availableItems(user.id)
        val currentConfig = currentAvatarConfig(user.avatarConfig)
        return AvatarCatalogResponse(
            categories = categories.map { it.toResponse() },
            items = items.map { it.toResponse(it.compatibleBases.toCompatibleBases()) },
            defaultConfig = defaultAvatarConfig,
            currentConfig = currentConfig,
        )
    }

    @Transactional
    override suspend fun updateAvatar(principal: Principal, command: AvatarUpdateCommand): UserProfileResponse {
        val user = user(principal.userId)
        val config = validateAvatarConfig(command.avatarConfig, user.id)
        user.avatarMode = command.avatarMode.toAvatarMode(default = AvatarMode.BUILDER)
        user.avatarConfig = config.toAvatarConfigJson()
        profilePhotos.delete(user.id)
        user.avatarUrl = null
        command.avatarColorSeed?.let { user.avatarColorSeed = it.take(64) }
        config["base"]?.let { user.avatarSymbolName = baseSymbolName(it) }
        user.updatedAt = Instant.now()
        val saved = users.save(user)
        log.info(
            "avatar_update_saved userId={} avatarMode={} slots={} base={}",
            saved.id,
            saved.avatarMode,
            config.keys.sorted().joinToString(","),
            config["base"],
        )
        return saved.toProfile()
    }

    @Transactional
    override suspend fun updateProfile(principal: Principal, command: ProfileUpdateCommand): UserProfileResponse {
        val user = user(principal.userId)
        log.info(
            "profile_update_command userId={} displayNamePresent={} bioPresent={} avatarSymbolName={} avatarColorSeed={}",
            principal.userId,
            command.displayName != null,
            command.bio != null,
            command.avatarSymbolName,
            command.avatarColorSeed,
        )
        command.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { user.displayName = it.take(120) }
        command.bio?.let { user.bio = it.take(500) }
        command.avatarSymbolName?.let { user.avatarSymbolName = it.take(64) }
        command.avatarColorSeed?.let { user.avatarColorSeed = it.take(64) }
        command.allowPublicQuestions?.let { user.allowPublicQuestions = it }
        command.avatarMode?.let { requestedMode ->
            user.avatarMode = requestedMode.toAvatarMode(default = user.avatarMode)
            if (!requestedMode.equals(PHOTO_AVATAR_MODE, ignoreCase = true)) {
                profilePhotos.delete(user.id)
                user.avatarUrl = null
                if (requestedMode.equals(PIXEL_AVATAR_MODE, ignoreCase = true)) {
                    user.avatarConfig = null
                }
            }
        }
        command.avatarConfig?.let { config ->
            val validated = validateAvatarConfig(config, user.id)
            user.avatarConfig = validated.toAvatarConfigJson()
            validated["base"]?.let { user.avatarSymbolName = baseSymbolName(it) }
        }
        user.updatedAt = Instant.now()
        val saved = try {
            users.save(user)
        } catch (duplicate: DataIntegrityViolationException) {
            if (command.displayName == null) {
                throw duplicate
            }
            throw ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.DISPLAY_NAME_TAKEN,
                "Display name is already in use.",
            )
        }
        log.info(
            "profile_update_saved userId={} avatarSymbolName={} avatarColorSeed={}",
            saved.id,
            saved.avatarSymbolName,
            saved.avatarColorSeed,
        )
        return saved.toProfile()
    }

    @Transactional(readOnly = true)
    override suspend fun profilePhoto(userId: Long): StoredProfilePhoto =
        profilePhotos.load(userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Profile photo not found.")

    @Transactional
    override suspend fun withdrawProfile(principal: Principal): AccessTokenResponse {
        if (principal.anonymous) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED, "Account deletion requires an active login.")
        }
        val now = Instant.now()
        val withdrawal = accountDeletion.beginWithdrawal(principal.userId, now)
        withdrawalEvents.append(
            AccountWithdrawnEvent.create(
                userId = principal.userId,
                deviceIds = withdrawal.deviceIds,
                withdrawnAt = now,
            ),
        )
        val device = sessions.device(principal.deviceId)
        val anonymousUser = sessions.ensureAnonymousUser(device)
        devices.save(device)
        roles.grantRoleIfMissing(anonymousUser.id, Roles.ANONYMOUS_USER)
        val session = sessions.saveSession(anonymousUser.id, device.deviceId, now, null)
        val token = tokenService.create(anonymousUser.id, device.deviceId, session.id, true, anonymousUser.status.name)
        return AccessTokenResponse(token.first, token.second)
    }

    private suspend fun user(id: Long) = users.findById(id)
        ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")

    private suspend fun currentAvatarConfig(rawConfig: String?): Map<String, String> =
        rawConfig.toAvatarConfigMap()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultAvatarConfig

    private suspend fun validateAvatarConfig(input: Map<String, String>, userId: Long): Map<String, String> {
        val requested = (defaultAvatarConfig + input)
            .mapValues { (_, value) -> value.trim() }
            .filterValues { it.isNotBlank() }
        val categoriesBySlot = avatarCatalog.activeCategories().associateBy { it.slot.databaseValue }
        val availableItemsByKey = avatarCatalog.availableItems(userId).associateBy { it.key }
        val normalized = requested.mapValues { (slot, itemKey) ->
            val category = categoriesBySlot[slot]
                ?: throw validation("Unknown avatar slot: $slot.")
            val item = availableItemsByKey[itemKey]
                ?: throw validation("Avatar item is not available: $itemKey.")
            if (item.slot != category.slot || item.category != category.key) {
                throw validation("Avatar item $itemKey does not belong to slot $slot.")
            }
            item.key
        }.toMutableMap()

        categoriesBySlot.values
            .filter { it.required }
            .forEach { category ->
                if (normalized[category.slot.databaseValue].isNullOrBlank()) {
                    throw validation("Avatar slot ${category.slot.databaseValue} is required.")
                }
            }

        val base = normalized["base"]
        if (base != null) {
            normalized.values
                .mapNotNull { availableItemsByKey[it] }
                .forEach { item ->
                    val compatibleBases = item.compatibleBases.toCompatibleBases()
                    if (compatibleBases.isNotEmpty() && base !in compatibleBases) {
                        throw validation("Avatar item ${item.key} is not compatible with base $base.")
                    }
                }
        }
        return normalized.toSortedMap()
    }

    private suspend fun validation(message: String) =
        ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)

    private suspend fun baseSymbolName(itemKey: String): String = when (itemKey) {
        "base-cat" -> "pixel-cat-laptop"
        "base-fox" -> "pixel-fox-scholar"
        "base-rabbit" -> "pixel-rabbit-reader"
        "base-dog" -> "pixel-dog-coder"
        else -> itemKey
    }

    private suspend fun String.toAvatarMode(default: AvatarMode): AvatarMode {
        val value = trim()
        if (value.isEmpty()) {
            return default
        }
        return runCatching { AvatarMode.valueOf(value.uppercase()) }
            .getOrElse { throw validation("Unsupported avatar mode: $value") }
    }

    companion object {
        private const val PHOTO_AVATAR_MODE = "PHOTO"
        private const val PIXEL_AVATAR_MODE = "PIXEL"

        val defaultAvatarConfig: Map<String, String> = mapOf(
            "base" to "base-cat",
            "background" to "background-teal",
            "top" to "top-hoodie-blue",
            "bottom" to "bottom-denim-pants",
            "shoes" to "shoes-white-sneakers",
            "hat" to "hat-beanie-navy",
            "item" to "item-laptop",
        )
    }
}
