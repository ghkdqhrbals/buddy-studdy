package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VoiceTutorCurriculumCoordinatorTest {
    @Test
    fun `empty selected descendant expands itself using original root level without question work`(): Unit = runBlocking {
        val f = Fixture()
        f.add(1, null, "MSA", 8)
        f.add(2, 1, "서비스 통신", 3)
        val result = f.coordinator.prepare(f.context, 2)!!
        val form = result.curriculumInput!!
        assertThat(form.prompt).contains("메인 주제: MSA", "레벨 8", "선택 주제: 서비스 통신")
        assertThat(form.topics).containsExactly("이벤트 전달 보장 · 8", "동기 호출 실패 처리 · 8")
        val write = f.calls.single { it.first == "create_study_topics" }.second
        assertThat(write["parent_study_id"]).isEqualTo(2L)
        assertThat(write["difficulty_level"]).isEqualTo(8)
        assertThat(write[BuddyStudyMcpPort.VOICE_INHERIT_ROOT_DIFFICULTY_ARGUMENT]).isEqualTo(true)
        assertThat(f.nodes.getValue(2).level).isEqualTo(3)
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.changedStudyIds).containsExactly(100L, 101L)
        assertThat(f.focused).isEmpty()
        val selected = f.coordinator.submit(f.context, form.proposalId, 0, "")
        assertThat(selected.isError).isFalse()
        assertThat(f.focused).containsExactly(100L)
        assertThat(f.calls.none { it.first in setOf("request_question", "list_pending_questions") }).isTrue()
    }

    @Test
    fun `actual terminal skips children and legacy depth beyond four remains readable`(): Unit = runBlocking {
        val terminal = Fixture()
        terminal.add(1, null, "MSA", 8)
        terminal.add(2, 1, "이벤트 중복 방지", 8, terminal = true)
        assertThat(terminal.coordinator.prepare(terminal.context, 2)).isNull()
        assertThat(terminal.calls.all { it.first == "get_study" }).isTrue()
        val legacy = Fixture()
        (1L..7L).forEach { id -> legacy.add(id, if (id == 1L) null else id - 1, "기존 주제 $id", 8) }
        assertThat(legacy.coordinator.prepare(legacy.context, 7)).isNull()
        assertThat(legacy.calls.all { it.first == "get_study" }).isTrue()
    }

    @Test
    fun `existing children are offered without creation and their levels are preserved`(): Unit = runBlocking {
        val f = Fixture()
        f.add(1, null, "Redis", 8)
        f.add(2, 1, "메모리", 5)
        f.add(3, 2, "만료 정책", 4, terminal = true)
        val form = f.coordinator.prepare(f.context, 2)!!.curriculumInput!!
        assertThat(form.topics).containsExactly("만료 정책 · 4")
        assertThat(f.calls.none { it.first in setOf("create_study_topics", "suggest_study_topics") }).isTrue()
        assertThat(f.coordinator.submit(f.context, form.proposalId, 0, "").isError).isFalse()
        assertThat(f.focused).containsExactly(3L)
        assertThat(f.nodes.getValue(3).level).isEqualTo(4)
    }

    @Test
    fun `selected broad child yields another real card and depth four children are terminal`(): Unit = runBlocking {
        val f = Fixture().apply { recommendedTerminal = false }
        f.add(1, null, "MSA", 8)
        f.add(2, 1, "통신", 3)
        f.add(3, 2, "이벤트", 3)
        f.add(4, 3, "전달 보장", 3)
        val first = f.coordinator.prepare(f.context, 3)!!.curriculumInput!!
        val next = f.coordinator.submit(f.context, first.proposalId, 0, "")
        assertThat(next.curriculumInput).isNotNull()
        assertThat(f.focused).isEmpty()
        val terminals = f.calls.single { it.first == "create_study_topics" }.second[
            BuddyStudyMcpPort.VOICE_CURRICULUM_TERMINALS_ARGUMENT] as Map<*, *>
        assertThat(terminals.values).containsOnly(true)
        val second = next.curriculumInput!!
        assertThat(f.coordinator.submit(f.context, second.proposalId, 0, "").isError).isFalse()
        assertThat(f.focused).containsExactly(100L)
        assertThat(f.calls.count { it.first == "create_study_topics" }).isEqualTo(1)
    }

    @Test
    fun `custom direction never selects the old option and foreign or changed proposals cannot focus`(): Unit = runBlocking {
        val f = Fixture()
        f.add(1, null, "MSA", 8)
        f.add(2, 1, "통신", 8, terminal = true)
        val form = f.coordinator.prepare(f.context, 1)!!.curriculumInput!!
        assertThat(f.coordinator.submit(f.context.copy(callId = "another-call"), form.proposalId, 0, "").isError).isTrue()
        assertThat(f.coordinator.submit(f.context, form.proposalId, 0, "다른 주제를 먼저 고를게요").isError).isFalse()
        assertThat(f.focused).isEmpty()
        val changed = f.coordinator.prepare(f.context, 1)!!.curriculumInput!!
        f.nodes[2] = f.nodes.getValue(2).copy(parent = null)
        assertThat(f.coordinator.submit(f.context, changed.proposalId, 0, "").isError).isTrue()
        assertThat(f.focused).isEmpty()
    }

    @Test
    fun `new learner direction while recommendations run prevents a stale curriculum write`(): Unit = runBlocking {
        val f = Fixture()
        f.add(1, null, "MSA", 8)
        f.afterInvoke = { if (it == "suggest_study_topics") f.authorized = false }
        assertThat(f.coordinator.prepare(f.context, 1)!!.isError).isTrue()
        assertThat(f.calls.none { it.first == "create_study_topics" }).isTrue()
        assertThat(f.focused).isEmpty()
    }

    @Test
    fun `long saved names fit native form bounds without losing exact selected node`(): Unit = runBlocking {
        val f = Fixture()
        f.add(1, null, "가".repeat(255), 8)
        f.add(2, 1, "나".repeat(255), 8)
        f.add(3, 2, "다".repeat(255), 8, terminal = true)
        val form = f.coordinator.prepare(f.context, 2)!!.curriculumInput!!
        val wire = com.buddystudy.backend.voice.VoiceTutorUserInputContract.curriculumRequest(form)
        assertThat(com.buddystudy.backend.voice.VoiceTutorUserInputContract.request(f.mapper.valueToTree(wire))).isEqualTo(wire)
        assertThat(wire.questions.single().prompt.length).isLessThanOrEqualTo(500)
        assertThat(wire.questions.single().options.single().label.length).isLessThanOrEqualTo(200)
        assertThat(f.coordinator.submit(f.context, form.proposalId, 0, "").isError).isFalse()
        assertThat(f.focused).containsExactly(3L)
        assertThat(f.nodes.getValue(3).topic).hasSize(255)
    }

    private data class Node(val id: Long, val parent: Long?, val topic: String, val level: Int, val terminal: Boolean) {
        fun fields() = mapOf("id" to id, "parentStudyId" to parent, "topic" to topic,
            "difficultyLevel" to level, "curriculumTerminal" to terminal)
    }

    private class Fixture {
        val now: Instant = Instant.parse("2026-09-10T21:00:00Z")
        val mapper = jacksonObjectMapper()
        val nodes = linkedMapOf<Long, Node>()
        val calls = mutableListOf<Pair<String, Map<String, Any>>>()
        val focused = mutableListOf<Long>()
        var authorized = true
        var recommendedTerminal = true
        var afterInvoke: (String) -> Unit = {}
        private var nextId = 100L
        val context = VoiceTutorWebRtcControlContext(
            session = VoiceTutorSession(
                id = "curriculum-session", userId = 7, studyId = null, idempotencyKey = "reserved", providerSessionId = "provider",
                status = VoiceTutorSessionStatus.ACTIVE, resultStatus = VoiceTutorResultStatus.PENDING,
                language = "ko", model = "realtime", voice = "marin", topic = "", difficulty = 5,
                periodStartedAt = now, periodEndsAt = now.plusSeconds(86_400), reservedSeconds = 600, chargedSeconds = 0,
                maxSessionSeconds = 600, hardEndsAt = now.plusSeconds(600), connectedAt = now, relayHeartbeatAt = now,
                acceptedAudioBytes = 0, endedAt = null, finalizedAt = null, endReason = null, failureCode = null,
                failureMessage = null, createdAt = now, updatedAt = now),
            callId = "call", principal = Principal(7, "device", 1, false), realtimeModelTools = true, userInputEnabled = true)
        val coordinator = VoiceTutorCurriculumCoordinator(mapper, Clock.fixed(now, ZoneOffset.UTC),
            current = { authorized },
            invoke = { _, name, args ->
                calls += name to args
                val result: JsonNode? = when (name) {
                    "get_study" -> nodes[(args.getValue("study_id") as Number).toLong()]?.let { mapper.valueToTree(it.fields()) }
                    "list_studies" -> mapper.valueToTree(mapOf("studies" to nodes.values.filter {
                        it.parent == (args.getValue("parent_study_id") as Number).toLong()
                    }.take(8).map(Node::fields)))
                    "suggest_study_topics" -> mapper.valueToTree(mapOf("suggestions" to listOf("이벤트 전달 보장", "동기 호출 실패 처리"),
                        "topicDetails" to listOf("이벤트 전달 보장", "동기 호출 실패 처리").map {
                            mapOf("topic" to it, "curriculumTerminal" to recommendedTerminal)
                        }))
                    "create_study_topics" -> {
                        val parent = (args.getValue("parent_study_id") as Number).toLong()
                        val level = (args.getValue("difficulty_level") as Number).toInt()
                        val terminals = args.getValue(BuddyStudyMcpPort.VOICE_CURRICULUM_TERMINALS_ARGUMENT) as Map<*, *>
                        val saved = (args.getValue("topics") as List<*>).map {
                            val topic = it as String
                            val node = Node(nextId++, parent, topic, level, terminals[topic] == true)
                            nodes[node.id] = node
                            node.fields() + ("created" to true)
                        }
                        mapper.valueToTree(mapOf("topics" to saved))
                    }
                    else -> error("Unexpected curriculum call: $name")
                }
                afterInvoke(name)
                result
            },
            focus = { _, id -> focused += id; VoiceTutorMcpToolResult("{}", false) })

        fun add(id: Long, parent: Long?, topic: String, level: Int, terminal: Boolean = false) {
            nodes[id] = Node(id, parent, topic, level, terminal)
        }
    }
}
