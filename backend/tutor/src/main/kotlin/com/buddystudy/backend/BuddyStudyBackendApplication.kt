package com.buddystudy.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@ImportRuntimeHints(ApplicationRuntimeHints::class)
@SpringBootApplication(scanBasePackages = ["com.buddystudy"])
class BuddyStudyBackendApplication

fun main(args: Array<String>) {
    runApplication<BuddyStudyBackendApplication>(*args)
}

class ApplicationRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        kotlinCollectionJacksonTypes.forEach { type ->
            hints.reflection().registerType(
                TypeReference.of(type),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS,
            )
        }
        jacksonResponseTypes.forEach { type ->
            hints.reflection().registerType(
                TypeReference.of(type),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS,
            )
        }
        hints.resources().registerPattern("db/migration/*.sql")
    }

    private val kotlinCollectionJacksonTypes = listOf(
        "kotlin.collections.EmptyList",
        "kotlin.collections.EmptyMap",
        "kotlin.collections.EmptySet",
    )

    private val jacksonResponseTypes = listOf(
        "com.buddystudy.backend.auth.application.model.AccessTokenResponse",
        "com.buddystudy.backend.auth.application.model.DeviceRegisterResponse",
        "com.buddystudy.backend.auth.application.model.EmailVerificationCodeResponse",
        "com.buddystudy.backend.auth.application.model.GoogleLoginResponse",
        "com.buddystudy.backend.auth.application.model.LoggedInDeviceResponse",
        "com.buddystudy.backend.auth.application.model.LoggedInDevicesResponse",
        "com.buddystudy.backend.profile.adapter.inbound.web.dto.AvatarUpdateRequest",
        "com.buddystudy.backend.profile.adapter.inbound.web.dto.ProfileUpdateRequest",
        "com.buddystudy.backend.profile.application.model.AvatarCatalogResponse",
        "com.buddystudy.backend.profile.application.model.AvatarCategoryResponse",
        "com.buddystudy.backend.profile.application.model.AvatarItemResponse",
        "com.buddystudy.backend.profile.application.model.UserProfileResponse",
    )
}
