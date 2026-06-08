package com.buddystuddy.backend.study.adapter.inbound.web

import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.CreateQuestionRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystuddy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystuddy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudySyncUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudyUseCase
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
import kotlin.math.max
import kotlin.math.min

@RestController
@RequestMapping("/api/v1")
class StudyController(
    private val study: StudyWebPort,
) {
    @GetMapping("/me/snapshot")
    fun sync(@RequestParam(defaultValue = "500") limit: Int, @RequestParam(defaultValue = "0") offset: Int, authentication: Authentication) =
        study.sync(limit, offset, authentication)

    @GetMapping("/me/records")
    fun records(@RequestParam(defaultValue = "100") limit: Int, @RequestParam(defaultValue = "0") offset: Int, authentication: Authentication) =
        study.records(limit, offset, authentication)

    @DeleteMapping("/me/records")
    fun clearRecords(authentication: Authentication): ResponseEntity<Unit> = study.clearRecords(authentication)

    @GetMapping("/me/records/{id}")
    fun record(@PathVariable id: Long, authentication: Authentication) = study.record(id, authentication)

    @PatchMapping("/me/records/{id}/answer")
    fun saveAnswer(@PathVariable id: Long, @RequestBody body: AnswerRequest, authentication: Authentication) =
        study.saveAnswer(id, body, authentication)

    @PostMapping("/me/records/{id}/answer")
    fun grade(@PathVariable id: Long, @RequestBody body: AnswerRequest, authentication: Authentication) =
        study.grade(id, body, authentication)

    @PostMapping("/me/records/{id}/skip")
    fun skip(@PathVariable id: Long, authentication: Authentication) = study.skip(id, authentication)

    @DeleteMapping("/me/records/{id}")
    fun delete(@PathVariable id: Long, authentication: Authentication): ResponseEntity<Unit> = study.delete(id, authentication)

    @PatchMapping("/me/records/{id}/publicity")
    fun publicity(@PathVariable id: Long, @RequestBody body: RecordPublicityRequest, authentication: Authentication) =
        study.publicity(id, body, authentication)

    @GetMapping("/me/stats")
    fun stats(@RequestParam(defaultValue = "8") limit: Int, @RequestParam(defaultValue = "0") offset: Int, authentication: Authentication) =
        study.stats(limit, offset, authentication)

    @PostMapping("/me/questions")
    fun createQuestion(@RequestBody body: CreateQuestionRequest, authentication: Authentication) =
        study.createQuestion(body, authentication)
}

interface StudyWebPort {
    fun sync(limit: Int, offset: Int, authentication: Authentication): Any
    fun records(limit: Int, offset: Int, authentication: Authentication): Any
    fun clearRecords(authentication: Authentication): ResponseEntity<Unit>
    fun record(id: Long, authentication: Authentication): Any
    fun saveAnswer(id: Long, body: AnswerRequest, authentication: Authentication): Any
    fun grade(id: Long, body: AnswerRequest, authentication: Authentication): Any
    fun skip(id: Long, authentication: Authentication): Any
    fun delete(id: Long, authentication: Authentication): ResponseEntity<Unit>
    fun publicity(id: Long, body: RecordPublicityRequest, authentication: Authentication): Any
    fun stats(limit: Int, offset: Int, authentication: Authentication): Any
    fun createQuestion(body: CreateQuestionRequest, authentication: Authentication): Any
}

@Component
class StudyWebAdapter(
    private val studyUseCase: StudyUseCase,
    private val recordsUseCase: BrowseRecordsUseCase,
    private val statsUseCase: GetStudyStatsUseCase,
    private val studySyncUseCase: StudySyncUseCase,
) : StudyWebPort {
    override fun sync(limit: Int, offset: Int, authentication: Authentication) =
        studySyncUseCase.sync(authentication.principalOrThrow(), safeLimit(limit, 1000), max(0, offset))

    override fun records(limit: Int, offset: Int, authentication: Authentication) =
        recordsUseCase.records(authentication.principalOrThrow(), safeLimit(limit, 500), max(0, offset))

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

    override fun stats(limit: Int, offset: Int, authentication: Authentication) =
        statsUseCase.stats(authentication.principalOrThrow(), safeLimit(limit, 100), max(0, offset))

    override fun createQuestion(body: CreateQuestionRequest, authentication: Authentication) =
        studyUseCase.createQuestion(authentication.principalOrThrow(), body.topic)

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}
