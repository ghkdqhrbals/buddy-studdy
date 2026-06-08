package com.buddystuddy.backend.study.adapter.inbound.web

import com.buddystuddy.backend.auth.PrincipalResolver
import com.buddystuddy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.CreateQuestionRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystuddy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystuddy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystuddy.backend.study.application.port.inbound.SnapshotUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudyUseCase
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.http.ResponseEntity
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
    fun snapshot(@RequestParam(defaultValue = "500") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        study.snapshot(limit, offset, request)

    @GetMapping("/me/records")
    fun records(@RequestParam(defaultValue = "100") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        study.records(limit, offset, request)

    @DeleteMapping("/me/records")
    fun clearRecords(request: HttpServletRequest): ResponseEntity<Unit> = study.clearRecords(request)

    @GetMapping("/me/records/{id}")
    fun record(@PathVariable id: Long, request: HttpServletRequest) = study.record(id, request)

    @PatchMapping("/me/records/{id}/answer")
    fun saveAnswer(@PathVariable id: Long, @RequestBody body: AnswerRequest, request: HttpServletRequest) =
        study.saveAnswer(id, body, request)

    @PostMapping("/me/records/{id}/answer")
    fun grade(@PathVariable id: Long, @RequestBody body: AnswerRequest, request: HttpServletRequest) =
        study.grade(id, body, request)

    @PostMapping("/me/records/{id}/skip")
    fun skip(@PathVariable id: Long, request: HttpServletRequest) = study.skip(id, request)

    @DeleteMapping("/me/records/{id}")
    fun delete(@PathVariable id: Long, request: HttpServletRequest): ResponseEntity<Unit> = study.delete(id, request)

    @PatchMapping("/me/records/{id}/publicity")
    fun publicity(@PathVariable id: Long, @RequestBody body: RecordPublicityRequest, request: HttpServletRequest) =
        study.publicity(id, body, request)

    @GetMapping("/me/stats")
    fun stats(@RequestParam(defaultValue = "8") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        study.stats(limit, offset, request)

    @PostMapping("/me/questions")
    fun createQuestion(@RequestBody body: CreateQuestionRequest, request: HttpServletRequest) =
        study.createQuestion(body, request)
}

interface StudyWebPort {
    fun snapshot(limit: Int, offset: Int, request: HttpServletRequest): Any
    fun records(limit: Int, offset: Int, request: HttpServletRequest): Any
    fun clearRecords(request: HttpServletRequest): ResponseEntity<Unit>
    fun record(id: Long, request: HttpServletRequest): Any
    fun saveAnswer(id: Long, body: AnswerRequest, request: HttpServletRequest): Any
    fun grade(id: Long, body: AnswerRequest, request: HttpServletRequest): Any
    fun skip(id: Long, request: HttpServletRequest): Any
    fun delete(id: Long, request: HttpServletRequest): ResponseEntity<Unit>
    fun publicity(id: Long, body: RecordPublicityRequest, request: HttpServletRequest): Any
    fun stats(limit: Int, offset: Int, request: HttpServletRequest): Any
    fun createQuestion(body: CreateQuestionRequest, request: HttpServletRequest): Any
}

@Component
class StudyWebAdapter(
    private val studyUseCase: StudyUseCase,
    private val recordsUseCase: BrowseRecordsUseCase,
    private val statsUseCase: GetStudyStatsUseCase,
    private val snapshotUseCase: SnapshotUseCase,
    private val principals: PrincipalResolver,
) : StudyWebPort {
    override fun snapshot(limit: Int, offset: Int, request: HttpServletRequest) =
        snapshotUseCase.snapshot(principals.authenticate(request), safeLimit(limit, 1000), max(0, offset))

    override fun records(limit: Int, offset: Int, request: HttpServletRequest) =
        recordsUseCase.records(principals.authenticate(request), safeLimit(limit, 500), max(0, offset))

    override fun clearRecords(request: HttpServletRequest): ResponseEntity<Unit> = ResponseEntity.noContent().build()

    override fun record(id: Long, request: HttpServletRequest) = recordsUseCase.record(principals.authenticate(request), id)

    override fun saveAnswer(id: Long, body: AnswerRequest, request: HttpServletRequest) =
        studyUseCase.answer(principals.authenticate(request), id, body.answer, grade = false)

    override fun grade(id: Long, body: AnswerRequest, request: HttpServletRequest) =
        studyUseCase.answer(principals.authenticate(request), id, body.answer, grade = true)

    override fun skip(id: Long, request: HttpServletRequest) = studyUseCase.skip(principals.authenticate(request), id)

    override fun delete(id: Long, request: HttpServletRequest): ResponseEntity<Unit> {
        studyUseCase.delete(principals.authenticate(request), id)
        return ResponseEntity.noContent().build()
    }

    override fun publicity(id: Long, body: RecordPublicityRequest, request: HttpServletRequest) =
        studyUseCase.publicity(principals.authenticate(request), id, body.isPublic)

    override fun stats(limit: Int, offset: Int, request: HttpServletRequest) =
        statsUseCase.stats(principals.authenticate(request), safeLimit(limit, 100), max(0, offset))

    override fun createQuestion(body: CreateQuestionRequest, request: HttpServletRequest) =
        studyUseCase.createQuestion(principals.authenticate(request), body.topic)

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}
