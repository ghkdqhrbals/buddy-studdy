package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.RecordTimeDisplayMapper
import com.buddystudy.backend.study.application.model.QuestionThreadResponse
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import io.swagger.v3.oas.annotations.Operation
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Read-only v2 boundary; writes retain their existing v1 contracts. */
interface StudyV2WebPort {
    suspend fun studies(limit: Int, offset: Int, query: String?, language: String, authentication: Authentication): StudyPageResponse
    suspend fun study(studyId: Long, language: String, authentication: Authentication): StudyRoomResponse
    suspend fun records(limit: Int, offset: Int, query: String?, studyId: Long?, language: String, view: String, authentication: Authentication): RecordsPageResponse
    suspend fun record(recordId: Long, language: String, view: String, authentication: Authentication): StudyRecordResponse
    suspend fun thread(recordId: Long, language: String, view: String, authentication: Authentication): QuestionThreadResponse
    suspend fun learningRecords(studyId: Long, scope: String, limit: Int, cursor: String?, language: String, view: String, authentication: Authentication): StudyLearningRecordsPageResponse
}

@Component
class StudyV2WebAdapter(
    private val study: StudyWebPort,
    private val followUps: FollowUpQuestionWebPort,
    private val learningRecords: StudyLearningRecordsWebPort,
) : StudyV2WebPort {
    override suspend fun studies(limit: Int, offset: Int, query: String?, language: String, authentication: Authentication): StudyPageResponse {
        val page = study.study(limit, offset, query, language, authentication)
        return RecordTimeDisplayMapper(language).studies(page)
    }

    override suspend fun study(studyId: Long, language: String, authentication: Authentication): StudyRoomResponse {
        val room = study.study(studyId, language, authentication)
        return RecordTimeDisplayMapper(language).study(room)
    }

    override suspend fun records(limit: Int, offset: Int, query: String?, studyId: Long?, language: String, view: String, authentication: Authentication): RecordsPageResponse {
        val page = study.records(limit, offset, query, studyId, language, view, authentication)
        return RecordTimeDisplayMapper(language).records(page)
    }

    override suspend fun record(recordId: Long, language: String, view: String, authentication: Authentication): StudyRecordResponse {
        val record = study.record(recordId, language, view, authentication)
        return RecordTimeDisplayMapper(language).record(record)
    }

    override suspend fun thread(recordId: Long, language: String, view: String, authentication: Authentication): QuestionThreadResponse {
        val thread = followUps.thread(recordId, language, view, authentication)
        return RecordTimeDisplayMapper(language).thread(thread)
    }

    override suspend fun learningRecords(studyId: Long, scope: String, limit: Int, cursor: String?, language: String, view: String, authentication: Authentication): StudyLearningRecordsPageResponse {
        val page = learningRecords.learningRecords(studyId, scope, limit, cursor, language, view, authentication)
        return RecordTimeDisplayMapper(language).learningRecords(page)
    }
}

@RestController
@RequestMapping("/api/v2")
class StudyV2Controller(private val study: StudyV2WebPort) {
    @Operation(summary = "Fetch my studies with server-localized record times")
    @GetMapping("/studies")
    suspend fun studies(
        @RequestParam(defaultValue = "500") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        authentication: Authentication,
    ) = study.studies(limit, offset, query, resolveTargetLanguage(tl, language), authentication)

    @Operation(summary = "Fetch one study with server-localized record times")
    @GetMapping("/studies/{studyId}")
    suspend fun study(
        @PathVariable studyId: Long,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        authentication: Authentication,
    ) = study.study(studyId, resolveTargetLanguage(tl, language), authentication)

    @Operation(summary = "List my records with server-localized answer times")
    @GetMapping("/records")
    suspend fun records(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) studyId: Long?,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication,
    ) = study.records(limit, offset, query, studyId, resolveTargetLanguage(tl, language), view, authentication)

    @Operation(summary = "Fetch one record with server-localized answer time")
    @GetMapping("/records/{id}")
    suspend fun record(
        @PathVariable id: Long,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication,
    ) = study.record(id, resolveTargetLanguage(tl, language), view, authentication)

    @Operation(summary = "Fetch a follow-up thread with server-localized answer times")
    @GetMapping("/records/{id}/thread")
    suspend fun thread(
        @PathVariable id: Long,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication,
    ) = study.thread(id, resolveTargetLanguage(tl, language), view, authentication)

    @Operation(summary = "Read a study node's learning history with server-localized answer times")
    @GetMapping("/studies/{studyId}/learning-records")
    suspend fun learningRecords(
        @PathVariable studyId: Long,
        @RequestParam(defaultValue = "node") scope: String,
        @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) tl: String?,
        @RequestParam(required = false) language: String?,
        @RequestParam(defaultValue = "localized") view: String,
        authentication: Authentication,
    ) = study.learningRecords(studyId, scope, limit, cursor, resolveTargetLanguage(tl, language), view, authentication)
}
