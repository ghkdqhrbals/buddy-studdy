package com.buddystudy.account.domain.entity

enum class UserProvider {
    ANONYMOUS,
    APPLE,
    GOOGLE,
    EMAIL,
    WITHDRAWN,
}

enum class UserStatus {
    ANONYMOUS,
    PENDING_TERMS,
    ACTIVE,
    WITHDRAWN,
}

enum class AvatarMode {
    BUILDER,
    PHOTO,
    PIXEL,
}

enum class MembershipStatus {
    ACTIVE,
}
