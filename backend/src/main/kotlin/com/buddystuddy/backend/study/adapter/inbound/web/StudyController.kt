package com.buddystuddy.backend.study.adapter.inbound.web

import com.buddystuddy.backend.auth.PrincipalService
import com.buddystuddy.backend.study.adapter.inbound.web.dto.AnswerRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.CreateQuestionRequest
import com.buddystuddy.backend.study.adapter.inbound.web.dto.RecordPublicityRequest
import com.buddystuddy.backend.study.application.port.inbound.StudyUseCase
import jakarta.servlet.http.HttpServletRequest
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
    private val study: StudyUseCase,
    private val principals: PrincipalService,
) {
    @GetMapping("/me/snapshot")
    fun snapshot(@RequestParam(defaultValue = "500") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        study.snapshot(principals.authenticate(request), safeLimit(limit, 1000), max(0, offset))

    @GetMapping("/me/records")
    fun records(@RequestParam(defaultValue = "100") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        study.records(principals.authenticate(request), safeLimit(limit, 500), max(0, offset))

    @DeleteMapping("/me/records")
    fun clearRecords(request: HttpServletRequest): ResponseEntity<Unit> = ResponseEntity.noContent().build()

    @GetMapping("/me/records/{id}")
    fun record(@PathVariable id: Long, request: HttpServletRequest) = study.record(principals.authenticate(request), id)

    @PatchMapping("/me/records/{id}/answer")
    fun saveAnswer(@PathVariable id: Long, @RequestBody body: AnswerRequest, request: HttpServletRequest) =
        study.answer(principals.authenticate(request), id, body.answer, grade = false)

    @PostMapping("/me/records/{id}/answer")
    fun grade(@PathVariable id: Long, @RequestBody body: AnswerRequest, request: HttpServletRequest) =
        study.answer(principals.authenticate(request), id, body.answer, grade = true)

    @PostMapping("/me/records/{id}/skip")
    fun skip(@PathVariable id: Long, request: HttpServletRequest) = study.skip(principals.authenticate(request), id)

    @DeleteMapping("/me/records/{id}")
    fun delete(@PathVariable id: Long, request: HttpServletRequest): ResponseEntity<Unit> {
        study.delete(principals.authenticate(request), id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/me/records/{id}/publicity")
    fun publicity(@PathVariable id: Long, @RequestBody body: RecordPublicityRequest, request: HttpServletRequest) =
        study.publicity(principals.authenticate(request), id, body.isPublic)

    @GetMapping("/me/stats")
    fun stats(@RequestParam(defaultValue = "8") limit: Int, @RequestParam(defaultValue = "0") offset: Int, request: HttpServletRequest) =
        study.stats(principals.authenticate(request), safeLimit(limit, 100), max(0, offset))

    @PostMapping("/me/questions")
    fun createQuestion(@RequestBody body: CreateQuestionRequest, request: HttpServletRequest) =
        study.createQuestion(principals.authenticate(request), body.topic)

    private fun safeLimit(value: Int, max: Int) = min(max(1, value), max)
}
