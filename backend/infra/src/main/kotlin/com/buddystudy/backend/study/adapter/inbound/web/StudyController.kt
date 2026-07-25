package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.settings.adapter.inbound.web.SettingsWebPort
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.model.StatsActivityResponse
import com.buddystudy.backend.stats.application.model.StatsResponse
import com.buddystudy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.CreateStudyRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystudy.backend.study.adapter.inbound.web.dto.StudyTopicActivationRequest
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Study", description = "Authenticated study-room, question, record, and topic-stat APIs.")
class StudyController(
    private val study: StudyWebPort,
    private val settings: SettingsWebPort,
) {

    @Operation(summary = "Fetch one study room settings", description = "Returns settings for a single study room. Use this instead of the old broad startup settings state when editing one study.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Study settings returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Study settings not found."),
    )
    @GetMapping("/studies/{studyId}/settings")
    suspend fun studySettings(
        @PathVariable studyId: Long,
        authentication: Authentication,
    ) = settings.studySettings(studyId, authentication)

    @Operation(
        summary = "Fetch my studies",
        description = "Returns the authenticated user's study rooms. Each study can include one pendingQuestion for the current unanswered study-room question. Record history is intentionally split into /api/v1/records.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Study rooms returned."),
        ApiResponse(responseCode = "401", description = "Missing, invalid, or expired access token/device credentials."),
    )
    @GetMapping("/studies")
    suspend fun study(
        @Parameter(description = "Maximum number of studies to include. Server clamps this to 1..1000.", example = "500")
        @RequestParam(defaultValue = "500") limit: Int,
        @Parameter(description = "Zero-based study offset for pagination.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Optional DB-backed study search query.", example = "Swift")
        @RequestParam(required = false) query: String?,
        authentication: Authentication,
    ): StudyPageResponse = study.study(limit, offset, query, authentication)

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
    @RequirePermission(Permissions.STUDY_CREATE)
    suspend fun createStudy(
        @Valid @RequestBody body: CreateStudyRequest,
        authentication: Authentication,
    ): StudyRoomResponse = study.createStudy(body, authentication)

    @Operation(summary = "Delete a study", description = "Deletes one study room owned by the authenticated user and removes its related questions from active records/search.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Study deleted."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Study not found or not owned by the user."),
    )
    @DeleteMapping("/studies/{studyId}")
    @RequirePermission(Permissions.STUDY_DELETE)
    suspend fun deleteStudy(
        @Parameter(description = "Study room id.", example = "42")
        @PathVariable studyId: Long,
        authentication: Authentication,
    ): ResponseEntity<Unit> = study.deleteStudy(studyId, authentication)

    @Operation(summary = "Recommend child study topics", description = "Uses the system tutor model to recommend non-duplicate child topics for the selected tree node.")
    @PostMapping("/studies/{studyId}/topic-suggestions")
    @RequirePermission(Permissions.STUDY_CREATE)
    suspend fun suggestStudyTopics(
        @PathVariable studyId: Long,
        @RequestParam(defaultValue = "4") count: Int,
        authentication: Authentication,
    ) = study.suggestStudyTopics(studyId, count, authentication)

    @Operation(summary = "Activate or deactivate a tree topic", description = "Controls whether this node participates in scheduled round-robin question generation.")
    @PatchMapping("/studies/{studyId}/question-activation")
    @RequirePermission(Permissions.STUDY_UPDATE)
    suspend fun updateStudyTopicActivation(
        @PathVariable studyId: Long,
        @RequestBody body: StudyTopicActivationRequest,
        authentication: Authentication,
    ) = study.updateStudyTopicActivation(studyId, body, authentication)

    @Operation(
        summary = "List my graded records",
        description = "Returns the authenticated user's graded or completed study records. Ungraded active questions are intentionally managed from the study room and should not be shown as regular history.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Records returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @GetMapping("/records")
    suspend fun records(
        @Parameter(description = "Maximum number of records to return. Server clamps this to 1..500.", example = "100")
        @RequestParam(defaultValue = "100") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Optional DB-backed record search query.", example = "actor")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Response/search language code.", example = "ko")
        @RequestParam(defaultValue = "ko") language: String,
        authentication: Authentication,
    ): RecordsPageResponse =
        study.records(limit, offset, query, language, authentication)

    @Operation(summary = "Clear all my records", description = "Reserved endpoint for deleting all records owned by the authenticated user.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Records cleared."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @DeleteMapping("/records")
    @RequirePermission(Permissions.RECORD_DELETE)
    suspend fun clearRecords(authentication: Authentication): ResponseEntity<Unit> =
        study.clearRecords(authentication)

    @Operation(summary = "Fetch one record", description = "Returns one study record owned by the authenticated user.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Record returned."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Record not found or not owned by the user."),
    )
    @GetMapping("/records/{id}")
    suspend fun record(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        @Parameter(description = "Response language code.", example = "ko")
        @RequestParam(defaultValue = "ko") language: String,
        authentication: Authentication,
    ): StudyRecordResponse = study.record(id, language, authentication)

    @Operation(summary = "Save a draft answer", description = "Stores the current answer text without grading. Used for preserving user drafts while the study room remains open.")
    @PatchMapping("/records/{id}/answer")
    @RequirePermission(Permissions.RECORD_UPDATE)
    suspend fun saveAnswer(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: AnswerRequest,
        authentication: Authentication,
    ): StudyRecordResponse = study.saveAnswer(id, body, authentication)

    @Operation(summary = "Submit an answer for grading", description = "Submits the answer, asks the tutor model to grade it, and returns the updated record with score, correctness, feedback, and explanation.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Answer graded."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Record not found or not owned by the user."),
    )
    @PostMapping("/records/{id}/answer")
    @RequirePermission(Permissions.RECORD_UPDATE)
    suspend fun grade(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: AnswerRequest,
        authentication: Authentication,
    ): StudyRecordResponse = study.grade(id, body, authentication)

    @Operation(summary = "Skip a question", description = "Marks an ungraded question as skipped and removes it from the active study-room question state.")
    @PostMapping("/records/{id}/skip")
    @RequirePermission(Permissions.RECORD_UPDATE)
    suspend fun skip(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        authentication: Authentication,
    ): StudyRecordResponse = study.skip(id, authentication)

    @Operation(summary = "Delete one record", description = "Immediately deletes a record owned by the authenticated user.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Record deleted."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "404", description = "Record not found or not owned by the user."),
    )
    @DeleteMapping("/records/{id}")
    @RequirePermission(Permissions.RECORD_DELETE)
    suspend fun delete(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Unit> = study.delete(id, authentication)

    @Operation(summary = "Update record visibility", description = "Sets whether a completed record can be included in public questions. The user's global public-question setting must also allow public sharing.")
    @PatchMapping("/records/{id}/publicity")
    @RequirePermission(Permissions.RECORD_PUBLISH)
    suspend fun publicity(
        @Parameter(description = "Record/question id.", example = "42")
        @PathVariable id: Long,
        @RequestBody body: RecordPublicityRequest,
        authentication: Authentication,
    ): StudyRecordResponse = study.publicity(id, body, authentication)

    @Operation(summary = "Fetch topic statistics", description = "Returns topic-first statistics for the authenticated user. Topics are sorted by answer count and include level-range information; the app should not compute global score averages locally.")
    @GetMapping("/stats")
    suspend fun stats(
        @Parameter(description = "Maximum number of topic stat cards to return.", example = "8")
        @RequestParam(defaultValue = "8") limit: Int,
        @Parameter(description = "Zero-based topic offset for pagination.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        @Parameter(description = "Optional DB-backed topic stat search query.", example = "Swift")
        @RequestParam(required = false) query: String?,
        @Parameter(description = "Optional preset period: all, today, last7, last30, last90.", example = "last30")
        @RequestParam(required = false) period: String?,
        @Parameter(description = "Optional inclusive UTC start timestamp for custom period.", example = "2026-06-01T00:00:00Z")
        @RequestParam(required = false) startAt: Instant?,
        @Parameter(description = "Optional exclusive UTC end timestamp for custom period.", example = "2026-06-13T00:00:00Z")
        @RequestParam(required = false) endAt: Instant?,
        authentication: Authentication,
    ): StatsResponse =
        study.stats(limit, offset, StatsQuery(search = query, period = period, startAt = startAt, endAt = endAt), authentication)

    @Operation(summary = "Fetch daily study activity", description = "Returns compact daily activity for the authenticated user. The app uses this to render a one-year contribution-style activity graph.")
    @GetMapping("/stats/activity")
    suspend fun statsActivity(
        @Parameter(description = "Optional inclusive UTC start timestamp. Defaults to 365 days including today.", example = "2025-06-18T00:00:00Z")
        @RequestParam(required = false) startAt: Instant?,
        @Parameter(description = "Optional exclusive UTC end timestamp. Defaults to tomorrow UTC.", example = "2026-06-19T00:00:00Z")
        @RequestParam(required = false) endAt: Instant?,
        authentication: Authentication,
    ): StatsActivityResponse =
        study.statsActivity(startAt, endAt, authentication)

    @Operation(summary = "Create a new study question", description = "Creates one new question for the requested study room. The backend enforces the per-study pending-question limit and uses the user's stored OpenAI settings.")
    @PostMapping("/studies/{studyId}/questions")
    suspend fun createQuestion(
        @Parameter(description = "Study room id.", example = "42")
        @PathVariable studyId: Long,
        authentication: Authentication,
    ): StudyRecordResponse = study.createQuestion(studyId, authentication)

    @Operation(
        summary = "Fetch my monthly question quota",
        description = "Returns only the current usage, monthly allowance, remaining count, and next reset time. Membership tier details are intentionally not exposed to the app.",
    )
    @GetMapping("/questions/quota")
    suspend fun questionQuota(authentication: Authentication) =
        study.questionQuota(authentication)
}
