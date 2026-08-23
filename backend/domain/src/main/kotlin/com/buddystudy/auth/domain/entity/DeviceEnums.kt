package com.buddystudy.auth.domain.entity

enum class DevicePlatform(
    val databaseValue: String,
) {
    IOS("ios"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): DevicePlatform =
            entries.firstOrNull { it.databaseValue == value }
                ?: error("Unsupported device platform database value: $value")
    }
}

enum class ApnsEnvironment(
    val databaseValue: String,
) {
    SANDBOX("sandbox"),
    PRODUCTION("production"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): ApnsEnvironment =
            entries.firstOrNull { it.databaseValue == value }
                ?: error("Unsupported APNs environment database value: $value")
    }
}
