package com.buddystudy.backend.study.application.port.inbound

data class CreateStudyCommand(
    val topic: String,
    val difficultyLevel: Int = 5,
    val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val notificationSound: String? = null,
    val customPrompt: String = "",
    val openaiModel: String = "gpt-5.4",
    val maxHistoryCount: Int = 100,
)

/** Create-only root metadata. Scheduling uses the product defaults and is not model-controlled. */
data class CreateRootStudyCommand(
    val topic: String,
    val difficultyLevel: Int = 5,
)

data class CreateStudyTopicCommand(
    val topic: String,
    val sortOrder: Int = 0,
    val difficultyLevel: Int = 5,
    val activeForQuestions: Boolean = true,
    /** Server-owned curriculum creation; ordinary app requests retain their supplied level. */
    val inheritRootDifficulty: Boolean = false,
)

/** Explicitly selected direct children; this never expands their descendants. */
data class CreateStudyTopicsCommand(
    val topics: List<String>,
    val difficultyLevel: Int = 5,
    val expectedParent: ExpectedStudyMetadata? = null,
    /** Server-owned candidate metadata, never inferred from whether a node has children. */
    val curriculumTerminalByTopic: Map<String, Boolean> = emptyMap(),
    val inheritRootDifficulty: Boolean = false,
)

data class UpdateStudyTopicActivationCommand(
    val active: Boolean,
)

/** A metadata-only patch; omitted values never reset other node settings. */
data class UpdateStudyCommand(
    val topic: String? = null,
    val difficultyLevel: Int? = null,
    /**
     * Optional optimistic identity fence used by a server-owned voice mutation.
     * Ordinary app and MCP updates omit it and keep their existing behavior.
     */
    val expectedCurrent: ExpectedStudyMetadata? = null,
)

data class ExpectedStudyMetadata(
    val parentStudyId: Long?,
    val topic: String,
    val difficultyLevel: Int,
)
