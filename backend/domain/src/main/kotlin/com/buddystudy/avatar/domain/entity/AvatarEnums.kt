package com.buddystudy.avatar.domain.entity

enum class AvatarSlot(
    val databaseValue: String,
) {
    BASE("base"),
    BACKGROUND("background"),
    TOP("top"),
    BOTTOM("bottom"),
    SHOES("shoes"),
    HAT("hat"),
    ITEM("item"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): AvatarSlot =
            entries.firstOrNull { it.databaseValue == value }
                ?: error("Unsupported avatar slot database value: $value")
    }
}

enum class AvatarGrantSource {
    SYSTEM,
}
