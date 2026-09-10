package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuestionChange
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuestionReadback
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorReviewedAnswer
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.delay
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
        var question: JsonNode? = null
        var readGeneration = 0L
        var readLearnerTurn = 0L
        var checked = false
        var generation: JsonNode? = null
        val gradings = linkedMapOf<String, Submitted>()
    }
    private data class Submitted(val record: JsonNode, val answer: String)
    private val sessions = linkedMapOf<String, State>()

    suspend fun selected(context: VoiceTutorWebRtcControlContext): VoiceTutorMcpToolResult {
        val state = state(context) ?: return unavailable()
        return pending(context, state)
    }

    suspend fun execute(context: VoiceTutorWebRtcControlContext, name: String, arguments: Map<String, Any>): VoiceTutorMcpToolResult {
        val state = state(context) ?: return unavailable()
        if (!validArguments(name, arguments, state)) return error("QUESTION_SCOPE_MISMATCH", "Use the selected topic and the exact current question returned by its tools.")
        return when (name) {
            "list_pending_questions" -> pending(context, state)
            "request_question" -> request(context, state)
            "get_question_process" -> generation(context, state, arguments.getValue("correlation_id").toString())
            "skip_question" -> skip(context, state)
            "submit_answer" -> error("USER_CONFIRMATION_REQUIRED", "Only the learner can finish, edit and submit this answer from the app. Never submit or assess unfinished speech.")
            "get_grading_process" -> grading(context, state, arguments.getValue("correlation_id").toString())
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

    private suspend fun pending(context: VoiceTutorWebRtcControlContext, state: State): VoiceTutorMcpToolResult {
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
        return output(mapOf(
            "studyId" to state.scope.study, "pendingQuestion" to state.question,
            "gradingQuestions" to records.filter { it.path("questionStatus").asText() == "GRADING" }.map {
                mapOf("id" to recordId(it).toString(), "questionStatus" to "GRADING")
            },
            "notice" to if (newlyBound) "Read pendingQuestion.question.question faithfully and wait for the learner's answer. Its original topic and difficulty stay unchanged. Do not invent another question or give its hint/answer. Wait for the learner to finish, edit and explicitly submit through the app; never call submit_answer or assess unfinished speech."
                else if (candidate != null) SAME_QUESTION_NOTICE
                else "No ready unanswered question remains on this exact topic. If the learner wants to start or explicitly requests a new question, call request_question and wait for its saved result. Do not invent a question or resubmit an answer that is already grading.",
        )).copy(questionChange = state.question?.takeIf { newlyBound }?.let { change(state, it) },
            questionReadback = state.question?.takeIf { newlyBound }?.let { readback(state, it) })
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
        if (learnerTurn(context, state.scope.revision) == null) return persistencePending()
        if (!current(context, state)) return unavailable()
        state.generation?.let { return output(mapOf("generation" to it, "notice" to "This generation is already requested. Continue get_question_process with its correlationId; do not request another question.")) }
        val checked = pending(context, state)
        if (checked.isError) return checked
        if (state.question != null) return checked
        if (!state.checked || !current(context, state)) return unavailable()
        val turn = learnerTurn(context, state.scope.revision) ?: return persistencePending()
        if (!current(context, state)) return unavailable()
        // Model-generated retry keys cannot spend quota twice for the same learner request.
        val key = "voice-" + UUID.nameUUIDFromBytes("${context.session.id}:${state.scope.study}:$turn".toByteArray())
        val result = invoke(context, "request_question", mapOf("study_id" to state.scope.study, "idempotency_key" to key))
        if (result.isError || !current(context, state)) return if (result.isError) result else unavailable()
        val body = mapper.readTree(result.output)
        if (id(body.path("topicId")) != state.scope.study || !correlation(body.path("correlationId"))) return invalidResult()
        state.generation = body
        return output(mapOf("generation" to body, "notice" to "Generation was requested through the normal question allowance. Call get_question_process with this correlationId; read only the saved question when it completes."))
    }

    private suspend fun generation(context: VoiceTutorWebRtcControlContext, state: State, correlationId: String): VoiceTutorMcpToolResult {
        if (state.generation?.path("correlationId")?.asText() != correlationId) return unavailable()
        val result = awaitProcess(context, state, "get_question_process", correlationId)
        if (result.isError) return result
        val body = mapper.readTree(result.output)
        if (body.path("terminal").asBoolean() && body.path("question").isObject) {
            val record = body.path("question")
            if (id(record.path("studyId")) != state.scope.study || recordId(record) == null || record.path("questionStatus").asText() != "UNGRADED" || !readableQuestion(record)) return invalidResult()
            val newlyBound = bind(context, state, record)
            if (!current(context, state)) return unavailable()
            state.generation = null
            return output(mapOf("terminal" to true, "pendingQuestion" to state.question,
                "notice" to if (newlyBound) "Read this saved question faithfully, then wait for an actual answer. Do not invent a score or reveal the answer hint." else SAME_QUESTION_NOTICE))
                .copy(questionChange = if (newlyBound) change(state, record) else null,
                    questionReadback = if (newlyBound) readback(state, record) else null)
        }
        if (body.path("terminal").asBoolean()) state.generation = null
        return result
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
            .copy(questionChange = change(state, record))
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
        return output(mapOf("record" to feedbackRecord(record), "notice" to "The learner explicitly finished, reviewed and submitted their edited answer. Earlier microphone transcripts may be superseded; never assess them. Call get_grading_process with gradingRequestId and read only its verified score and feedback. Do not submit again."))
            .copy(questionChange = change(state, record))
    }

    private suspend fun grading(context: VoiceTutorWebRtcControlContext, state: State, correlationId: String): VoiceTutorMcpToolResult {
        val submitted = state.gradings[correlationId] ?: return unavailable()
        val result = awaitProcess(context, state, "get_grading_process", correlationId)
        if (result.isError || !mapper.readTree(result.output).path("terminal").asBoolean()) return result
        val question = submitted.record
        val recordResult = invoke(context, "get_record", mapOf("record_id" to recordId(question)!!, "language" to context.session.language, "view" to "original"))
        if (recordResult.isError || !current(context, state)) return if (recordResult.isError) recordResult else unavailable()
        val record = mapper.readTree(recordResult.output)
        if (recordId(record) != recordId(question) || id(record.path("studyId")) != state.scope.study || record.path("answer").asText().trim() != submitted.answer.trim()) return invalidResult()
        return output(mapOf("terminal" to true, "record" to feedbackRecord(record),
            "notice" to "Use only this saved gradingResult for score and feedback, based on the learner-reviewed edited answer rather than superseded microphone text. If gradingDetailsAvailableInRecord is true, say the saved result can be read in the app. Otherwise, when gradingResult is absent, report that grading is not complete; do not invent a grade. Wait for the learner before selecting another question."))
            .copy(questionChange = change(state, record))
    }

    private suspend fun awaitProcess(context: VoiceTutorWebRtcControlContext, state: State, name: String, correlationId: String): VoiceTutorMcpToolResult {
        // One bounded tool round waits for normal async work without a rapid model polling loop.
        repeat(18) { attempt ->
            if (!current(context, state)) return unavailable()
            val result = invoke(context, name, mapOf("correlation_id" to correlationId))
            if (result.isError || !current(context, state)) return if (result.isError) result else unavailable()
            val node = mapper.readTree(result.output)
            if (node.path("correlationId").asText() != correlationId) return invalidResult()
            if (node.path("terminal").asBoolean() || attempt == 17) return result
            delay(500)
        }
        return unavailable()
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
            state.gradings[it] = Submitted(compact(record), answer)
            while (state.gradings.size > 32) state.gradings.remove(state.gradings.keys.first())
        }
    }
    private fun recoverSubmission(state: State, record: JsonNode, answer: String): VoiceTutorMcpToolResult? {
        if (recordId(record) != recordId(state.question) || id(record.path("studyId")) != state.scope.study ||
            record.path("answer").asText().trim() != answer.trim() ||
            record.path("questionStatus").asText() !in setOf("GRADING", "GRADED", "COMPLETED") || !correlation(record.path("gradingRequestId"))) return null
        rememberSubmission(state, record, answer)
        return output(mapOf("record" to feedbackRecord(record), "notice" to "This exact learner-reviewed answer was already accepted. Continue its gradingRequestId; do not submit again or assess superseded microphone text."))
            .copy(questionChange = change(state, record))
    }

    private fun validArguments(name: String, args: Map<String, Any>, state: State): Boolean = when (name) {
        "list_pending_questions" -> args.keys == setOf("study_id") && id(mapper.valueToTree(args["study_id"])) == state.scope.study
        "request_question" -> args.keys == setOf("study_id") && id(mapper.valueToTree(args["study_id"])) == state.scope.study
        "submit_answer", "skip_question" -> args.keys == setOf("record_id") && id(mapper.valueToTree(args["record_id"])) == recordId(state.question)
        "get_question_process", "get_grading_process" -> args.keys == setOf("correlation_id") && args["correlation_id"] is String
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
    private fun unavailable() = error("QUESTION_CONTEXT_UNAVAILABLE", "Select the exact topic and read its current pending question before acting; stale or other-topic work cannot continue.")
    private fun invalidResult() = error("INVALID_QUESTION_RESULT", "The saved question identity could not be verified. Do not invent a question, answer or grade.")

    companion object {
        private const val SAME_QUESTION_NOTICE = "This is the same current saved question, not a new question or a request to repeat its readback. Preserve the current question and handle the learner's present answer or explicit skip/replace request. Do not ask them to repeat an answer merely because this state was refreshed."
        val TOOLS = setOf("list_pending_questions", "request_question", "get_question_process", "skip_question", "submit_answer", "get_grading_process")
    }
}
