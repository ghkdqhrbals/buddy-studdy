package com.buddystudy.backend.community.application.model

import java.util.Locale

data class TopicSubscriptionsResponse(val topics: List<String> = emptyList())

/** Display text is retained; matching ignores case, whitespace, hyphens and underscores. */
object TopicSubscriptionPolicy {
    const val MAX_TOPICS = 30
    const val MAX_TOPIC_LENGTH = 120
    // Unicode White_Space, shared by Swift Character.isWhitespace and SQL topic matching.
    val whitespaceCodePoints = (9..13).toList() + listOf(0x20, 0x85, 0xA0, 0x1680) +
        (0x2000..0x200A).toList() + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
    private val whitespace = Regex("[" + whitespaceCodePoints.map { it.toChar() }.joinToString("") + "]+")
    private val separators = Regex("[" + whitespaceCodePoints.map { it.toChar() }.joinToString("") + "_-]+")

    fun display(value: String): String = value.replace(whitespace, " ").trim()
    fun key(value: String): String = value.lowercase(Locale.ROOT).replace(separators, "")
}

enum class PublicFeedSort { RECOMMENDED, LATEST, VIEWS, LIKES }
enum class PublicFeedScope { ALL, FOLLOWING }
