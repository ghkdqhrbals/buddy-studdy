package com.buddystuddy.backend.study.application.model

data class PushTestCommand(
    val title: String = "BuddyStuddy",
    val body: String = "BuddyStuddy test push.",
    val topic: String = "Test",
    val recordId: String = "test",
    val studyId: Long? = null,
    val difficultyLevel: Int = 1,
    val language: String = "ko",
    val sound: String = "default",
    val deepLink: String = "buddystuddy://test-push",
)

data class PushTestResponse(
    val sent: Boolean,
    val provider: String,
    val deviceId: String,
    val topic: String,
    val recordId: String,
)
