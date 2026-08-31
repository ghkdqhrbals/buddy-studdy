package com.buddystudy.backend.study.adapter.inbound.web

import io.swagger.v3.oas.annotations.Operation
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class StudyLearningRecordsController(private val records: StudyLearningRecordsWebPort) {
    @Operation(summary = "Read a study node's learning history", description = "Owned graded questions and private source-backed voice exchanges, paginated by a scope-bound cursor. Original content is always available while translations use the existing translation stream.")
    @GetMapping("/studies/{studyId}/learning-records")
    suspend fun learningRecords(
        @PathVariable studyId: Long,
        @RequestParam(defaultValue = "node") scope: String,
        @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "ko") tl: String,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication,
    ) = records.learningRecords(studyId, scope, limit, cursor, tl, view, authentication)

    @Operation(summary = "Read one private voice learning record", description = "Requires the original call owner. Never publishes an answer or creates a pending question.")
    @GetMapping("/voice-tutor/learning-records/{id}")
    suspend fun voiceLearningRecord(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "ko") tl: String,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication,
    ) = records.voiceLearningRecord(id, tl, view, authentication)
}
