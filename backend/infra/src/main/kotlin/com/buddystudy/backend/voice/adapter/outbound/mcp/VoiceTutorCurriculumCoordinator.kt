package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCurriculumUserInput
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Prepares one real saved branch at a time; an empty child list is never a terminal-leaf decision. */
internal class VoiceTutorCurriculumCoordinator(
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val current: suspend (VoiceTutorWebRtcControlContext) -> Boolean,
    private val invoke: suspend (VoiceTutorWebRtcControlContext, String, Map<String, Any>) -> JsonNode?,
    private val focus: suspend (VoiceTutorWebRtcControlContext, Long) -> VoiceTutorMcpToolResult,
) {
    private data class Node(val id: Long, val parent: Long?, val topic: String, val difficulty: Int, val terminal: Boolean)
    private data class Proposal(val context: VoiceTutorWebRtcControlContext, val path: List<Node>, val children: List<Node>,
        val expiresAt: Instant, var submitted: Boolean = false, var selection: Pair<Int?, String>? = null,
        var result: VoiceTutorMcpToolResult? = null)
    private val proposals = linkedMapOf<String, Proposal>()

    /** Null means this exact saved node is terminal and may proceed to question work. */
    suspend fun prepare(context: VoiceTutorWebRtcControlContext, selectedId: Long): VoiceTutorMcpToolResult? {
        if (!current(context)) return error("LESSON_CONTEXT_STALE")
        var path = path(context, selectedId) ?: return error("STUDY_TREE_CHANGED")
        val selected = path.last()
        if (selected.terminal || path.size - 1 >= MAX_DEPTH) return null
        if (!context.userInputEnabled) return error("USER_INPUT_UNAVAILABLE")
        var children = children(context, selectedId) ?: return error("STUDY_TREE_CHANGED")
        val createdIds = mutableListOf<Long>()
        if (children.isEmpty()) {
            val suggested = invoke(context, "suggest_study_topics", mapOf("parent_study_id" to selectedId, "count" to 4))
                ?: return error("CURRICULUM_UNAVAILABLE")
            val names = suggested.path("suggestions").takeIf { it.isArray }?.mapNotNull {
                it.takeIf(JsonNode::isTextual)?.asText()?.takeIf { name -> name.isNotBlank() && name.length <= 200 }
            }?.distinct()?.take(4).orEmpty()
            if (names.isEmpty()) return error("CURRICULUM_UNAVAILABLE")
            val terminals = suggested.path("topicDetails").takeIf { it.isArray }?.mapNotNull {
                val topic = it.path("topic").takeIf(JsonNode::isTextual)?.asText() ?: return@mapNotNull null
                val terminal = it.path("curriculumTerminal").takeIf(JsonNode::isBoolean)?.booleanValue() ?: return@mapNotNull null
                topic to terminal
            }?.toMap().orEmpty()
            if (!current(context) || path(context, selectedId) != path) return error("STUDY_TREE_CHANGED")
            val result = invoke(context, "create_study_topics", mapOf(
                "parent_study_id" to selectedId, "topics" to names, "difficulty_level" to path.first().difficulty,
                BuddyStudyMcpPort.VOICE_INHERIT_ROOT_DIFFICULTY_ARGUMENT to true,
                BuddyStudyMcpPort.VOICE_CURRICULUM_TERMINALS_ARGUMENT to names.associateWith { terminals[it] == true || path.size == MAX_DEPTH },
                BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT to selected.topic,
                BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT to selected.difficulty,
                BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT to (selected.parent ?: 0L),
            )) ?: return error("CURRICULUM_UNAVAILABLE")
            result.path("topics").filter { it.path("created").asBoolean(false) }.mapNotNull { id(it.path("id")) }.forEach(createdIds::add)
            // Re-read after the atomic write. Saved root level and node identities remain authoritative.
            path = path(context, selectedId) ?: return changedError("STUDY_TREE_CHANGED", createdIds)
            children = children(context, selectedId) ?: return changedError("STUDY_TREE_CHANGED", createdIds)
        }
        if (!current(context) || children.isEmpty()) return changedError("CURRICULUM_UNAVAILABLE", createdIds)
        val proposalId = UUID.randomUUID().toString()
        val now = clock.instant()
        synchronized(proposals) {
            proposals.entries.removeIf { !now.isBefore(it.value.expiresAt) }
            if (proposals.size >= 256) return changedError("CURRICULUM_UNAVAILABLE", createdIds)
            proposals[proposalId] = Proposal(context, path, children, minOf(now.plusSeconds(600), context.session.hardEndsAt))
        }
        val root = path.first()
        val parent = path.last()
        val title = when (context.session.language) { "en" -> "Choose a curriculum topic"; "ja" -> "学習項目を選択"; else -> "커리큘럼에서 선택" }
        val prompt = when (context.session.language) {
            "en" -> "Main topic: ${root.topic} · Level ${root.difficulty}\nSelected topic: ${parent.topic}\nChoose a saved subtopic to study, or type a different direction."
            "ja" -> "メイン: ${root.topic} · レベル ${root.difficulty}\n選択中: ${parent.topic}\n学習する保存済み項目を選ぶか、別の希望を入力してください。"
            else -> "메인 주제: ${root.topic} · 레벨 ${root.difficulty}\n선택 주제: ${parent.topic}\n공부할 하위 주제를 고르거나 원하는 방향을 직접 입력하세요."
        }
        return VoiceTutorMcpToolResult("{}", false,
            studyTreeChanged = createdIds.isNotEmpty(), changedStudyIds = createdIds,
            changeKind = VoiceTutorStudyChangeKind.CREATED,
            curriculumInput = VoiceTutorCurriculumUserInput(proposalId, title, prompt,
                children.map { "${it.topic} · ${it.difficulty}" }))
    }

    suspend fun submit(context: VoiceTutorWebRtcControlContext, proposalId: String, selectedIndex: Int?, text: String): VoiceTutorMcpToolResult {
        val proposal = synchronized(proposals) { proposals[proposalId] }
            ?: return error("PROPOSAL_EXPIRED")
        if (!sameCall(proposal.context, context) || !clock.instant().isBefore(proposal.expiresAt))
            return error("PROPOSAL_EXPIRED")
        if (selectedIndex != null && selectedIndex !in proposal.children.indices) return error("INVALID_ARGUMENTS")
        if (text.length > 2_000 || (selectedIndex == null && text.isBlank())) return error("INVALID_ARGUMENTS")
        synchronized(proposals) {
            if (proposal.selection != null && proposal.selection != (selectedIndex to text)) return error("PROPOSAL_ALREADY_SUBMITTED")
            proposal.result?.let { return it }
            if (proposal.submitted) return error("PROPOSAL_ALREADY_SUBMITTED")
            proposal.selection = selectedIndex to text
            proposal.submitted = true
        }
        if (!current(context)) return error("PROPOSAL_EXPIRED")
        val result = submitChoice(context, proposal, selectedIndex, text)
        synchronized(proposals) { proposal.result = result }
        return result
    }

    private suspend fun submitChoice(context: VoiceTutorWebRtcControlContext, proposal: Proposal,
        selectedIndex: Int?, text: String): VoiceTutorMcpToolResult {
        if (path(context, proposal.path.last().id) != proposal.path) return error("STUDY_TREE_CHANGED")
        // Free text is preference evidence, never authority to invent or reset a saved focus.
        if (text.isNotBlank()) return VoiceTutorMcpToolResult(mapper.writeValueAsString(mapOf(
            "selected" to false, "preference" to text,
            "notice" to "The learner supplied a different preference. No focus or lesson was started. Follow this preference in conversation and request an exact saved topic only if needed; do not force the previous curriculum or create a root from this text.")), false)
        val child = proposal.children[requireNotNull(selectedIndex)]
        val freshPath = path(context, child.id) ?: return error("STUDY_TREE_CHANGED")
        if (freshPath != proposal.path + child) return error("STUDY_TREE_CHANGED")
        prepare(context, child.id)?.let { return it }
        return focus(context, child.id)
    }

    private suspend fun path(context: VoiceTutorWebRtcControlContext, id: Long): List<Node>? {
        val reversed = mutableListOf<Node>()
        var currentId: Long? = id
        while (currentId != null && reversed.size < 128) {
            if (reversed.any { it.id == currentId }) return null
            val node = invoke(context, "get_study", mapOf("study_id" to currentId,
                "language" to context.session.language))?.let(::node) ?: return null
            if (node.id != currentId) return null
            reversed += node
            currentId = node.parent
        }
        if (currentId != null) return null
        return reversed.asReversed()
    }

    private suspend fun children(context: VoiceTutorWebRtcControlContext, parent: Long): List<Node>? {
        val result = invoke(context, "list_studies", mapOf("parent_study_id" to parent,
            "limit" to 8, "offset" to 0, "language" to context.session.language)) ?: return null
        val items = result.path("studies").takeIf { it.isArray } ?: return null
        val children = items.map { node(it) ?: return null }
        if (children.size > 8 || children.any { it.parent != parent } || children.map { it.id }.distinct().size != children.size) return null
        return children
    }

    private fun node(value: JsonNode): Node? {
        val nodeId = id(value.path("id")) ?: return null
        val parentValue = value.path("parentStudyId")
        val parent = if (parentValue.isNull || parentValue.isMissingNode) null else id(parentValue) ?: return null
        val topic = value.path("topic").takeIf(JsonNode::isTextual)?.asText()?.takeIf { it.isNotBlank() && it.length <= 255 } ?: return null
        val difficulty = value.path("difficultyLevel").takeIf { it.isIntegralNumber && it.asInt() in 1..10 }?.asInt() ?: return null
        return Node(nodeId, parent, topic, difficulty, value.path("curriculumTerminal").asBoolean(false))
    }
    private fun id(node: JsonNode): Long? = node.takeIf { it.isIntegralNumber && it.canConvertToLong() && it.asLong() > 0 }?.asLong()
    private fun sameCall(a: VoiceTutorWebRtcControlContext, b: VoiceTutorWebRtcControlContext) =
        a.session.id == b.session.id && a.callId == b.callId && a.principal == b.principal && a.initialLessonRevision == b.initialLessonRevision
    private fun changedError(code: String, created: List<Long>) = error(code).copy(studyTreeChanged = created.isNotEmpty(),
        changedStudyIds = created, changeKind = VoiceTutorStudyChangeKind.CREATED)
    private fun error(code: String) = VoiceTutorMcpToolResult(mapper.writeValueAsString(mapOf("error" to mapOf(
        "code" to code, "message" to "The exact saved curriculum could not be confirmed. No question was generated; inspect the current saved topic before proceeding."))), true)
    private companion object { const val MAX_DEPTH = 4 }
}
