package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorGradingReadback
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningPhase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearningProgress
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuestionChange
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuestionReadback
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorReviewedAnswer
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Call-local identities only; all questions, submissions and grades stay in the canonical store. */
internal class VoiceTutorCanonicalQuestionCoordinator(
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val persistence: VoiceTutorPersistencePort,
    private val authorized: suspend (VoiceTutorWebRtcControlContext) -> Boolean,
    private val currentFocus: suspend (VoiceTutorWebRtcControlContext) -> Pair<Long, Long>?,
    private val learnerTurn: suspend (VoiceTutorWebRtcControlContext, Long) -> Long?,
    private val invoke: suspend (VoiceTutorWebRtcControlContext, String, Map<String, Any>) -> VoiceTutorMcpToolResult,
) {
    private data class Scope(val user: Long, val device: String, val auth: Long, val call: String, val study: Long, val revision: Long)
    private class State(val scope: Scope, val expires: Instant) {
        @Volatile var question: JsonNode? = null
        var readGeneration = 0L
        var readLearnerTurn = 0L
        var checked = false
        @Volatile var generation: JsonNode? = null
        @Volatile var completedGeneration: String? = null
        val gradings = linkedMapOf<String, Submitted>()
        @Volatile var activeGradingCorrelationId: String? = null
    }
    private data class Submitted(val record: JsonNode, val answer: String)
    private val sessions = linkedMapOf<String, State>()

    suspend fun selected(context: VoiceTutorWebRtcControlContext): VoiceTutorMcpToolResult {
        val state = state(context) ?: return unavailable()
        return pending(context, state)
    }

    suspend fun execute(context: VoiceTutorWebRtcControlContext, name: String, arguments: Map<String, Any>): VoiceTutorMcpToolResult {
        val state = state(context) ?: return if (name in GRADING_TOOLS) gradingUnavailable() else unavailable()
        if (!validArguments(name, arguments, state)) return error("QUESTION_SCOPE_MISMATCH", "Use the selected topic and the exact current question returned by its tools.")
        return when (name) {
            "list_pending_questions" -> pending(context, state)
            "request_question" -> request(context, state)
            "get_question_process" -> generation(context, state, arguments.getValue("correlation_id").toString())
            "skip_question" -> skip(context, state)
            "submit_answer" -> error("USER_CONFIRMATION_REQUIRED", "Only the learner can finish, edit and submit this answer from the app. Never submit or assess unfinished speech.")
            "get_grading_process" -> grading(context, state, arguments.getValue("correlation_id").toString(),
                arguments["record_id"]?.let { id(mapper.valueToTree(it)) })
            "get_answer_status" -> answerStatus(context, state, requireNotNull(id(mapper.valueToTree(arguments["record_id"]))),
                arguments["correlation_id"]?.toString())
            else -> unavailable()
        }
    }

    /** Native pending lookup must not reopen curriculum while a submitted record owns the flow. */
    suspend fun submittedAnswerStatus(context: VoiceTutorWebRtcControlContext, arguments: Map<String, Any>): VoiceTutorMcpToolResult? {
        val state = state(context) ?: return null
        val active = activeSubmission(state) ?: return null
        if (!validArguments("list_pending_questions", arguments, state)) return gradingUnavailable()
        return readSubmittedRecord(context, state, active.first, active.second)
    }

    suspend fun pollLearningProgress(context: VoiceTutorWebRtcControlContext, requested: VoiceTutorLearningProgress): VoiceTutorMcpToolResult {
        val state = state(context) ?: return unavailable()
        val correlationId = requested.correlationId ?: return unavailable()
        if (requested.studyId != state.scope.study) return unavailable()
        return when (requested.phase) {
            VoiceTutorLearningPhase.QUESTION_GENERATING -> {
                if (state.generation?.path("correlationId")?.asText() != correlationId || requested.recordId != null) return unavailable()
                val result = invoke(context, "get_question_process", mapOf("correlation_id" to correlationId))
                if (result.isError || !current(context, state)) return if (result.isError) result else unavailable()
                val body = mapper.readTree(result.output)
                if (body.path("correlationId").asText() != correlationId) return invalidResult()
                if (!body.path("terminal").asBoolean()) return output(emptyMap<String, Any>()).copy(learningProgress = requested)
                val record = body.path("question")
                if (record.isNull || record.isMissingNode) {
                    completeGeneration(state, correlationId)
                    return output(emptyMap<String, Any>()).copy(
                        learningProgress = requested.copy(phase = VoiceTutorLearningPhase.QUESTION_FAILED))
                }
                if (id(record.path("studyId")) != state.scope.study || recordId(record) == null ||
                    record.path("questionStatus").asText() != "UNGRADED" || !readableQuestion(record)) return invalidResult()
                // This is the accepted generation's exact durable result. Binding does not
                // authorize speech: the native controller fences delivery to its original
                // learner/lesson boundary. A later explicit lookup can recover an undelivered question.
                val newlyBound = bind(context, state, record)
                if (!current(context, state)) return unavailable()
                completeGeneration(state, correlationId)
                output(emptyMap<String, Any>()).copy(questionChange = change(state, record),
                    questionReadback = if (newlyBound) readback(state, record) else null,
                    questionReadbackRecovery = if (!newlyBound) readback(state, record) else null,
                    learningProgress = requested.copy(phase = VoiceTutorLearningPhase.QUESTION_READY, recordId = recordId(record).toString()))
            }
            VoiceTutorLearningPhase.GRADING -> {
                val submitted = activeSubmission(state)?.takeIf { it.first == correlationId }?.second ?: return gradingUnavailable()
                if (requested.recordId != recordId(submitted.record)?.toString()) return gradingUnavailable()
                val result = readSubmittedRecord(context, state, correlationId, submitted)
                if (result.isError) result else result.copy(output = "{}")
            }
            else -> unavailable()
        }
    }

    suspend fun submitReviewedAnswer(context: VoiceTutorWebRtcControlContext, reviewed: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult {
        val state = reviewedState(context, reviewed) ?: return unavailable()
        if (reviewed.text.isBlank() || reviewed.text.length > 8_000) return error("ANSWER_UNAVAILABLE", "Review a nonempty answer of at most 8000 characters before submitting.")
        return submit(context, state, reviewed)
    }

    suspend fun skipReviewedQuestion(context: VoiceTutorWebRtcControlContext, reviewed: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult {
        val state = reviewedState(context, reviewed) ?: return unavailable()
        if (reviewed.text.isNotEmpty()) return unavailable()
        if (!excludeReviewedSource(context, state, reviewed)) return persistencePending()
        if (!current(context, state)) return unavailable()
        return skipBoundQuestion(context, state)
    }

    private suspend fun reviewedState(context: VoiceTutorWebRtcControlContext, reviewed: VoiceTutorReviewedAnswer): State? {
        val state = state(context) ?: return null
        if (reviewed.studyId != state.scope.study || reviewed.lessonRevision != state.scope.revision ||
            reviewed.recordId != recordId(state.question)?.toString() ||
            runCatching { UUID.fromString(reviewed.answerId).toString() == reviewed.answerId }.getOrDefault(false).not() ||
            reviewed.learnerProviderItemIds.size > 32 || reviewed.learnerProviderItemIds.distinct().size != reviewed.learnerProviderItemIds.size ||
            reviewed.learnerProviderItemIds.any { it.isBlank() || it.length > 191 }) return null
        return state.takeIf { current(context, it) }
    }

    private suspend fun excludeReviewedSource(context: VoiceTutorWebRtcControlContext, state: State, reviewed: VoiceTutorReviewedAnswer): Boolean {
        // Typing an answer without any audio is valid. The trusted controller, never
        // model arguments, freezes the available source IDs at explicit finish.
        if (reviewed.learnerProviderItemIds.isEmpty()) return true
        val tutorId = reviewed.precedingTutorProviderItemId ?: return false
        val source = persistence.canonicalAnswerTurns(context.session.userId, context.session.id,
            reviewed.learnerProviderItemIds.last(), tutorId, state.scope.revision)
        if (source.size != reviewed.learnerProviderItemIds.size + 1 || source.firstOrNull()?.role != VoiceTutorTranscriptRole.TUTOR ||
            source.firstOrNull()?.providerItemId != tutorId || source.drop(1).any { it.role != VoiceTutorTranscriptRole.USER } ||
            source.drop(1).map { it.providerItemId } != reviewed.learnerProviderItemIds || !current(context, state)) return false
        return persistence.excludeCanonicalQuestionTurns(context.session.userId, context.session.id, source.map { it.providerItemId })
    }

    private suspend fun state(context: VoiceTutorWebRtcControlContext): State? {
        if (!context.realtimeModelTools || !authorized(context)) return null
        val principal = context.principal ?: return null
        val focus = currentFocus(context) ?: return null
        if (focus.second != context.initialLessonRevision) return null
        val scope = Scope(principal.userId, principal.deviceId, principal.sessionId, context.callId, focus.first, focus.second)
        return synchronized(sessions) {
            sessions.entries.removeIf { !clock.instant().isBefore(it.value.expires) }
            val existing = sessions[context.session.id]
            if (existing?.scope == scope) existing
            else if (existing != null || sessions.size < 256) State(scope, context.session.hardEndsAt).also { sessions[context.session.id] = it }
            else null
        }
    }

    private suspend fun current(context: VoiceTutorWebRtcControlContext, state: State): Boolean =
        authorized(context) && currentFocus(context) == (state.scope.study to state.scope.revision) &&
            synchronized(sessions) { sessions[context.session.id] === state }

    private suspend fun pending(context: VoiceTutorWebRtcControlContext, state: State, allowAdvance: Boolean = false): VoiceTutorMcpToolResult {
        // A pending-list read is not a request to leave a submitted answer. Recover
        // that exact grading record, even after failure/completion, without rebinding.
        if (!allowAdvance) activeSubmission(state)?.let { (correlationId, submitted) ->
            return readSubmittedRecord(context, state, correlationId, submitted)
        }
        val result = readPending(context, state)
        if (result.isError || !current(context, state)) return if (result.isError) result else unavailable()
        val body = mapper.readTree(result.output)
        val records = body.path("records")
        if (!records.isArray || records.any { id(it.path("studyId")) != state.scope.study || recordId(it) == null }) return invalidResult()
        val candidates = records.filter { it.path("questionStatus").asText() == "UNGRADED" && it.path("answer").asText("").isBlank() }
        val candidate = candidates.minWithOrNull(compareBy<JsonNode> { it.path("question").path("createdAt").asText() }.thenBy { recordId(it) })
        if (candidate != null && !readableQuestion(candidate)) return invalidResult()
        if (candidate == null && (!body.path("totalCount").canConvertToLong() || body.path("totalCount").asLong() > records.size())) {
            return error("PENDING_PAGE_INCOMPLETE", "More pending records remain unread. Do not invent or generate a replacement question.")
        }
        state.checked = true
        val newlyBound = if (candidate != null) bind(context, state, candidate)
        else { state.question = null; false }
        if (!current(context, state)) return unavailable()
        if (allowAdvance && newlyBound) state.activeGradingCorrelationId = null
        return output(mapOf(
            "studyId" to state.scope.study, "pendingQuestion" to state.question,
            "gradingQuestions" to records.filter { it.path("questionStatus").asText() == "GRADING" }.map {
                mapOf("id" to recordId(it).toString(), "questionStatus" to "GRADING")
            },
            "notice" to if (newlyBound) "Read pendingQuestion.question.question faithfully and wait for the learner's answer. Its original topic and difficulty stay unchanged. Do not invent another question or give its hint/answer. Wait for the learner to finish, edit and explicitly submit through the app; never call submit_answer or assess unfinished speech."
                else if (candidate != null) SAME_QUESTION_NOTICE
                else "No ready unanswered question remains on this exact topic. If the learner wants to start or explicitly requests a new question, call request_question and wait for its saved result. Do not invent a question or resubmit an answer that is already grading.",
        )).copy(questionChange = state.question?.takeIf { newlyBound }?.let { change(state, it) },
            questionReadback = state.question?.takeIf { newlyBound }?.let { readback(state, it) },
            questionReadbackRecovery = state.question?.takeUnless { newlyBound }?.let { readback(state, it) },
            learningProgress = if (!newlyBound) progress(state, VoiceTutorLearningPhase.CONVERSATION) else null)
    }

    private suspend fun readPending(context: VoiceTutorWebRtcControlContext, state: State): VoiceTutorMcpToolResult {
        val first = invoke(context, "list_pending_questions", mapOf("study_id" to state.scope.study, "limit" to 10, "offset" to 0))
        if (!current(context, state)) return unavailable()
        if (!first.isError || mapper.readTree(first.output).path("error").path("code").asText() != "RESULT_TOO_LARGE") return first
        // Keep the normal small pending set complete when its combined payload is too large.
        // Offsets and page sizes belong to this bounded server read, never to model arguments.
        val records = mutableListOf<JsonNode>()
        var total: Long? = null
        repeat(10) { offset ->
            if (!current(context, state)) return unavailable()
            val page = invoke(context, "list_pending_questions", mapOf("study_id" to state.scope.study, "limit" to 1, "offset" to offset))
            if (page.isError || !current(context, state)) return if (page.isError) page else unavailable()
            val body = mapper.readTree(page.output)
            val count = body.path("totalCount")
            val items = body.path("records")
            if (!count.isIntegralNumber || !count.canConvertToLong() || count.asLong() < 0 || !items.isArray || items.size() > 1) return invalidResult()
            val pageTotal = count.asLong()
            if (total != null && total != pageTotal) return pendingPageIncomplete()
            total = pageTotal
            if (pageTotal > 10 || pageTotal < offset + items.size() || (items.isEmpty && pageTotal > offset)) return pendingPageIncomplete()
            if (items.any { item -> id(item.path("studyId")) != state.scope.study || recordId(item) == null || records.any { recordId(it) == recordId(item) } }) return invalidResult()
            records += items.toList()
            if (records.size.toLong() == pageTotal) return output(mapOf("records" to records, "totalCount" to pageTotal))
        }
        return pendingPageIncomplete()
    }

    private suspend fun request(context: VoiceTutorWebRtcControlContext, state: State): VoiceTutorMcpToolResult {
        if (context.operationStillCurrent?.invoke() == false) return staleQuestionRequest()
        if (learnerTurn(context, state.scope.revision) == null) return persistencePending()
        if (!current(context, state)) return unavailable()
        state.generation?.let { return output(mapOf("generation" to it, "notice" to "This generation is already requested. The server subscribes to its completion and will deliver the saved question; do not poll, request another question or ask the learner to start again."))
            .copy(learningProgress = progress(state, VoiceTutorLearningPhase.QUESTION_GENERATING)) }
        val checked = pending(context, state, allowAdvance = true)
        if (checked.isError) return checked
        if (state.question != null) return checked
        if (!state.checked || !current(context, state)) return unavailable()
        val turn = learnerTurn(context, state.scope.revision) ?: return persistencePending()
        if (!current(context, state)) return unavailable()
        // The pending lookup can suspend. Re-check the owning accepted turn before
        // spending quota; accepted jobs after the write remain idempotent and saved.
        if (context.operationStillCurrent?.invoke() == false) return staleQuestionRequest()
        // Model-generated retry keys cannot spend quota twice for the same learner request.
        val key = "voice-" + UUID.nameUUIDFromBytes("${context.session.id}:${state.scope.study}:$turn".toByteArray())
        val result = invoke(context, "request_question", mapOf("study_id" to state.scope.study, "idempotency_key" to key))
        if (result.isError || !current(context, state)) return if (result.isError) result else unavailable()
        val body = mapper.readTree(result.output)
        if (id(body.path("topicId")) != state.scope.study || !correlation(body.path("correlationId"))) return invalidResult()
        state.completedGeneration = null
        state.generation = body
        state.activeGradingCorrelationId = null
        return output(mapOf("generation" to body, "notice" to "Generation was requested through the normal question allowance. The server subscribes to completion and delivers the saved question. Do not poll or ask the learner to repeat the start request."))
            .copy(learningProgress = progress(state, VoiceTutorLearningPhase.QUESTION_GENERATING))
    }

    private fun staleQuestionRequest() = error("STALE_TURN",
        "A newer learner request replaced this unstarted question continuation. No new question was requested; follow the latest request.")

    private suspend fun generation(context: VoiceTutorWebRtcControlContext, state: State, correlationId: String): VoiceTutorMcpToolResult {
        if (state.generation?.path("correlationId")?.asText() != correlationId &&
            !(state.generation == null && state.completedGeneration == correlationId)) return unavailable()
        val result = awaitProcess(context, state, "get_question_process", correlationId)
        if (result.isError) return result
        val body = mapper.readTree(result.output)
        if (body.path("terminal").asBoolean() && body.path("question").isObject) {
            val record = body.path("question")
            if (id(record.path("studyId")) != state.scope.study || recordId(record) == null || record.path("questionStatus").asText() != "UNGRADED" || !readableQuestion(record)) return invalidResult()
            val newlyBound = bind(context, state, record)
            if (!current(context, state)) return unavailable()
            completeGeneration(state, correlationId)
            return output(mapOf("terminal" to true, "pendingQuestion" to state.question,
                "notice" to if (newlyBound) "Read this saved question faithfully, then wait for an actual answer. Do not invent a score or reveal the answer hint." else SAME_QUESTION_NOTICE))
                .copy(questionChange = if (newlyBound) change(state, record) else null,
                    questionReadback = if (newlyBound) readback(state, record) else null,
                    questionReadbackRecovery = if (!newlyBound) readback(state, record) else null)
        }
        if (body.path("terminal").asBoolean()) completeGeneration(state, correlationId)
        return result.copy(learningProgress = progress(state, if (body.path("terminal").asBoolean())
            VoiceTutorLearningPhase.QUESTION_FAILED else VoiceTutorLearningPhase.QUESTION_GENERATING).copy(correlationId = correlationId))
    }

    private suspend fun skip(context: VoiceTutorWebRtcControlContext, state: State): VoiceTutorMcpToolResult {
        val question = state.question ?: return unavailable()
        val turn = learnerTurn(context, state.scope.revision) ?: return persistencePending()
        if (turn <= state.readLearnerTurn || !current(context, state)) return error("FRESH_LEARNER_REQUEST_REQUIRED", "Wait for the learner's request about the question just read.")
        return skipBoundQuestion(context, state)
    }

    private suspend fun skipBoundQuestion(context: VoiceTutorWebRtcControlContext, state: State): VoiceTutorMcpToolResult {
        val question = state.question ?: return unavailable()
        val result = invoke(context, "skip_question", mapOf("record_id" to recordId(question)!!))
        if (result.isError || !current(context, state)) return if (result.isError) result else unavailable()
        val record = mapper.readTree(result.output)
        if (recordId(record) != recordId(question) || id(record.path("studyId")) != state.scope.study || record.path("questionStatus").asText() != "SKIPPED") return invalidResult()
        state.question = null
        state.checked = false
        state.generation = null
        return output(mapOf("skipped" to true, "recordId" to recordId(record).toString(),
            "notice" to "This exact unanswered question was skipped. Check list_pending_questions for the next arrived question. If none remains and the learner wants another, use request_question; skipping itself creates no question and costs no question allowance."))
            .copy(questionChange = change(state, record), learningProgress = progress(state, VoiceTutorLearningPhase.CONVERSATION))
    }

    private suspend fun submit(context: VoiceTutorWebRtcControlContext, state: State, reviewed: VoiceTutorReviewedAnswer): VoiceTutorMcpToolResult {
        val question = state.question ?: return unavailable()
        val answer = reviewed.text
        val existing = invoke(context, "get_record", mapOf("record_id" to recordId(question)!!, "language" to context.session.language, "view" to "original"))
        if (existing.isError || !current(context, state)) return if (existing.isError) existing else unavailable()
        val existingRecord = mapper.readTree(existing.output)
        if (recordId(existingRecord) != recordId(question) || id(existingRecord.path("studyId")) != state.scope.study) return invalidResult()
        if (existingRecord.path("questionStatus").asText() != "UNGRADED" || !existingRecord.path("answer").asText("").isBlank()) {
            val recovered = recoverSubmission(state, existingRecord, answer)
                ?: return error("QUESTION_ALREADY_HANDLED", "This question was already skipped or answered. Do not overwrite its saved answer.")
            if (!excludeReviewedSource(context, state, reviewed)) return persistencePending()
            return if (current(context, state)) recovered else unavailable()
        }
        // The explicit UI text is authoritative. Original ASR remains private and is
        // excluded as duplicate learning evidence, never rewritten into the edited text.
        if (!excludeReviewedSource(context, state, reviewed)) return persistencePending()
        if (!current(context, state)) return unavailable()
        val result = invoke(context, "submit_answer", mapOf("record_id" to recordId(question)!!, "answer" to answer, "source_language" to context.session.language))
        if (!current(context, state)) return unavailable()
        if (result.isError) {
            val recovered = invoke(context, "get_record", mapOf("record_id" to recordId(question)!!, "language" to context.session.language, "view" to "original"))
            if (!current(context, state)) return unavailable()
            if (!recovered.isError) recoverSubmission(state, mapper.readTree(recovered.output), answer)?.let { return it }
            return result
        }
        val record = mapper.readTree(result.output)
        if (recordId(record) != recordId(question) || id(record.path("studyId")) != state.scope.study || record.path("answer").asText().trim() != answer.trim()) return invalidResult()
        rememberSubmission(state, record, answer)
        return output(mapOf("record" to feedbackRecord(record), "notice" to "The reviewed answer is saved; prior microphone text may be superseded. Grading completion arrives automatically. Do not poll, resubmit or invent feedback."))
            .copy(questionChange = change(state, record), learningProgress = gradingProgress(state, record),
                gradingReadback = gradingReadback(state, record, gradingProgress(state, record)))
    }

    private fun activeSubmission(state: State): Pair<String, Submitted>? = synchronized(state.gradings) {
        state.activeGradingCorrelationId?.let { correlation -> state.gradings[correlation]?.let { correlation to it } }
    }

    private suspend fun grading(context: VoiceTutorWebRtcControlContext, state: State, correlationId: String,
        expectedRecordId: Long? = null): VoiceTutorMcpToolResult {
        val submitted = activeSubmission(state)?.takeIf { it.first == correlationId }?.second ?: return gradingUnavailable()
        if (expectedRecordId != null && expectedRecordId != recordId(submitted.record)) return gradingUnavailable()
        return readSubmittedRecord(context, state, correlationId, submitted)
    }

    private suspend fun answerStatus(context: VoiceTutorWebRtcControlContext, state: State, expectedRecordId: Long,
        correlationId: String?): VoiceTutorMcpToolResult {
        val active = activeSubmission(state)?.takeIf { recordId(it.second.record) == expectedRecordId &&
            (correlationId == null || correlationId == it.first) } ?: return gradingUnavailable()
        return readSubmittedRecord(context, state, active.first, active.second)
    }

    private suspend fun readSubmittedRecord(context: VoiceTutorWebRtcControlContext, state: State,
        correlationId: String, submitted: Submitted): VoiceTutorMcpToolResult {
        val expected = requireNotNull(recordId(submitted.record))
        if (!current(context, state) || state.activeGradingCorrelationId != correlationId) return gradingUnavailable()
        // The owned original record is authoritative even if the event/process endpoint
        // failed. Never gate completed feedback on a progress-stream lookup.
        val result = invoke(context, "get_record", mapOf("record_id" to expected,
            "language" to context.session.language, "view" to "original"))
        if (!current(context, state) || state.activeGradingCorrelationId != correlationId) return gradingUnavailable()
        if (result.isError) return error("GRADING_STATUS_UNAVAILABLE",
            "The saved status of this submitted answer could not be read. Keep this exact record and retry get_answer_status; do not read pending questions, generate a replacement or resubmit the answer.")
        val record = mapper.readTree(result.output)
        if (recordId(record) != expected || id(record.path("studyId")) != state.scope.study ||
            record.path("gradingRequestId").asText() != correlationId ||
            record.path("answer").asText().trim() != submitted.answer.trim() ||
            record.path("question").path("question") != submitted.record.path("question").path("question"))
            return error("GRADING_RESULT_MISMATCH", "The saved result does not match this exact submitted question, answer and grading attempt. Keep the current answer; do not switch questions or invent feedback.")
        val progress = gradingProgress(state, record)
        return output(mapOf("terminal" to (progress.phase != VoiceTutorLearningPhase.GRADING),
            "record" to feedbackRecord(record), "notice" to
                "This is the saved status for the exact submitted record. Use only its gradingResult for feedback; missing feedback is not a completed grade. Keep this question and answer. Do not use list_pending_questions, resubmit or start another question for a grading status request."))
            .copy(questionChange = if (progress.phase != VoiceTutorLearningPhase.GRADING) change(state, record) else null,
                learningProgress = progress, gradingReadback = gradingReadback(state, record, progress))
    }

    private fun gradingReadback(state: State, record: JsonNode, progress: VoiceTutorLearningProgress): VoiceTutorGradingReadback? {
        if (progress.phase != VoiceTutorLearningPhase.GRADED) return null
        val grade = record.path("gradingResult")
        val feedback = grade.path("feedback").takeIf { it.isTextual }?.asText().orEmpty()
        val explanation = grade.path("explanation").takeIf { it.isTextual }?.asText().orEmpty()
        return VoiceTutorGradingReadback(state.scope.study, requireNotNull(recordId(record)).toString(),
            requireNotNull(progress.correlationId), grade.path("score").takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() in 0..100 }?.asInt(),
            feedback.takeIf { it.length <= 8_000 }.orEmpty(), explanation.takeIf { it.length <= 8_000 }.orEmpty(),
            detailsAvailableInRecord = feedback.length > 8_000 || explanation.length > 8_000)
    }

    private fun progress(state: State, phase: VoiceTutorLearningPhase, record: JsonNode? = null) =
        VoiceTutorLearningProgress(phase, state.scope.study, record?.let(::recordId)?.toString(),
            if (record != null) record.path("gradingRequestId").takeIf(::correlation)?.asText()
            else if (phase == VoiceTutorLearningPhase.QUESTION_GENERATING) state.generation?.path("correlationId")?.asText() else null)

    private fun gradingProgress(state: State, record: JsonNode): VoiceTutorLearningProgress {
        // Process completion alone is not a grade. Only the owned saved record
        // with its final feedback may announce success; failures stay explicit.
        val phase = when {
            record.path("questionStatus").asText() == "FAILED" || record.path("gradingStatus").asText() == "FAILED" -> VoiceTutorLearningPhase.GRADING_FAILED
            record.path("questionStatus").asText() in setOf("GRADED", "COMPLETED") && record.path("gradingResult").isObject -> VoiceTutorLearningPhase.GRADED
            record.path("questionStatus").asText() == "GRADING" -> VoiceTutorLearningPhase.GRADING
            else -> VoiceTutorLearningPhase.GRADING_FAILED
        }
        return progress(state, phase, record)
    }

    private fun completeGeneration(state: State, correlationId: String) = synchronized(state) {
        if (state.generation?.path("correlationId")?.asText() == correlationId) {
            state.generation = null
            state.completedGeneration = correlationId
        }
    }

    private suspend fun awaitProcess(context: VoiceTutorWebRtcControlContext, state: State, name: String, correlationId: String): VoiceTutorMcpToolResult {
        // Explicit status/recovery reads take one snapshot; event subscriptions own waiting.
        if (!current(context, state)) return unavailable()
        val result = invoke(context, name, mapOf("correlation_id" to correlationId))
        if (result.isError || !current(context, state)) return if (result.isError) result else unavailable()
        if (mapper.readTree(result.output).path("correlationId").asText() != correlationId) return invalidResult()
        return result
    }

    private suspend fun bind(context: VoiceTutorWebRtcControlContext, state: State, record: JsonNode): Boolean {
        val previous = state.question
        val unchanged = previous != null && recordId(previous) == recordId(record) &&
            previous.path("question").path("question") == record.path("question").path("question") &&
            previous.path("difficulty") == record.path("difficulty")
        state.question = compact(record)
        // Refreshing the same saved question must not consume the current answer/skip request.
        if (unchanged) return false
        state.readGeneration = context.dialogueBoundary?.responseGeneration ?: 0
        state.readLearnerTurn = learnerTurn(context, state.scope.revision) ?: 0
        return true
    }

    private fun rememberSubmission(state: State, record: JsonNode, answer: String) {
        record.path("gradingRequestId").takeIf(::correlation)?.asText()?.let {
            synchronized(state.gradings) {
                state.gradings[it] = Submitted(compact(record), answer)
                state.activeGradingCorrelationId = it
                while (state.gradings.size > 32) state.gradings.remove(state.gradings.keys.first())
            }
        }
    }
    private fun recoverSubmission(state: State, record: JsonNode, answer: String): VoiceTutorMcpToolResult? {
        if (recordId(record) != recordId(state.question) || id(record.path("studyId")) != state.scope.study ||
            record.path("answer").asText().trim() != answer.trim() ||
            record.path("questionStatus").asText() !in setOf("GRADING", "GRADED", "COMPLETED", "FAILED") || !correlation(record.path("gradingRequestId"))) return null
        rememberSubmission(state, record, answer)
        return output(mapOf("record" to feedbackRecord(record), "notice" to "This exact learner-reviewed answer was already accepted. Continue its gradingRequestId; do not submit again or assess superseded microphone text."))
            .copy(questionChange = change(state, record), learningProgress = gradingProgress(state, record),
                gradingReadback = gradingReadback(state, record, gradingProgress(state, record)))
    }

    private fun validArguments(name: String, args: Map<String, Any>, state: State): Boolean = when (name) {
        "list_pending_questions" -> args.keys == setOf("study_id") && id(mapper.valueToTree(args["study_id"])) == state.scope.study
        "request_question" -> args.keys == setOf("study_id") && id(mapper.valueToTree(args["study_id"])) == state.scope.study
        "submit_answer", "skip_question" -> args.keys == setOf("record_id") && id(mapper.valueToTree(args["record_id"])) == recordId(state.question)
        "get_question_process" -> args.keys == setOf("correlation_id") && correlation(mapper.valueToTree(args["correlation_id"]))
        "get_grading_process" -> args.keys.all { it in setOf("correlation_id", "record_id", "after_event_id") } &&
            correlation(mapper.valueToTree(args["correlation_id"])) &&
            (!args.containsKey("record_id") || id(mapper.valueToTree(args["record_id"])) != null) &&
            // Older open native sessions advertised this cursor. Accept it without
            // replaying events: the exact submitted record remains authoritative.
            (!args.containsKey("after_event_id") || mapper.valueToTree<JsonNode>(args["after_event_id"]).let {
                it.isIntegralNumber && it.canConvertToLong() && it.asLong() >= 0
            })
        "get_answer_status" -> args.keys.all { it in setOf("record_id", "correlation_id") } &&
            id(mapper.valueToTree(args["record_id"])) != null &&
            (!args.containsKey("correlation_id") || correlation(mapper.valueToTree(args["correlation_id"])))
        else -> false
    }
    private fun feedbackRecord(record: JsonNode): JsonNode = mapper.createObjectNode().apply {
        // The canonical grader receives the full edited text. Keep model tool output
        // small and avoid showing an earlier ASR answer or duplicating a long question.
        listOf("id", "studyId", "topic", "difficulty", "questionStatus", "gradingRequestId", "gradingStatus", "gradingResult", "gradingError").forEach { key -> record.get(key)?.let { set<JsonNode>(key, it) } }
        // Include the corrected answer when it fits completely; never present a
        // cut-off excerpt as the learner's entire final answer.
        record.get("answer")?.let { answer ->
            set<JsonNode>("answer", answer)
            if (mapper.writeValueAsBytes(this).size > 12 * 1024) {
                remove("answer")
                put("answerOmittedForSize", true)
            }
        }
        if (mapper.writeValueAsBytes(this).size > 12 * 1024) {
            remove("gradingResult")
            remove("gradingError")
            put("gradingDetailsAvailableInRecord", true)
        }
    }
    private fun compact(record: JsonNode): JsonNode = mapper.createObjectNode().apply {
        listOf("id", "studyId", "topic", "difficulty", "questionStatus", "answer", "gradingRequestId", "gradingStatus", "gradingResult", "gradingError").forEach { key -> record.get(key)?.let { set<JsonNode>(key, it) } }
        set<JsonNode>("question", mapper.createObjectNode().apply {
            record.path("question").get("question")?.let { set<JsonNode>("question", it) }
            record.path("question").get("createdAt")?.let { set<JsonNode>("createdAt", it) }
        })
    }
    private fun change(state: State, record: JsonNode) = VoiceTutorQuestionChange(state.scope.study, recordId(record)!!.toString())
    private fun readback(state: State, record: JsonNode) = VoiceTutorQuestionReadback(state.scope.study, recordId(record)!!.toString(), record.path("question").path("question").asText())
    private fun recordId(record: JsonNode?): Long? = record?.let { id(it.path("id")) }
    private fun readableQuestion(record: JsonNode): Boolean = record.path("question").path("question").let {
        it.isTextual && it.asText().isNotBlank() && it.asText().length <= 8_000
    }
    private fun id(node: JsonNode): Long? = when {
        node.isIntegralNumber && node.canConvertToLong() -> node.longValue().takeIf { it > 0 }
        node.isTextual && node.asText().matches(Regex("[1-9][0-9]{0,18}")) -> node.asText().toLongOrNull()
        else -> null
    }
    private fun correlation(node: JsonNode) = node.isTextual && node.asText().length in 1..100
    private fun output(value: Any) = VoiceTutorMcpToolResult(mapper.writeValueAsString(value), false)
    private fun error(code: String, message: String) = VoiceTutorMcpToolResult(mapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))), true)
    private fun persistencePending() = error("INPUT_PERSISTENCE_PENDING", "The exact original speech is still being saved. Retry internally without asking the learner to repeat or inventing text.")
    private fun pendingPageIncomplete() = error("PENDING_PAGE_INCOMPLETE", "The complete pending question set could not be read consistently. Retry its read before generating a replacement.")
    private fun gradingUnavailable() = error("GRADING_CONTEXT_UNAVAILABLE", "Use get_answer_status with the exact submitted record_id from this conversation and its grading attempt. Do not select another topic, read pending questions or resubmit to recover a grade.")
    private fun unavailable() = error("QUESTION_CONTEXT_UNAVAILABLE", "Select the exact topic and read its current pending question before acting; stale or other-topic work cannot continue.")
    private fun invalidResult() = error("INVALID_QUESTION_RESULT", "The saved question identity could not be verified. Do not invent a question, answer or grade.")

    companion object {
        private const val SAME_QUESTION_NOTICE = "This is the same current saved question, not a new question or a request to repeat its readback. Preserve the current question and handle the learner's present answer or explicit skip/replace request. Do not ask them to repeat an answer merely because this state was refreshed."
        private val GRADING_TOOLS = setOf("get_grading_process", "get_answer_status")
        val TOOLS = setOf("list_pending_questions", "request_question", "get_question_process", "skip_question", "submit_answer") + GRADING_TOOLS
    }
}
