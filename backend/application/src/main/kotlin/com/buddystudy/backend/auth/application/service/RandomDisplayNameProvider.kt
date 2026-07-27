package com.buddystudy.backend.auth.application.service

import org.springframework.stereotype.Component
import java.security.SecureRandom

@Component
class RandomDisplayNameProvider {
    private val random = SecureRandom()

    fun next(): String {
        val adjective = adjectives[random.nextInt(adjectives.size)]
        val noun = nouns[random.nextInt(nouns.size)]
        val number = random.nextInt(9_000) + 1_000
        return "$adjective-$noun-$number"
    }

    private companion object {
        val adjectives = listOf(
            "Admirable",
            "Agile",
            "Bright",
            "Calm",
            "Clever",
            "Curious",
            "Daring",
            "Eager",
            "Gentle",
            "Happy",
            "Honest",
            "Jolly",
            "Kind",
            "Lively",
            "Lucky",
            "Merry",
            "Nimble",
            "Noble",
            "Patient",
            "Quick",
            "Radiant",
            "Ready",
            "Sharp",
            "Steady",
            "Sunny",
            "Swift",
            "Thoughtful",
            "Upbeat",
            "Vivid",
            "Warm",
            "Wise",
            "Witty",
        )

        val nouns = listOf(
            "Badger",
            "Bear",
            "Bee",
            "Buy",
            "Comet",
            "Dolphin",
            "Eagle",
            "Falcon",
            "Fox",
            "Gecko",
            "Heron",
            "Koala",
            "Lion",
            "Lynx",
            "Maple",
            "Marten",
            "Otter",
            "Owl",
            "Panda",
            "Pine",
            "Puffin",
            "Rabbit",
            "Robin",
            "Seal",
            "Sparrow",
            "Spruce",
            "Star",
            "Tiger",
            "Toucan",
            "Whale",
            "Wolf",
            "Wren",
        )
    }
}
