package com.buddystuddy.backend.study.adapter.inbound.web

import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.CreateQuestionRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.CreateStudyRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystuddy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystuddy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystuddy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudyUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.stereotype.Component
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid
import kotlin.math.max
import kotlin.math.min

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Study", description = "Authenticated study-room, question, record, and topic-stat APIs.")
class StudyController(
    private val study: StudyWebPort,
) {
    @Operation(
        summary = "Fetch my studies",
        description = "Returns the authenticated user's study rooms. Each study can include one pendingQuestion for the current unanswered study-room question. Record history is intentionally split into /api/v1/records.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Study rooms returned."),
        ApiResponse(responseCode = "401", description = "Missing, invalid, or expired access token/device credentials."),
    )
    @GetMapping("/studies")
    fun study(
        @Parameter(description = "Maximum number of studies to include. Server clamps this to 1..1000.", example = "500")
        @RequestParam(defaultValue = "500") limit: Int,
        @Parameter(description = "Zero-based study offset for pagination.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Optional DB-backed study search query.", example = "Swift")
        @RequestParam(required = false) query: String?,
        authentication: Authentication,
    ) = study.study(limit, offset, query, authentication)

    @Operation(
        summary = "Create a study",
        description = "Creates a study room for the authenticated user. If the same topic already exists for the user, the existing study is updated with the request values and returned.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Study created or updated."),
        ApiResponse(responseCode = "400", description = "Invalid study request."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @PostMapping("/study")
    fun createStudy(
        @Valid @RequestBody body: CreateStudyRequest,
        authentication: Authentication,
    ) = study.createStudy(body, authentication)

    @Operation(
        summary = "List my graded records",
        description = "Returns the authenticated user's graded or completed study records. Ungraded active questions are intentionally managed from the study room and should not be shown as regular history.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Records returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @GetMapping("/records")
    fun records(
        @Parameter(description = "Maximum number of records to return. Server clamps this to 1..500.", example = "100")
        @RequestParam(defaultValue = "100") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Optional DB-backed record search query.", example = "actor")
        @RequestParam(required = false) query: String?,
        authentication: Authentication,
    ) =
        study.records(limit, offset, query, authentication)

    @Operation(summary = "Clear all my records", description = "Reserved endpoint for deleting all records owned by the authenticated user.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Records cleared."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @DeleteMapping("/records")
    fun clearRecords(authentication: Authentication): ResponseEntity<Unit> = study.clearRecords(authentication)

    @Operation(summary = "Fetch one record", description = "Returns one study record owned by the authenticated user.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Record returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Record not found or not owned by the user."),
    )
    @GetMapping("/records/{id}")
    fun record(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        authentication: Authentication,
    ) = study.record(id, authentication)

    @Operation(summary = "Save a draft answer", description = "Stores the current answer text without grading. Used for preserving user drafts while the study room remains open.")
    @PatchMapping("/records/{id}/answer")
    fun saveAnswer(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: AnswerRequest,
        authentication: Authentication,
    ) =
        study.saveAnswer(id, body, authentication)

    @Operation(summary = "Submit an answer for grading", description = "Submits the answer, asks the tutor model to grade it, and returns the updated record with score, correctness, feedback, and explanation.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Answer graded."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Record not found or not owned by the user."),
    )
    @PostMapping("/records/{id}/answer")
    fun grade(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: AnswerRequest,
        authentication: Authentication,
    ) =
        study.grade(id, body, authentication)

    @Operation(summary = "Skip a question", description = "Marks an ungraded question as skipped and removes it from the active study-room question state.")
    @PostMapping("/records/{id}/skip")
    fun skip(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        authentication: Authentication,
    ) = study.skip(id, authentication)

    @Operation(summary = "Delete one record", description = "Immediately deletes a record owned by the authenticated user.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Record deleted."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Record not found or not owned by the user."),
    )
    @DeleteMapping("/records/{id}")
    fun delete(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Unit> = study.delete(id, authentication)

    @Operation(summary = "Update record visibility", description = "Sets whether a completed record can be included in public questions. The user's global public-question setting must also allow public sharing.")
    @PatchMapping("/records/{id}/publicity")
    fun publicity(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: RecordPublicityRequest,
        authentication: Authentication,
    ) =
        study.publicity(id, body, authentication)

    @Operation(summary = "Fetch topic statistics", description = "Returns topic-first statistics for the authenticated user. Topics are sorted by answer count and include level-range information; the app should not compute global score averages locally.")
    @GetMapping("/stats")
    fun stats(
        @Parameter(description = "Maximum number of topic stat cards to return.", example = "8")
        @RequestParam(defaultValue = "8") limit: Int,
        @Parameter(description = "Zero-based topic offset for pagination.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Optional DB-backed topic stat search query.", example = "Swift")
        @RequestParam(required = false) query: String?,
        authentication: Authentication,
    ) =
        study.stats(limit, offset, query, authentication)

    @Operation(summary = "Create a new study question", description = "Creates one new question for a specific study topic. The backend enforces the per-study pending-question limit and uses the user's stored OpenAI settings.")
    @PostMapping("/questions")
    fun createQuestion(
        @RequestBody body: CreateQuestionRequest,
        authentication: Authentication,
    ) =
        study.createQuestion(body, authentication)
}

interface StudyWebPort {
    fun study(limit: Int, offset: Int, query: String?, authentication: Authentication): Any
    fun records(limit: Int, offset: Int, query: String?, authentication: Authentication): Any
    fun clearRecords(authentication: Authentication): ResponseEntity<Unit>
    fun record(id: Long, authentication: Authentication): Any
    fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication): Any
    fun grade(id: Long, body: AnswerRequest, authentication: Authentication): Any
    fun skip(id: Long, authentication: Authentication): Any
    fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit>
    fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication): Any
    fun stats(limit: Int, offset: Int, query: String?, authentication: Authentication): Any
    fun createQuestion(body: CreateQuestionRequest, authentication: Authentication): Any
    fun createStudy(body: CreateStudyRequest, authentication: Authentication): Any
}

@Component
class StudyWebAdapter(
    private val studyUseCase: StudyUseCase,
    private val recordsUseCase: BrowseRecordsUseCase,
    private val statsUseCase: GetStudyStatsUseCase,
    private val studySyncUseCase: StudySyncUseCase,
) : StudyWebPort {
    override fun study(limit: Int, offset: Int, query: String?, authentication: Authentication) =
        studySyncUseCase.study(authentication.principalOrThrow(), safeLimit(limit, 1000), max(0, offset), query)

    override fun records(limit: Int, offset: Int, query: String?, authentication: Authentication) =
        recordsUseCase.records(authentication.principalOrThrow(), safeLimit(limit, 500), max(0, offset), query)

    override fun clearRecords(authentication: Authentication): ResponseEntity<Unit> = ResponseEntity.noContent().build()

    override fun record(id: Long, authentication: Authentication) = recordsUseCase.record(authentication.principalOrThrow(), id)

    override fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication) =
        studyUseCase.answer(authentication.principalOrThrow(), id, body.answer, grade = false)

    override fun grade(id: Long, body: AnswerRequest, authentication: Authentication) =
        studyUseCase.answer(authentication.principalOrThrow(), id, body.answer, grade = true)

    override fun skip(id: Long, authentication: Authentication) = studyUseCase.skip(authentication.principalOrThrow(), id)

    override fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit> {
        studyUseCase.delete(authentication.principalOrThrow(), id)
        return ResponseEntity.noContent().build()
    }

    override fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication) =
        studyUseCase.publicity(authentication.principalOrThrow(), id, body.isPublic)

    override fun stats(limit: Int, offset: Int, query: String?, authentication: Authentication) =
        statsUseCase.stats(authentication.principalOrThrow(), safeLimit(limit, 100), max(0, offset), query)

    override fun createQuestion(body: CreateQuestionRequest, authentication: Authentication) =
        studyUseCase.createQuestion(authentication.principalOrThrow(), body.topic)

    override fun createStudy(body: CreateStudyRequest, authentication: Authentication) =
        studySyncUseCase.createStudy(
            authentication.principalOrThrow(),
            CreateStudyCommand(
                topic = body.topic,
                difficultyLevel = body.difficultyLevel,
                intervalMinutes = body.intervalMinutes,
                enabled = body.enabled,
                notificationSound = body.notificationSound,
                customPrompt = body.customPrompt,
                openaiModel = body.openaiModel,
                maxHistoryCount = body.maxHistoryCount,
                isQuestionPublic = body.isQuestionPublic,
            )
        )

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}
