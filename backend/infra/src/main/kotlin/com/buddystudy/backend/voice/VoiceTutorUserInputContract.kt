package com.buddystudy.backend.voice

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyTopicUserInput
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCurriculumUserInput
import com.fasterxml.jackson.databind.JsonNode

/** Bounded presentation and learner answers; neither grants study mutation authority. */
internal object VoiceTutorUserInputContract {
    const val TOOL = "request_user_input"
    private val idPattern = Regex("[A-Za-z0-9_-]{1,80}")
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    data class Option(val id: String, val label: String)
    data class Question(val id: String, val prompt: String, val selectionMode: String,
        val options: List<Option>, val allowFreeText: Boolean)
    data class Request(val title: String, val questions: List<Question>)
    data class Answer(val questionId: String, val selectedOptionIds: List<String>, val text: String)
    data class StudyTopics(val parentStudyId: Long, val topics: List<String>, val difficultyLevel: Int?)

    /** Model arguments are one of two exclusive shapes; transport envelopes use [request] separately. */
    fun validToolArguments(node: JsonNode): Boolean = when {
        !node.isObject -> false
        node.has("studyTopicProposal") -> exactFields(node, setOf("studyTopicProposal")) &&
            studyTopics(node.path("studyTopicProposal")) != null
        else -> exactFields(node, setOf("title", "questions")) && request(node) != null
    }

    fun studyTopicsArguments(node: JsonNode): StudyTopics? =
        if (validToolArguments(node) && node.has("studyTopicProposal")) studyTopics(node.path("studyTopicProposal")) else null

    fun studyTopics(node: JsonNode): StudyTopics? {
        val parent = node.path("parentStudyId")
        val difficulty = node.path("difficultyLevel")
        val topics = node.path("topics")
        if (!allowedFields(node, setOf("parentStudyId", "topics", "difficultyLevel")) ||
            !parent.isIntegralNumber || !parent.canConvertToLong() || parent.asLong() <= 0 ||
            (!difficulty.isMissingNode && (!difficulty.isIntegralNumber || !difficulty.canConvertToInt() || difficulty.asInt() !in 1..10)) ||
            !topics.isArray || topics.size() !in 1..8 || topics.any { !text(it, 200) }) return null
        val names = topics.map { it.asText().trim() }
        if (names.distinct().size != names.size) return null
        return StudyTopics(parent.asLong(), names, difficulty.takeUnless { it.isMissingNode }?.asInt())
    }

    fun studyTopicRequest(proposal: VoiceTutorStudyTopicUserInput): Request = Request(proposal.title,
        listOf(Question("study_topics", proposal.prompt, "multiple",
            proposal.topics.mapIndexed { index, topic -> Option("topic_$index", topic) }, false)))

    fun curriculumRequest(proposal: VoiceTutorCurriculumUserInput): Request = Request(displayText(proposal.title, 200),
        listOf(Question("curriculum", displayText(proposal.prompt, 500), "single",
            proposal.topics.mapIndexed { index, topic -> Option("topic_$index", displayText(topic, 200)) }, true)))

    private fun displayText(value: String, limit: Int): String {
        val clean = value.map { if (it.isISOControl() && it !in "\n\r\t") ' ' else it }.joinToString("")
        if (clean.length <= limit) return clean
        val prefix = clean.take(limit - 1).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        return prefix + "…"
    }

    fun validCorrelation(node: JsonNode): Boolean = listOf("requestId", "sessionId", "attemptId")
        .all { node.path(it).isTextual && uuidPattern.matches(node.path(it).asText()) }

    fun request(node: JsonNode): Request? {
        if (!node.isObject || node.has("studyTopicProposal") || !text(node.path("title"), 200)) return null
        val questions = node.path("questions")
        if (!questions.isArray || questions.size() !in 1..5) return null
        val parsed = questions.map { question ->
            val id = question.path("id")
            val mode = question.path("selectionMode").asText()
            val freeText = question.path("allowFreeText")
            val options = question.path("options")
            if (!exactFields(question, setOf("id", "prompt", "selectionMode", "options", "allowFreeText")) ||
                !id.isTextual || !idPattern.matches(id.asText()) || !text(question.path("prompt"), 500) ||
                mode !in setOf("single", "multiple", "text") || !freeText.isBoolean || !options.isArray ||
                (mode == "text" && (options.size() != 0 || !freeText.booleanValue())) ||
                (mode != "text" && options.size() !in 1..8)) return null
            val choices = options.map { option ->
                if (!exactFields(option, setOf("id", "label")) ||
                    !option.path("id").isTextual || !idPattern.matches(option.path("id").asText()) ||
                    !text(option.path("label"), 200)) return null
                Option(option.path("id").asText(), option.path("label").asText())
            }
            if (choices.map { it.id }.distinct().size != choices.size) return null
            Question(id.asText(), question.path("prompt").asText(), mode, choices, freeText.booleanValue())
        }
        if (parsed.map { it.id }.distinct().size != parsed.size) return null
        return Request(node.path("title").asText(), parsed)
    }

    fun answers(node: JsonNode, request: Request): List<Answer>? {
        if (!node.isArray || node.size() != request.questions.size) return null
        val parsed = node.map { answer ->
            val question = request.questions.singleOrNull { it.id == answer.path("questionId").asText() } ?: return null
            val selected = answer.path("selectedOptionIds")
            val input = answer.path("text")
            if (!selected.isArray || selected.size() > question.options.size ||
                !input.isTextual || input.asText().length > 2_000 || !safeText(input.asText()) ||
                (!question.allowFreeText && input.asText().isNotBlank())) return null
            val ids = selected.map { option ->
                if (!option.isTextual || question.options.none { it.id == option.asText() }) return null
                option.asText()
            }
            if (ids.distinct().size != ids.size || (question.selectionMode == "single" && ids.size > 1) ||
                (ids.isEmpty() && input.asText().isBlank())) return null
            Answer(question.id, ids, input.asText())
        }
        if (parsed.map { it.questionId }.distinct().size != parsed.size) return null
        return request.questions.map { question -> parsed.single { it.questionId == question.id } }
    }

    private fun text(node: JsonNode, maximum: Int) = node.isTextual && node.asText().isNotBlank() &&
        node.asText().length <= maximum && safeText(node.asText())
    private fun safeText(value: String) = value.none { it.isISOControl() && it !in "\n\r\t" }
    private fun allowedFields(node: JsonNode, allowed: Set<String>) =
        node.isObject && node.fieldNames().asSequence().all { it in allowed }
    private fun exactFields(node: JsonNode, required: Set<String>) =
        allowedFields(node, required) && node.size() == required.size

    val definition = VoiceTutorMcpToolDefinition(TOOL,
        "REQUIRED whenever you ask the learner to choose recommendations, preferences, topics, a learning direction or the next step: show selectable options in the app instead of just speaking a list or asking which one aloud. " +
            "Use exactly one argument shape: ordinary choices contain only title and questions (both required), or a topic-creation proposal contains only studyTopicProposal. Never mix these shapes or send an empty object. " +
            "Each ordinary question needs id, prompt, selectionMode, options and allowFreeText. Question IDs and option IDs use only A-Z, a-z, 0-9, underscore and hyphen, and must be unique within their question/request. " +
            "Use one request with 1-5 questions and single, multiple or text selection; allowFreeText=true accompanies ordinary preference choices so the learner can type an alternative. " +
            "Single and multiple modes require 1-8 options; text mode requires options=[] and allowFreeText=true. " +
            "Call this tool alone in its response. No option is preselected and nothing is applied until Submit. " +
            "Wait for the exact tool result; never keep speaking, call more tools, or infer answers while it is pending. " +
            "Cancellation is no consent and no answer. Ordinary questions collect preferences only and never authorize writes. " +
            "For adding several subtopics, first use suggest_study_topics for an exact saved parent, then set studyTopicProposal with parentStudyId, " +
            "the desired candidate topics. The server resolves the original main root and inherits its difficulty, even when the selected parent has a different level. Omit difficultyLevel; it is an optional legacy hint only and cannot override that saved root level. " +
            "The server replaces all form text with an exact immutable multi-selection proposal; " +
            "its explicit Submit creates only selected topics without another spoken confirmation. Do not call create_study_topics directly. " +
            "For typed alternatives first collect preferences with free text, then request a new studyTopicProposal. Other mutations retain prepare/confirm.",
        // Keep a root object for provider compatibility. Exclusive flat argument shapes
        // are enforced by validToolArguments; do not introduce a root anyOf/oneOf.
        mapOf("type" to "object", "additionalProperties" to false, "minProperties" to 1,
            "properties" to mapOf(
                "title" to (stringSchema(200) + ("description" to "Required with questions for ordinary choices. Omit for studyTopicProposal.")),
                "studyTopicProposal" to mapOf("type" to "object", "additionalProperties" to false,
                    "description" to "The only root property for a topic-creation proposal; omit title and questions.",
                    "properties" to mapOf("parentStudyId" to mapOf("type" to "integer", "minimum" to 1, "maximum" to Long.MAX_VALUE),
                        "topics" to mapOf("type" to "array", "minItems" to 1, "maxItems" to 8, "uniqueItems" to true, "items" to stringSchema(200)),
                        "difficultyLevel" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10,
                            "description" to "Optional legacy hint. Omit: the server always inherits the original main root's saved level.")),
                    "required" to listOf("parentStudyId", "topics")),
                "questions" to mapOf("type" to "array", "minItems" to 1, "maxItems" to 5,
                    "description" to "Required with title for ordinary choices. Omit for studyTopicProposal.",
                    "items" to mapOf("anyOf" to listOf(questionSchema("single"), questionSchema("multiple"), questionSchema("text"))))),
            "required" to emptyList<String>()))

    private fun stringSchema(maximum: Int) = mapOf("type" to "string", "minLength" to 1, "maxLength" to maximum)

    private fun questionSchema(mode: String): Map<String, Any> = mapOf(
        "type" to "object", "additionalProperties" to false,
        "properties" to mapOf(
            "id" to (stringSchema(80) + ("pattern" to "^[A-Za-z0-9_-]{1,80}$")),
            "prompt" to stringSchema(500),
            "selectionMode" to mapOf("type" to "string", "enum" to listOf(mode)),
            "allowFreeText" to if (mode == "text") mapOf("type" to "boolean", "enum" to listOf(true)) else mapOf("type" to "boolean"),
            "options" to mapOf("type" to "array", "minItems" to if (mode == "text") 0 else 1,
                "maxItems" to if (mode == "text") 0 else 8,
                "items" to mapOf("type" to "object", "additionalProperties" to false,
                    "properties" to mapOf("id" to (stringSchema(80) + ("pattern" to "^[A-Za-z0-9_-]{1,80}$")),
                        "label" to stringSchema(200)), "required" to listOf("id", "label")))),
        "required" to listOf("id", "prompt", "selectionMode", "options", "allowFreeText"))
}
