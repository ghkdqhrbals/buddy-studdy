package com.buddystudy.backend.voice.adapter.outbound.mcp

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpAdapter
import com.buddystudy.backend.mcp.adapter.inbound.BuddyStudyMcpPort
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.model.VoiceTutorDialogueBoundary
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.model.VoiceTutorRootStudyCreationAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorChildStudyCreationAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyUpdateAuthorization
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateDiscoveryScope
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorCandidateReadKind
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersistencePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayAuthorizationPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMutationConfirmationPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLearnerTurnAuthorization
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyChangeKind
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorFocusCommitAuthority
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class McpVoiceTutorToolAdapterTest {
    @Test
    fun `successful study read returns the immutable lesson level alongside current app metadata`(): Unit = runBlocking {
        val contextStore = ContextStore().apply {
            saved[102L] = VoiceTutorStudySnapshot(102, 101, "Cache", 3)
        }
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf("id" to 102L, "topic" to "Cache", "difficultyLevel" to 9)) }
        }
        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 102L))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("difficultyLevel").asInt()).isEqualTo(9)
        val lessonTopic = json(result).path("voiceLessonTopics")[0]
        assertThat(lessonTopic.path("studyId").asLong()).isEqualTo(102)
        assertThat(lessonTopic.path("difficulty").asInt()).isEqualTo(3)
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isTrue()
        val tree = json(result).path("voiceLessonTree")
        assertThat(tree.path("selectedStudyId").asLong()).isEqualTo(101)
        assertThat(tree.path("selectedRootStudyId").asLong()).isEqualTo(101)
        assertThat(tree.path("selectedPathComplete").asBoolean()).isTrue()
        assertThat(tree.path("nodes").size()).isEqualTo(1)
        assertThat(tree.path("nodes")[0].path("studyId").asLong()).isEqualTo(102)
        assertThat(tree.path("nodes")[0].path("relationToSelected").asText()).isEqualTo("DESCENDANT")
        assertThat(contextStore.remembered).isEmpty()
        assertThat(contextStore.listReads).isEqualTo(1)
        assertThat(result.studyTreeChanged).isFalse()
    }

    @Test
    fun `an explicitly read other root keeps its exact relation without silently changing the selected lesson tree`(): Unit = runBlocking {
        val contextStore = ContextStore().apply {
            saved[100L] = VoiceTutorStudySnapshot(100, null, "Systems", 9)
            saved[101L] = VoiceTutorStudySnapshot(101, 100, "Cache", 5)
            saved[200L] = VoiceTutorStudySnapshot(200, null, "Cache", 5)
            saved[201L] = VoiceTutorStudySnapshot(201, 200, "Cache", 2)
        }
        val fixture = Fixture(studyContexts = contextStore).apply {
            // Live names and levels cannot supply ancestry or replace the frozen lesson level.
            handler = { _, _ -> success(mapOf("id" to 201L, "topic" to "Cache", "parentStudyId" to 101L, "difficultyLevel" to 9)) }
        }
        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 201L))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("voiceLessonTopics").size()).isEqualTo(1)
        assertThat(json(result).path("voiceLessonTopics")[0].path("parentStudyId").asLong()).isEqualTo(200)
        assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(2)
        val tree = json(result).path("voiceLessonTree")
        assertThat(tree.path("selectedStudyId").asLong()).isEqualTo(101)
        assertThat(tree.path("selectedRootStudyId").asLong()).isEqualTo(100)
        assertThat(tree.path("selectedPathStudyIds").map { it.asLong() }).containsExactly(100, 101)
        assertThat(tree.path("nodes").size()).isEqualTo(1)
        assertThat(tree.path("nodes")[0].path("relationToSelected").asText()).isEqualTo("OTHER_TREE")
        assertThat(tree.path("nodes")[0].path("rootStudyId").asLong()).isEqualTo(200)
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        assertThat(result.studyTreeChanged).isFalse()
    }

    @Test
    fun `study pages enrich only already frozen validated IDs without capturing browsing candidates`(): Unit = runBlocking {
        val contextStore = ContextStore().apply {
            saved[102L] = VoiceTutorStudySnapshot(102, 101, "Cache", 3)
            saved[103L] = VoiceTutorStudySnapshot(103, 101, "Eviction", 4)
        }
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf("studies" to listOf(
                mapOf("id" to 102L), mapOf("id" to 103L), mapOf("id" to -1),
                mapOf("id" to "104"), mapOf("id" to 1.5), mapOf("id" to 102L),
            ), "totalCount" to 6, "offset" to 0)) }
        }
        val result = fixture.adapter.execute(context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 10))
        assertThat(result.isError).isFalse()
        assertThat(contextStore.remembered).isEmpty()
        assertThat(json(result).path("voiceLessonTopics").map { it.path("studyId").asLong() }).containsExactly(102, 103)
        assertThat(json(result).path("totalCount").asInt()).isEqualTo(6)
    }

    @Test
    fun `successful study reads attest exact IDs and validated query or parent pages`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { name, arguments ->
                when (name) {
                    "get_study" -> success(mapOf(
                        "id" to 202L,
                        "parentStudyId" to 201L,
                        "topic" to "Returned cache",
                    ))
                    "list_studies" -> if ("query" in arguments) {
                        success(mapOf(
                            "studies" to listOf(
                                mapOf("id" to 301L, "parentStudyId" to null, "topic" to "Databases"),
                                mapOf("id" to 302L, "parentStudyId" to 301L, "topic" to "Redis"),
                            ),
                            "totalCount" to 2,
                            "limit" to 10,
                            "offset" to 0,
                        ))
                    } else {
                        success(mapOf(
                            "studies" to listOf(
                                mapOf("id" to 302L, "parentStudyId" to 301L, "topic" to "Redis"),
                            ),
                            "totalCount" to 1,
                            "limit" to 10,
                            "offset" to 0,
                        ))
                    }
                    else -> failure("UNEXPECTED_TOOL")
                }
            }
        }

        val getResult = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 202L))
        val getDiscovery = requireNotNull(getResult.candidateDiscovery)
        assertThat(getDiscovery.source).isEqualTo(VoiceTutorCandidateReadKind.GET_STUDY)
        assertThat(getDiscovery.lessonRevision).isZero()
        assertThat(getDiscovery.currentFocusStudyId).isEqualTo(101L)
        assertThat(getDiscovery.scope).isEqualTo(VoiceTutorCandidateDiscoveryScope.ExactStudy(202L))
        assertThat(getDiscovery.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(202L, 201L, "Returned cache"),
        )

        val listResult = fixture.adapter.execute(
            context(), "list_studies", mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
        )
        val listDiscovery = requireNotNull(listResult.candidateDiscovery)
        assertThat(listDiscovery.source).isEqualTo(VoiceTutorCandidateReadKind.LIST_STUDIES)
        assertThat(listDiscovery.lessonRevision).isZero()
        assertThat(listDiscovery.currentFocusStudyId).isEqualTo(101L)
        assertThat(listDiscovery.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("Redis", 0, 10, 2),
        )
        assertThat(listDiscovery.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(301L, null, "Databases"),
            VoiceTutorStudyTargetCandidate(302L, 301L, "Redis"),
        )

        val childrenResult = fixture.adapter.execute(
            context(), "list_studies", mapOf("parent_study_id" to 301L, "limit" to 10, "offset" to 0),
        )
        val childrenDiscovery = requireNotNull(childrenResult.candidateDiscovery)
        assertThat(childrenDiscovery.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(301, 0, 10, 1),
        )
        assertThat(childrenDiscovery.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(302L, 301L, "Redis"),
        )
    }

    @Test
    fun `validated paginated slices retain exact query and direct child scope`(): Unit = runBlocking {
        val queryCandidates = (201L..211L).map { candidate(it, null) }
        val childCandidates = (301L..311L).map { candidate(it, 101L) }
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { _, arguments ->
                val offset = (arguments.getValue("offset") as Number).toInt()
                val candidates = if ("query" in arguments) queryCandidates else childCandidates
                success(completePage(
                    studies = candidates.drop(offset).take(10),
                    totalCount = candidates.size,
                    limit = 10,
                    offset = offset,
                ))
            }
        }

        val queryFirst = fixture.adapter.execute(
            context(), "list_studies", mapOf("query" to "cache", "limit" to 10, "offset" to 0),
        ).candidateDiscovery
        val queryLast = fixture.adapter.execute(
            context(), "list_studies", mapOf("query" to "cache", "limit" to 10, "offset" to 10),
        ).candidateDiscovery
        val childFirst = fixture.adapter.execute(
            context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 0),
        ).candidateDiscovery
        val childLast = fixture.adapter.execute(
            context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 10),
        ).candidateDiscovery

        assertThat(queryFirst?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("cache", 0, 10, 11),
        )
        assertThat(queryFirst?.candidates).hasSize(10)
        assertThat(queryLast?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteQueryPage("cache", 10, 10, 11),
        )
        assertThat(queryLast?.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(211L, null, "Topic 211"),
        )
        assertThat(childFirst?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 10, 11),
        )
        assertThat(childFirst?.candidates).hasSize(10)
        assertThat(childLast?.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 10, 10, 11),
        )
        assertThat(childLast?.candidates).containsExactly(
            VoiceTutorStudyTargetCandidate(311L, 101L, "Topic 311"),
        )
    }

    @Test
    fun `only exact validated list slices attest candidates while provider output remains available`(): Unit = runBlocking {
        suspend fun assertNoDiscovery(
            description: String,
            toolName: String,
            arguments: Map<String, Any>,
            payload: Map<String, Any?>,
        ) {
            val fixture = Fixture(studyContexts = ContextStore()).apply {
                handler = { _, _ -> success(payload) }
            }
            val result = fixture.adapter.execute(context(), toolName, arguments)
            assertThat(result.isError).describedAs(description).isFalse()
            assertThat(result.candidateDiscovery).describedAs(description).isNull()
            assertThat(json(result).isObject).describedAs("$description provider output").isTrue()
        }

        assertNoDiscovery(
            "GET response ID differs from the requested ID",
            "get_study",
            mapOf("study_id" to 201L),
            mapOf("id" to 202L, "parentStudyId" to null, "topic" to "Redis"),
        )
        assertNoDiscovery(
            "LIST has neither query nor parent scope",
            "list_studies",
            mapOf("limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, null)), totalCount = 1, limit = 10),
        )
        assertNoDiscovery(
            "LIST mixes query and parent scope",
            "list_studies",
            mapOf("query" to "Redis", "parent_study_id" to 101L, "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, 101L)), totalCount = 1, limit = 10),
        )
        assertNoDiscovery(
            "truncated first page",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, null)), totalCount = 2, limit = 10),
        )
        assertNoDiscovery(
            "nonzero page",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 1),
            completePage(listOf(candidate(201L, null)), totalCount = 1, limit = 10, offset = 1),
        )
        assertNoDiscovery(
            "response offset differs from request",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, null)), totalCount = 1, limit = 10, offset = 1),
        )
        assertNoDiscovery(
            "response limit differs from request",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, null)), totalCount = 1, limit = 11),
        )
        assertNoDiscovery(
            "complete page exceeds discovery bound",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 17, "offset" to 0),
            completePage((201L..217L).map { candidate(it, null) }, totalCount = 17, limit = 17),
        )
        assertNoDiscovery(
            "result set exceeds bounded discovery authority",
            "list_studies",
            mapOf("query" to "Redis", "limit" to 10, "offset" to 0),
            completePage((201L..210L).map { candidate(it, null) }, totalCount = 61, limit = 10),
        )
        assertNoDiscovery(
            "direct child belongs to another parent",
            "list_studies",
            mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 0),
            completePage(listOf(candidate(201L, 999L)), totalCount = 1, limit = 10),
        )
    }

    @Test
    fun `an empty complete direct child page remains typed leaf evidence`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { _, _ -> success(completePage(emptyList(), totalCount = 0, limit = 10)) }
        }

        val result = fixture.adapter.execute(
            context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 10, "offset" to 0),
        )

        val discovery = requireNotNull(result.candidateDiscovery)
        assertThat(discovery.source).isEqualTo(VoiceTutorCandidateReadKind.LIST_STUDIES)
        assertThat(discovery.scope).isEqualTo(
            VoiceTutorCandidateDiscoveryScope.CompleteDirectChildrenPage(101, 0, 10, 0),
        )
        assertThat(discovery.candidates).isEmpty()
        assertThat(json(result).path("studies")).isEmpty()
    }

    @Test
    fun `failed malformed ambiguous oversized and revision raced reads cannot attest candidates`(): Unit = runBlocking {
        suspend fun assertNoDiscovery(
            description: String,
            toolName: String,
            arguments: Map<String, Any>,
            rawResult: McpSchema.CallToolResult,
        ) {
            val fixture = Fixture(studyContexts = ContextStore()).apply { handler = { _, _ -> rawResult } }
            val result = fixture.adapter.execute(context(), toolName, arguments)
            assertThat(result.candidateDiscovery).describedAs(description).isNull()
        }

        assertNoDiscovery(
            "error result",
            "get_study",
            mapOf("study_id" to 201L),
            failure("READ_FAILED"),
        )
        assertNoDiscovery(
            "invalid candidate identity",
            "get_study",
            mapOf("study_id" to 201L),
            success(mapOf("id" to 0L, "parentStudyId" to null, "topic" to "Invalid")),
        )
        assertNoDiscovery(
            "invalid blank topic",
            "get_study",
            mapOf("study_id" to 201L),
            success(mapOf("id" to 201L, "parentStudyId" to null, "topic" to " ")),
        )
        assertNoDiscovery(
            "missing parent field",
            "get_study",
            mapOf("study_id" to 201L),
            success(mapOf("id" to 201L, "topic" to "Missing parent")),
        )
        assertNoDiscovery(
            "duplicate list identity",
            "list_studies",
            emptyMap(),
            success(mapOf("studies" to listOf(
                mapOf("id" to 201L, "parentStudyId" to null, "topic" to "First"),
                mapOf("id" to 201L, "parentStudyId" to null, "topic" to "Duplicate"),
            ))),
        )
        assertNoDiscovery(
            "inconsistent list page bounds",
            "list_studies",
            emptyMap(),
            success(mapOf(
                "studies" to listOf(
                    mapOf("id" to 201L, "parentStudyId" to null, "topic" to "Redis"),
                ),
                "totalCount" to 1,
                "limit" to 1,
                "offset" to 1,
            )),
        )
        assertNoDiscovery(
            "seventeen candidates",
            "list_studies",
            emptyMap(),
            success(mapOf("studies" to (201L..217L).map { id ->
                mapOf("id" to id, "parentStudyId" to null, "topic" to "Topic $id")
            })),
        )

        val racingStore = ContextStore()
        val racingFixture = Fixture(studyContexts = racingStore).apply {
            handler = { _, _ -> success(mapOf(
                "id" to 202L,
                "parentStudyId" to 101L,
                "topic" to "Raced candidate",
            )) }
        }
        racingStore.afterList = { racingStore.revision = 1L }
        val raced = racingFixture.adapter.execute(context(), "get_study", mapOf("study_id" to 202L))
        assertThat(racingStore.listReads).isEqualTo(1)
        assertThat(racingStore.revision).isEqualTo(1L)
        assertThat(raced.candidateDiscovery).describedAs("lesson revision changed during read").isNull()
    }

    @Test
    fun `browsing many children and individual candidates after selection cannot exhaust the lesson cache`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            handler = { name, args ->
                if (name == "get_study") success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to 101L))
                else success(mapOf("studies" to (200L..229L).map { mapOf("id" to it, "parentStudyId" to 101L) }))
            }
        }
        for (id in 200L..279L) {
            val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to id))
            assertThat(result.isError).isFalse()
            assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        }
        val page = fixture.adapter.execute(context(), "list_studies", mapOf("parent_study_id" to 101L, "limit" to 30))
        assertThat(page.isError).isFalse()
        assertThat(json(page).path("studies").size()).isEqualTo(30)
        assertThat(store.saved.keys).containsExactly(101L)
        assertThat(store.remembered).isEmpty()
        assertThat(store.revised).isEmpty()
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `late study reads are suppressed when authorization expires during their handler`(): Unit = runBlocking {
        for (name in listOf("get_study", "list_studies")) {
            val store = ContextStore()
            val fixture = Fixture(studyContexts = store).apply {
                handler = { _, _ ->
                    authorized = false
                    success(mapOf("id" to 102L, "topic" to "private-late-topic",
                        "studies" to listOf(mapOf("id" to 102L, "topic" to "private-late-topic"))))
                }
            }
            val args = if (name == "get_study") mapOf("study_id" to 102L) else mapOf("limit" to 10)
            val result = fixture.adapter.execute(context(), name, args)
            assertCode(result, "CALL_NOT_AUTHORIZED")
            assertThat(result.output).doesNotContain("private-late-topic")
            assertThat(store.listReads).isZero()
            assertThat(store.remembered).isEmpty()
        }
    }

    @Test
    fun `ending a call during read-only metadata enrichment suppresses the late response`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            handler = { _, _ -> success(mapOf("id" to 101L, "topic" to "private-late-topic")) }
        }
        store.afterList = { fixture.persistedSession = session().copy(status = VoiceTutorSessionStatus.COMPLETED) }
        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 101L))
        assertCode(result, "CALL_NOT_AUTHORIZED")
        assertThat(result.output).doesNotContain("private-late-topic")
        assertThat(store.listReads).isEqualTo(1)
        assertThat(store.remembered).isEmpty()
    }

    @Test
    fun `failed tools and failed authorization do not capture topic metadata`(): Unit = runBlocking {
        val contextStore = ContextStore()
        val fixture = Fixture(studyContexts = contextStore).apply { handler = { _, _ -> failure("NOT_FOUND") } }
        assertCode(fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 102L)), "NOT_FOUND")
        fixture.authorized = false
        assertCode(fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 102L)), "CALL_NOT_AUTHORIZED")
        assertThat(contextStore.remembered).isEmpty()
        assertThat(contextStore.listReads).isZero()
    }

    @Test
    fun `metadata persistence failure cannot misreport an already committed child creation`(): Unit = runBlocking {
        val contextStore = ContextStore().apply { fail = true }
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf(
                "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Cache", "difficultyLevel" to 5,
            )) }
        }
        val result = fixture.adapter.execute(
            childContext(101L, "Cache"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Cache"),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.createdStudyId).isEqualTo(102)
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
        assertThat(result.output).doesNotContain("private-database-detail")
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonTopics")).isEmpty()
        assertThat(json(result).path("voiceLessonTree").path("selectedPathComplete").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonTree").path("nodes")[0].path("relationToSelected").asText()).isEqualTo("UNRESOLVED")
    }

    @Test
    fun `ancestry read failure preserves a completed creation and its captured level without inventing a root`(): Unit = runBlocking {
        val contextStore = ContextStore().apply { failList = true }
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf(
                "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Cache", "difficultyLevel" to 5,
            )) }
        }
        val result = fixture.adapter.execute(
            childContext(101L, "Cache"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Cache"),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.createdStudyId).isEqualTo(102)
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(3)
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isTrue()
        val tree = json(result).path("voiceLessonTree")
        assertThat(tree.path("selectedRootStudyId").isNull).isTrue()
        assertThat(tree.path("selectedPathComplete").asBoolean()).isFalse()
        assertThat(tree.path("nodes")[0].path("relationToSelected").asText()).isEqualTo("UNRESOLVED")
        assertThat(result.output).doesNotContain("private-database-detail")
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
    }

    @Test
    fun `a full snapshot cache preserves app metadata but explicitly withholds an unfrozen lesson level`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = UnavailableVoiceTutorStudyContextPort).apply {
            handler = { _, _ -> success(mapOf("id" to 102L, "topic" to "Cache", "difficultyLevel" to 9)) }
        }
        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 102L))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("difficultyLevel").asInt()).isEqualTo(9)
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonTopics")).isEmpty()
        assertThat(json(result).path("voiceLessonTree").path("nodes")[0].path("relationToSelected").asText()).isEqualTo("UNRESOLVED")
        assertThat(fixture.calls).hasSize(1)
    }

    @Test
    fun `unscoped discovery pages stay readable without consuming the bounded lesson context cache`(): Unit = runBlocking {
        val contextStore = ContextStore()
        val fixture = Fixture(studyContexts = contextStore).apply {
            handler = { _, _ -> success(mapOf("studies" to (102L..141L).map { mapOf("id" to it) })) }
        }
        val result = fixture.adapter.execute(context(), "list_studies", mapOf("limit" to 50))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("studies").size()).isEqualTo(40)
        assertThat(json(result).path("voiceLessonTopics").size()).isZero()
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(contextStore.remembered).isEmpty()
    }

    @Test
    fun `lesson metadata obeys the output bound without losing a committed creation`(): Unit = runBlocking {
        for (name in listOf("get_study", "create_study_topic")) {
            val fixture = Fixture(studyContexts = ContextStore()).apply {
                handler = { _, _ -> success(mapOf(
                    "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Cache",
                    "difficultyLevel" to 5, "customPrompt" to "x".repeat(16_300),
                )) }
            }
            val args = if (name == "get_study") mapOf("study_id" to 102L)
                else mapOf("parent_study_id" to 101L, "topic" to "Cache")
            val callContext = if (name == "create_study_topic") childContext(101L, "Cache") else context()
            val result = fixture.adapter.execute(callContext, name, args)
            assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16 * 1_024)
            if (name == "get_study") assertCode(result, "RESULT_TOO_LARGE") else {
                assertThat(result.isError).isFalse()
                assertThat(result.createdStudyId).isEqualTo(102)
                assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(3)
                assertThat(json(result).path("voiceLessonTree").path("nodes")[0].path("relationToSelected").asText()).isEqualTo("DESCENDANT")
            }
        }
    }

    @Test
    fun `advertises existing allowed schemas and the scoped voice focus tool while resolving MCP lazily`() {
        val fixture = Fixture()
        assertThat(fixture.catalogReads).isZero()

        val definitions = fixture.adapter.definitions()

        assertThat(definitions.map { it.name }).containsExactly(
            "list_studies", "get_study", "update_study", "create_root_study", "create_study_topic", "delete_study",
            "list_records", "get_record", "list_study_learning_records", "get_voice_learning_record",
            "get_topic_stats", "get_study_growth",
            "select_voice_study", "advance_voice_study",
        )
        for (definition in definitions.filterNot { it.name in setOf("select_voice_study", "advance_voice_study") }) {
            val original = fixture.catalog.single { it.tool().name() == definition.name }.tool()
            if (definition.name != "delete_study") {
                assertThat(definition.parameters).isEqualTo(original.inputSchema())
            }
            assertThat(definition.description).startsWith(original.description())
        }
        val rootCreation = mapper.valueToTree<JsonNode>(
            definitions.single { it.name == "create_root_study" }.parameters,
        )
        assertThat(rootCreation.path("required").map { it.asText() }).containsExactly("topic")
        assertThat(rootCreation.path("additionalProperties").asBoolean()).isFalse()
        assertThat(rootCreation.path("properties").fieldNames().asSequence().toList()).containsExactly(
            "topic", "difficulty_level",
        )
        assertThat(rootCreation.path("properties").has("interval_minutes")).isFalse()
        assertThat(definitions.single { it.name == "create_root_study" }.description)
            .contains(
                "server-owned", "never originate", "natural first-person new-study intent",
                "bounded earlier persisted learner speech", "never selects a lesson or creates a question",
            )
        assertThat(definitions.single { it.name == "create_study_topic" }.description).contains(
            "server-owned", "never originate", "current first-person choice", "verified descendant",
            "Mere mentions", "ambiguous targets",
        )
        assertThat(definitions.single { it.name == "update_study" }.description).contains(
            "server-owned", "never originate", "current first-person choice",
            "Unspecified fields", "ambiguous targets or outcomes",
        )
        val deletion = mapper.valueToTree<JsonNode>(definitions.single { it.name == "delete_study" }.parameters)
        assertThat(deletion.path("properties").has("confirmation_token")).isTrue()
        assertThat(deletion.path("properties").has("expected_study_ids")).isFalse()
        assertThat(definitions.filter { it.name in setOf("list_study_learning_records", "get_voice_learning_record") })
            .allSatisfy { assertThat(it.description).contains("verified study tree") }
        fixture.adapter.definitions()
        assertThat(fixture.catalogReads).isEqualTo(1)
        assertThat(fixture.calls).isEmpty()
        for (name in listOf("select_voice_study", "advance_voice_study")) {
            val focus = mapper.valueToTree<JsonNode>(definitions.single { it.name == name }.parameters)
            assertThat(focus.path("required").map { it.asText() }).containsExactly("study_id")
            assertThat(focus.path("additionalProperties").asBoolean()).isFalse()
            assertThat(focus.path("properties").fieldNames().asSequence().toList()).containsExactly("study_id")
        }
        assertThat(definitions.single { it.name == "select_voice_study" }.description)
            .contains("initial or explicitly changed")
        assertThat(definitions.single { it.name == "advance_voice_study" }.description)
            .contains("exactly one verified parent-child edge")
    }

    @Test
    fun `persisted direct root command writes immediately exactly once without preview or lesson focus`(): Unit =
        runBlocking {
            val store = ContextStore().apply {
                saved[501L] = VoiceTutorStudySnapshot(501, null, "운영체제", 6)
            }
            val fixture = Fixture(studyContexts = store).apply {
                handler = { name, _ ->
                    if (name == "create_root_study") success(linkedMapOf(
                        "created" to true,
                        "id" to 501L,
                        "parentStudyId" to null,
                        "topic" to "운영체제",
                        "difficultyLevel" to 6,
                        "enabled" to true,
                        "activeForQuestions" to true,
                    )) else failure("UNEXPECTED_TOOL")
                }
            }
            val command = context(
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootTopic = "운영체제",
                rootDifficulty = 6,
            )
            val created = fixture.adapter.execute(
                command,
                "create_root_study",
                mapOf("topic" to "운영체제", "difficulty_level" to 6),
            )

            assertThat(created.isError).isFalse()
            assertThat(created.studyTreeChanged).isTrue()
            assertThat(created.createdStudyId).isEqualTo(501L)
            assertThat(created.changedStudyId).isEqualTo(501L)
            assertThat(created.changeKind).isEqualTo(VoiceTutorStudyChangeKind.CREATED)
            assertThat(created.rootStudyReadbackId).isEqualTo(501L)
            assertThat(created.lessonFocus).isNull()
            assertThat(json(created).path("parentStudyId").isNull).isTrue()
            assertThat(json(created).path("voiceLessonContextReady").asBoolean()).isFalse()
            assertThat(json(created).path("voiceLessonChangeApplies").asText()).isEqualTo("REQUIRES_SELECTION")
            assertThat(json(created).path("notice").asText()).contains(
                "not a lesson focus", "wait for a new learner agreement", "creation itself never starts teaching",
            )
            assertThat(fixture.calls.map { it.name }).containsExactly("create_root_study")
            assertThat(fixture.calls.single().arguments).containsExactlyInAnyOrderEntriesOf(
                mapOf("topic" to "운영체제", "difficulty_level" to 6),
            )
            assertThat(fixture.calls.single().principal).isEqualTo(principal)
            assertThat(fixture.focusSelections).isEmpty()
            assertThat(store.remembered).containsExactly(listOf(501L))

            assertCode(
                fixture.adapter.execute(
                    command,
                    "create_root_study",
                    mapOf("topic" to "운영체제", "difficulty_level" to 6),
                ),
                "ROOT_CREATION_REQUEST_REQUIRED",
            )
            assertThat(fixture.calls).hasSize(1)
        }

    @Test
    fun `existing exact root is returned unchanged without a tree event or lesson focus`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            handler = { name, _ ->
                if (name == "create_root_study") success(linkedMapOf(
                    "created" to false,
                    "id" to 701L,
                    "parentStudyId" to null,
                    "topic" to "Redis Streams",
                    "difficultyLevel" to 3,
                    "enabled" to false,
                    "activeForQuestions" to false,
                )) else failure("UNEXPECTED_TOOL")
            }
        }
        val result = fixture.adapter.execute(
            context(
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootTopic = "redis streams",
                rootDifficulty = 5,
            ),
            "create_root_study",
            mapOf("topic" to " redis streams "),
        )

        assertThat(result.isError).isFalse()
        assertThat(json(result).path("created").asBoolean()).isFalse()
        assertThat(json(result).path("topic").asText()).isEqualTo("Redis Streams")
        assertThat(json(result).path("difficultyLevel").asInt()).isEqualTo(3)
        assertThat(json(result).path("enabled").asBoolean()).isFalse()
        assertThat(json(result).path("notice").asText())
            .contains("already existed", "returned unchanged")
            .doesNotContain("updated", "level 9")
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.createdStudyId).isNull()
        assertThat(result.changedStudyId).isNull()
        assertThat(result.rootStudyReadbackId).isEqualTo(701L)
        assertThat(result.lessonFocus).isNull()
        assertThat(store.remembered).isEmpty()
        assertThat(fixture.focusSelections).isEmpty()
        assertThat(fixture.calls).hasSize(1)
        assertThat(fixture.calls.single().arguments).containsExactlyInAnyOrderEntriesOf(
            mapOf("topic" to "redis streams", "difficulty_level" to 5),
        )
    }

    @Test
    fun `a child name conflict is preserved without a root tree event or lesson focus`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            handler = { name, _ ->
                if (name == "create_root_study") failure("STUDY_TOPIC_CONFLICT")
                else failure("UNEXPECTED_TOOL")
            }
        }

        val result = fixture.adapter.execute(
            context(
                VoiceTutorInputIntent.CREATE_ROOT_STUDY,
                rootTopic = "Cache",
                rootDifficulty = 5,
            ),
            "create_root_study",
            mapOf("topic" to "Cache"),
        )

        assertCode(result, "STUDY_TOPIC_CONFLICT")
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.createdStudyId).isNull()
        assertThat(result.changedStudyId).isNull()
        assertThat(result.rootStudyReadbackId).isNull()
        assertThat(result.lessonFocus).isNull()
        assertThat(fixture.calls).hasSize(1)
        assertThat(fixture.calls.single().principal).isEqualTo(principal)
        assertThat(fixture.calls.single().arguments).containsExactlyInAnyOrderEntriesOf(
            mapOf("topic" to "Cache", "difficulty_level" to 5),
        )
        assertThat(store.remembered).isEmpty()
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `direct root authorization is exact tuple bound and a mismatched call cannot consume it`() = runBlocking {
        val store = ContextStore().apply {
            saved[801L] = VoiceTutorStudySnapshot(801, null, "Spring", 7)
        }
        val fixture = Fixture(studyContexts = store).apply {
            handler = { name, _ ->
                if (name == "create_root_study") success(linkedMapOf(
                    "created" to true,
                    "id" to 801L,
                    "parentStudyId" to null,
                    "topic" to "Spring",
                    "difficultyLevel" to 7,
                    "enabled" to true,
                    "activeForQuestions" to true,
                )) else failure("UNEXPECTED_TOOL")
            }
        }
        val command = context(
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            rootTopic = "Spring",
            rootDifficulty = 7,
        )

        assertCode(
            fixture.adapter.execute(
                command,
                "create_root_study",
                mapOf("topic" to "Redis", "difficulty_level" to 9),
            ),
            "ROOT_CREATION_REQUEST_MISMATCH",
        )
        assertThat(fixture.calls).isEmpty()
        assertThat(command.dialogueBoundary!!.rootStudyCreationAuthorization!!.isActive()).isTrue()

        val created = fixture.adapter.execute(
            command,
            "create_root_study",
            mapOf("topic" to "Spring", "difficulty_level" to 7),
        )
        assertThat(created.isError).isFalse()
        assertThat(fixture.calls.map { it.name }).containsExactly("create_root_study")
        assertThat(command.dialogueBoundary!!.rootStudyCreationAuthorization!!.isActive()).isFalse()
    }

    @Test
    fun `newer speech during persisted direct command lookup revokes root write before invocation`() = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore())
        val arguments = mapOf(
            "topic" to "운영체제",
            "difficulty_level" to 6,
        )
        val lease = VoiceTutorRootStudyCreationAuthorization("운영체제", 6)
        fixture.duringLearnerAuthorization = { lease.invalidate() }

        assertCode(
            fixture.adapter.execute(
                context(VoiceTutorInputIntent.CREATE_ROOT_STUDY).copy(
                    dialogueBoundary = context(VoiceTutorInputIntent.CREATE_ROOT_STUDY).dialogueBoundary!!.copy(
                        rootStudyCreationAuthorization = lease,
                    ),
                ),
                "create_root_study",
                arguments,
            ),
            "ROOT_CREATION_REQUEST_REQUIRED",
        )
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `unverified root result fails closed and consumed direct command cannot replay the write`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore()).apply {
            handler = { name, _ ->
                if (name == "create_root_study") success(linkedMapOf(
                    "created" to true,
                    "id" to 801L,
                    "parentStudyId" to null,
                    "topic" to "Different root",
                    "difficultyLevel" to 9,
                    "enabled" to true,
                    "activeForQuestions" to true,
                )) else failure("UNEXPECTED_TOOL")
            }
        }
        val arguments = mapOf(
            "topic" to "운영체제",
            "difficulty_level" to 6,
        )
        val command = context(
            VoiceTutorInputIntent.CREATE_ROOT_STUDY,
            rootTopic = "운영체제",
            rootDifficulty = 6,
        )

        val uncertain = fixture.adapter.execute(command, "create_root_study", arguments)

        assertCode(uncertain, "ROOT_CREATION_RESULT_UNCONFIRMED")
        assertThat(uncertain.studyTreeChanged).isFalse()
        assertThat(uncertain.createdStudyId).isNull()
        assertThat(uncertain.rootStudyReadbackId).isNull()
        assertThat(uncertain.output).doesNotContain("Different root")
        assertThat(fixture.calls).hasSize(1)
        assertCode(
            fixture.adapter.execute(command, "create_root_study", arguments),
            "ROOT_CREATION_REQUEST_REQUIRED",
        )
        assertThat(fixture.calls).hasSize(1)
    }

    @Test
    fun `root creation rejects topic discovery and caller supplied settings before any write`(): Unit = runBlocking {
        for ((context, arguments, code) in listOf(
            Triple(
                context(VoiceTutorInputIntent.DISCOVER_SAVED_TOPIC),
                mapOf<String, Any>("topic" to "운영체제"),
                "ROOT_CREATION_REQUEST_REQUIRED",
            ),
            Triple(
                context(VoiceTutorInputIntent.NONE),
                mapOf<String, Any>("topic" to "운영체제"),
                "ROOT_CREATION_REQUEST_REQUIRED",
            ),
            Triple(
                context(VoiceTutorInputIntent.CREATE_ROOT_STUDY),
                mapOf<String, Any>("topic" to "운영체제", "enabled" to false),
                "INVALID_ARGUMENTS",
            ),
            Triple(
                context(VoiceTutorInputIntent.CREATE_ROOT_STUDY),
                mapOf<String, Any>("topic" to "운영체제", "confirm" to true, "confirmation_token" to "forged"),
                "INVALID_ARGUMENTS",
            ),
        )) {
            val fixture = Fixture()
            assertCode(fixture.adapter.execute(context, "create_root_study", arguments), code)
            assertThat(fixture.calls).isEmpty()
        }
    }

    @Test
    fun `discovery can browse owned studies but cannot mutate or read lesson history before a chosen focus`(): Unit = runBlocking {
        val store = ContextStore()
        val fixture = Fixture(studyContexts = store).apply {
            persistedSession = discoverySession()
            handler = { name, _ -> if (name == "list_studies") success(mapOf("studies" to listOf(mapOf("id" to 101L))))
                else success(mapOf("id" to 101L, "topic" to "Redis", "parentStudyId" to null)) }
        }
        val context = context().copy(session = discoverySession())
        assertThat(fixture.adapter.execute(context, "list_studies", emptyMap()).isError).isFalse()
        val read = fixture.adapter.execute(context, "get_study", mapOf("study_id" to 101L))
        assertThat(read.isError).isFalse()
        assertThat(json(read).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(store.remembered).isEmpty()
        for ((name, args, code) in listOf(
            Triple(
                "create_study_topic",
                mapOf("parent_study_id" to 101L, "topic" to "Cache"),
                "CHILD_CREATION_REQUEST_REQUIRED",
            ),
            Triple("update_study", mapOf("study_id" to 101L, "difficulty_level" to 2), "STUDY_UPDATE_REQUEST_REQUIRED"),
            Triple("delete_study", mapOf("study_id" to 101L, "confirm" to false), "STUDY_SCOPE_DENIED"),
            Triple("list_study_learning_records", mapOf("study_id" to 101L), "STUDY_SCOPE_DENIED"),
        )) assertCode(fixture.adapter.execute(context, name, args), code)
        assertThat(fixture.calls.map { it.name }).containsExactly("list_studies", "get_study")
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `spoken selection returns only persisted focus metadata and preserves the original nullable request`(): Unit = runBlocking {
        val store = ContextStore().apply {
            saved[101L] = VoiceTutorStudySnapshot(101, null, "Redis", 3)
        }
        val fixture = Fixture(studyContexts = store).apply {
            persistedSession = discoverySession()
            focusResult = focusSelection(101, 1)
        }
        val traversal = VoiceTutorStudyTargetTraversal(terminalLeafStudyId = 101)
        val context = context(targetTraversal = traversal).copy(session = discoverySession())
        val result = fixture.adapter.execute(context, "select_voice_study", mapOf("study_id" to 101L))
        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.lessonRevision).isEqualTo(1)
        assertThat(result.lessonFocus).isEqualTo(fixture.focusResult)
        assertThat(json(result).path("voiceLessonFocus").path("topic").asText()).isEqualTo("Redis")
        assertThat(json(result).path("voiceLessonFocus").path("difficulty").asInt()).isEqualTo(3)
        assertThat(json(result).path("voiceLessonTree").path("selectedStudyId").asLong()).isEqualTo(101)
        assertThat(fixture.persistedSession?.acceptedStudyId).isNull()
        assertThat(fixture.persistedSession?.studyId).isEqualTo(101)
        assertThat(fixture.focusSelections).containsExactly(101L)
        assertThat(fixture.lastExpectedTraversal).isEqualTo(traversal)
        assertThat(fixture.calls).isEmpty() // no common study/question mutation
        assertThat(fixture.adapter.execute(context, "list_studies", emptyMap()).isError).isFalse()
    }

    @Test
    fun `focus selection rejects invalid identities and any caller supplied metadata`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = discoverySession(); focusResult = focusSelection(101, 1) }
        val context = context().copy(session = discoverySession())
        for (args in listOf(emptyMap(), mapOf("study_id" to 0), mapOf("study_id" to -1),
            mapOf("study_id" to "101"), mapOf("study_id" to 101.5), mapOf("study_id" to true),
            mapOf("study_id" to 101L, "topic" to "injected-title"), mapOf("study_id" to 101L, "revision" to 99))) {
            assertCode(fixture.adapter.execute(context, "select_voice_study", args), "INVALID_ARGUMENTS")
        }
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `focus tools reject a tool target different from the learner attested target without writing focus`(): Unit = runBlocking {
        val selection = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = focusSelection(101L, 1L)
        }
        val selectResult = selection.adapter.execute(
            context(targetStudyId = 202L).copy(session = discoverySession()),
            "select_voice_study",
            mapOf("study_id" to 101L),
        )
        assertCode(selectResult, "LEARNER_TARGET_MISMATCH")
        assertThat(selection.focusSelections).isEmpty()

        val missingTraversal = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = focusSelection(101L, 1L)
        }
        val missingTraversalResult = missingTraversal.adapter.execute(
            context(targetTraversal = null).copy(session = discoverySession()),
            "select_voice_study",
            mapOf("study_id" to 101L),
        )
        assertCode(missingTraversalResult, "LEARNER_TARGET_MISMATCH")
        assertThat(missingTraversal.focusSelections).isEmpty()

        val advance = Fixture().apply {
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102L, 1L),
                VoiceTutorStudySnapshot(102L, 101L, "Child", 4, revision = 1L),
            )
            handler = { name, _ -> if (name == "get_study") {
                success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Child"))
            } else failure("UNEXPECTED_TOOL") }
        }
        val advanceResult = advance.adapter.execute(
            context(VoiceTutorInputIntent.CONTINUE_TREE, targetStudyId = 103L),
            "advance_voice_study",
            mapOf("study_id" to 102L),
        )
        assertCode(advanceResult, "LEARNER_TARGET_MISMATCH")
        // Reject the model-supplied mismatch before it can trigger an arbitrary
        // child lookup; only the learner-attested target may be re-read.
        assertThat(advance.calls).isEmpty()
        assertThat(advance.focusSelections).isEmpty()
    }

    @Test
    fun `guided progression advances exactly one verified direct child with frozen metadata`(): Unit = runBlocking {
        val store = ContextStore().apply {
            saved[102L] = VoiceTutorStudySnapshot(102, 101, "Eviction", 6, revision = 2)
        }
        val child = VoiceTutorLessonFocusSelection(
            VoiceTutorLessonFocus(102, 2),
            VoiceTutorStudySnapshot(102, 101, "Eviction", 6, revision = 2),
        )
        val fixture = Fixture(studyContexts = store).apply {
            focusResult = child
            handler = { name, args ->
                if (name == "get_study" && args["study_id"] == 102L) {
                    success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Eviction"))
                } else {
                    failure("UNEXPECTED_TOOL")
                }
            }
        }

        val result = fixture.adapter.execute(
            context(VoiceTutorInputIntent.CONTINUE_TREE, targetTopic = "Eviction"),
            "advance_voice_study",
            mapOf("study_id" to 102L),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.lessonFocus).isEqualTo(child)
        assertThat(result.lessonRevision).isEqualTo(2)
        assertThat(json(result).path("voiceLessonFocusChange").asText()).isEqualTo("GUIDED_DIRECT_CHILD")
        assertThat(json(result).path("voiceLessonFocus").path("parentStudyId").asLong()).isEqualTo(101)
        assertThat(json(result).path("voiceLessonFocus").path("difficulty").asInt()).isEqualTo(6)
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        assertThat(fixture.focusSelections).containsExactly(102L)
        assertThat(fixture.lastExpectedTraversal).isEqualTo(VoiceTutorStudyTargetTraversal())
        assertThat(fixture.persistedSession?.studyId).isEqualTo(102)
    }

    @Test
    fun `guided progression consumes one accepted learner turn before another tree edge`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102, 2),
                VoiceTutorStudySnapshot(102, 101, "Cache", 4, revision = 2),
            )
            handler = { name, args -> if (name == "get_study") {
                val id = args["study_id"] as Long
                success(mapOf(
                    "id" to id,
                    "parentStudyId" to if (id == 102L) 101L else 102L,
                    "topic" to if (id == 102L) "Cache" else "Eviction",
                ))
            } else failure("UNEXPECTED_TOOL") }
        }
        assertThat(fixture.adapter.execute(
            context(VoiceTutorInputIntent.CONTINUE_TREE), "advance_voice_study", mapOf("study_id" to 102L),
        ).isError)
            .isFalse()
        fixture.focusResult = VoiceTutorLessonFocusSelection(
            VoiceTutorLessonFocus(103, 3),
            VoiceTutorStudySnapshot(103, 102, "Eviction", 5, revision = 3),
        )

        assertCode(
            fixture.adapter.execute(
                context(
                    VoiceTutorInputIntent.CONTINUE_TREE,
                    targetStudyId = 103L,
                    targetParentStudyId = 102L,
                    targetTopic = "Eviction",
                ),
                "advance_voice_study", mapOf("study_id" to 103L),
            ),
            "LESSON_FOCUS_UNAVAILABLE",
        )
        fixture.learnerTurnId = 12
        assertThat(fixture.adapter.execute(
            context(
                VoiceTutorInputIntent.CONTINUE_TREE,
                targetStudyId = 103L,
                targetParentStudyId = 102L,
                targetTopic = "Eviction",
            ),
            "advance_voice_study", mapOf("study_id" to 103L),
        ).isError)
            .isFalse()
        assertThat(fixture.focusSelections).containsExactly(102L, 103L)
    }

    @Test
    fun `guided progression rejects siblings deeper jumps other roots and missing continuation`(): Unit = runBlocking {
        for (parent in listOf<Long?>(null, 999L, 102L)) {
            val fixture = Fixture().apply {
                focusResult = VoiceTutorLessonFocusSelection(
                    VoiceTutorLessonFocus(103, 2),
                    VoiceTutorStudySnapshot(103, parent, "Wrong branch", 5, revision = 2),
                )
                handler = { name, _ -> if (name == "get_study") {
                    success(mapOf("id" to 103L, "parentStudyId" to parent, "topic" to "Wrong branch"))
                } else failure("UNEXPECTED_TOOL") }
            }
            assertCode(
                fixture.adapter.execute(
                    context(
                        VoiceTutorInputIntent.CONTINUE_TREE,
                        targetStudyId = 103L,
                        targetParentStudyId = parent,
                        targetTopic = "Wrong branch",
                    ),
                    "advance_voice_study", mapOf("study_id" to 103L),
                ),
                "GUIDED_DESCENT_OUT_OF_SCOPE",
            )
            assertThat(fixture.focusSelections).isEmpty()
        }

        val noContinuation = Fixture().apply {
            learnerTurnId = null
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102, 2),
                VoiceTutorStudySnapshot(102, 101, "Child", 4, revision = 2),
            )
            handler = { name, _ -> if (name == "get_study") {
                success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Child"))
            } else failure("UNEXPECTED_TOOL") }
        }
        assertCode(
            noContinuation.adapter.execute(
                context(VoiceTutorInputIntent.CONTINUE_TREE, targetTopic = "Child"),
                "advance_voice_study", mapOf("study_id" to 102L),
            ),
            "LEARNER_CONTINUATION_REQUIRED",
        )
        assertThat(noContinuation.focusSelections).isEmpty()
    }

    @Test
    fun `guided progression requires an existing focus and rejects caller supplied metadata`(): Unit = runBlocking {
        val discovery = Fixture().apply { persistedSession = discoverySession() }
        assertCode(
            discovery.adapter.execute(
                context().copy(session = discoverySession()),
                "advance_voice_study",
                mapOf("study_id" to 102L),
            ),
            "GUIDED_FOCUS_REQUIRED",
        )
        assertCode(
            Fixture().adapter.execute(
                context(),
                "advance_voice_study",
                mapOf("study_id" to 102L, "parent_study_id" to 101L),
            ),
            "INVALID_ARGUMENTS",
        )
    }

    @Test
    fun `focus rejects revoked calls before commit but preserves the committed epoch when revoke follows it`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = discoverySession(); focusResult = focusSelection(101, 1) }
        val context = context().copy(session = discoverySession())
        fixture.learnerTurnId = null
        assertCode(fixture.adapter.execute(context, "select_voice_study", mapOf("study_id" to 101L)), "LEARNER_CHOICE_REQUIRED")
        fixture.learnerTurnId = 11
        fixture.authorized = false
        assertCode(fixture.adapter.execute(context, "select_voice_study", mapOf("study_id" to 101L)), "CALL_NOT_AUTHORIZED")
        assertThat(fixture.focusSelections).isEmpty()
        fixture.authorized = true
        fixture.afterFocus = { fixture.authorized = false }
        val late = fixture.adapter.execute(context, "select_voice_study", mapOf("study_id" to 101L))
        assertThat(late.isError).isFalse()
        assertThat(late.lessonFocus?.studyId).isEqualTo(101)
        assertThat(late.lessonRevision).isEqualTo(1)
        assertThat(json(late).path("voiceLessonFocus").path("revision").asLong()).isEqualTo(1)
    }

    @Test
    fun `focus tools require the server classified intent for their exact persisted turn`(): Unit = runBlocking {
        val selection = Fixture().apply {
            persistedSession = discoverySession()
            focusResult = focusSelection(101, 1)
        }
        assertCode(
            selection.adapter.execute(
                context(VoiceTutorInputIntent.CONTINUE_TREE).copy(session = discoverySession()),
                "select_voice_study",
                mapOf("study_id" to 101L),
            ),
            "LEARNER_CHOICE_REQUIRED",
        )
        assertCode(
            selection.adapter.execute(
                context(providerItemId = "different-user-item").copy(session = discoverySession()),
                "select_voice_study",
                mapOf("study_id" to 101L),
            ),
            "LEARNER_CHOICE_REQUIRED",
        )
        assertThat(selection.focusSelections).isEmpty()

        val advance = Fixture().apply {
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102, 1),
                VoiceTutorStudySnapshot(102, 101, "Child", 4, revision = 1),
            )
            handler = { name, _ -> if (name == "get_study") {
                success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Child"))
            } else failure("UNEXPECTED_TOOL") }
        }
        assertCode(
            advance.adapter.execute(context(), "advance_voice_study", mapOf("study_id" to 102L)),
            "LEARNER_CONTINUATION_REQUIRED",
        )
        advance.completedExchange = false
        assertCode(
            advance.adapter.execute(
                context(VoiceTutorInputIntent.CONTINUE_TREE, targetTopic = "Child"),
                "advance_voice_study",
                mapOf("study_id" to 102L),
            ),
            "CURRENT_EXCHANGE_INCOMPLETE",
        )
        advance.completedExchange = true
        val readinessOnly = context(VoiceTutorInputIntent.CONTINUE_TREE, targetTopic = "Child").let { base ->
            base.copy(dialogueBoundary = base.dialogueBoundary?.copy(
                precedingTutorFeedbackForStudyAnswer = false,
            ))
        }
        assertCode(
            advance.adapter.execute(readinessOnly, "advance_voice_study", mapOf("study_id" to 102L)),
            "CURRENT_EXCHANGE_INCOMPLETE",
        )
        assertThat(advance.focusSelections).isEmpty()
    }

    @Test
    fun `focus tools reject stale lesson intent and continuation before spoken feedback drains`(): Unit = runBlocking {
        val store = ContextStore().apply { revision = 1 }
        val fixture = Fixture(studyContexts = store).apply {
            acceptedLessonRevision = 1
            focusResult = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(102, 2),
                VoiceTutorStudySnapshot(102, 101, "Child", 4, revision = 2),
            )
            handler = { name, _ -> if (name == "get_study") {
                success(mapOf("id" to 102L, "parentStudyId" to 101L, "topic" to "Child"))
            } else failure("UNEXPECTED_TOOL") }
        }
        assertCode(
            fixture.adapter.execute(
                context(VoiceTutorInputIntent.CONTINUE_TREE, lessonRevision = 0, targetTopic = "Child"),
                "advance_voice_study",
                mapOf("study_id" to 102L),
            ),
            "LEARNER_CONTINUATION_REQUIRED",
        )
        val overlapping = context(
            VoiceTutorInputIntent.CONTINUE_TREE,
            lessonRevision = 1,
            targetTopic = "Child",
        ).let { base ->
            base.copy(dialogueBoundary = base.dialogueBoundary?.copy(
                latestAcceptedLearnerSpeechStartedOrder = 2,
                precedingTutorSpeechStoppedOrder = 2,
            ))
        }
        assertCode(
            fixture.adapter.execute(overlapping, "advance_voice_study", mapOf("study_id" to 102L)),
            "CURRENT_EXCHANGE_INCOMPLETE",
        )
        assertThat(fixture.focusSelections).isEmpty()
    }

    @Test
    fun `failed or unverified focus never emits a successful focus or prepared level`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = discoverySession() }
        assertCode(fixture.adapter.execute(
            context().copy(session = discoverySession()), "select_voice_study", mapOf("study_id" to 101L),
        ), "LESSON_FOCUS_UNAVAILABLE")
        for (invalid in listOf(focusSelection(101, 0), focusSelection(201, 1),
            focusSelection(101, 1).let { it.copy(snapshot = it.snapshot.copy(difficulty = 0)) })) {
            fixture.persistedSession = discoverySession()
            fixture.focusHistory.clear()
            fixture.focusResult = invalid
            val result = fixture.adapter.execute(
                context().copy(session = discoverySession()), "select_voice_study", mapOf("study_id" to 101L),
            )
            assertCode(result, "LESSON_FOCUS_UNCONFIRMED")
            assertThat(result.lessonFocus).isNull()
            assertThat(result.lessonRevision).isNull()
        }
    }

    @Test
    fun `focus enrichment failure cannot misreport a committed selection as a failed operation`(): Unit = runBlocking {
        val fixture = Fixture(studyContexts = ContextStore().apply { failList = true }).apply {
            persistedSession = discoverySession(); focusResult = focusSelection(101, 1)
        }
        val result = fixture.adapter.execute(context().copy(session = discoverySession()), "select_voice_study", mapOf("study_id" to 101L))
        assertThat(result.isError).isFalse()
        assertThat(json(result).path("voiceLessonFocus").path("studyId").asLong()).isEqualTo(101)
        assertThat(result.output).doesNotContain("private-database-detail")
        assertThat(fixture.focusSelections).hasSize(1)
    }

    @Test
    fun `switching trees follows the persisted focus not the immutable initial request or a mere read`(): Unit = runBlocking {
        val fixture = mutationFixture().apply { focusResult = focusSelection(201, 1) }
        assertThat(fixture.adapter.execute(
            context(targetStudyId = 201L), "select_voice_study", mapOf("study_id" to 201L),
        ).isError).isFalse()
        assertThat(fixture.persistedSession?.acceptedStudyId).isEqualTo(101)
        val original = fixture.handler
        fixture.handler = { name, args -> if (name == "create_study_topic") success(mapOf(
            "created" to true, "id" to 202L, "parentStudyId" to 201L, "topic" to "Child", "difficultyLevel" to 5,
        ))
            else original(name, args) }
        assertThat(fixture.adapter.execute(
            childContext(201L, "Child"), "create_study_topic",
            mapOf("parent_study_id" to 201L, "topic" to "Child"),
        ).isError).isFalse()
        assertThat(fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 101L)).isError).isFalse()
        assertCode(fixture.adapter.execute(
            childContext(101L, "Wrong tree"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Wrong tree"),
        ), "STUDY_SCOPE_DENIED")
    }

    @Test
    fun `node history and voice detail preserve source evidence and allow verified same tree siblings`(): Unit = runBlocking {
        val contextStore = ContextStore()
        val fixture = Fixture(studyContexts = contextStore).apply { persistedSession = session().copy(studyId = 201L, acceptedStudyId = 201L) }
        val page = mapOf(
            "items" to listOf(mapOf("id" to "voice:91", "source" to "VOICE_TUTOR", "studyId" to 202L,
                "voiceRecord" to learningRecordPayload(202L))),
            "nextCursor" to "same-scope-position", "hasMore" to true, "limit" to 3,
        )
        fixture.handler = { name, args ->
            when (name) {
                "get_study" -> {
                    val id = (args.getValue("study_id") as Number).toLong()
                    success(mapOf("id" to id, "parentStudyId" to if (id == 101L) null else 101L, "difficultyLevel" to 9))
                }
                "list_study_learning_records" -> success(page)
                "get_voice_learning_record" -> success(learningRecordPayload(202L))
                else -> error("Unexpected tool")
            }
        }
        val active = context().copy(session = fixture.persistedSession!!)

        val listResult = fixture.adapter.execute(active, "list_study_learning_records", mapOf(
            "study_id" to 202L, "scope" to "node", "limit" to 3, "cursor" to "prior-position", "view" to "original",
        ))
        val detailResult = fixture.adapter.execute(active, "get_voice_learning_record", mapOf("record_id" to 91L, "view" to "original"))

        assertThat(listResult.isError).isFalse()
        // Compare the complete wire-decoded trees: valueToTree keeps Kotlin Long
        // nodes, but JSON parsing uses IntNode for the same small integer values.
        assertThat(json(listResult)).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(page)))
        assertThat(detailResult.isError).isFalse()
        assertThat(json(detailResult)).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(learningRecordPayload(202L))))
        assertThat(json(detailResult).path("difficulty").asInt()).isEqualTo(3)
        assertThat(listResult.studyTreeChanged).isFalse()
        assertThat(detailResult.studyTreeChanged).isFalse()
        assertThat(detailResult.createdStudyId).isNull()
        assertThat(fixture.calls.single { it.name == "list_study_learning_records" }.arguments)
            .containsEntry("cursor", "prior-position").containsEntry("study_id", 202L)
        assertThat(fixture.calls.map { it.principal }).allMatch { it === principal }
        assertThat(contextStore.remembered).isEmpty()
    }

    @Test
    fun `new learning history cannot cross a root or guess missing foreign cyclic and deep ancestry`(): Unit = runBlocking {
        val rejectedNodes = listOf<(Long) -> McpSchema.CallToolResult>(
            { id -> success(mapOf("id" to id, "parentStudyId" to null)) },
            { _ -> failure("RECORD_NOT_FOUND") },
            { id -> success(mapOf("id" to id)) },
            { _ -> success(mapOf("id" to 999L, "parentStudyId" to 101L)) },
            { id -> success(mapOf("id" to id, "parentStudyId" to 101.5)) },
            { id -> success(mapOf("id" to id, "parentStudyId" to if (id == 201L) 202L else 201L)) },
            { id -> success(mapOf("id" to id, "parentStudyId" to id + 1)) },
        )
        for (readNode in rejectedNodes) {
            val fixture = Fixture().apply {
                handler = { name, args ->
                    assertThat(name).isEqualTo("get_study")
                    val id = (args.getValue("study_id") as Number).toLong()
                    if (id == 101L) success(mapOf("id" to id, "parentStudyId" to null)) else readNode(id)
                }
            }
            assertCode(fixture.adapter.execute(context(), "list_study_learning_records", mapOf("study_id" to 201L)), "STUDY_SCOPE_DENIED")
            assertThat(fixture.calls).hasSizeLessThanOrEqualTo(33)
            assertThat(fixture.calls.map { it.name }).containsOnly("get_study")
        }
        val noStudy = Fixture().apply { persistedSession = session().copy(studyId = null, acceptedStudyId = null) }
        assertCode(noStudy.adapter.execute(context().copy(session = noStudy.persistedSession!!), "list_study_learning_records", mapOf("study_id" to 101L)), "STUDY_SCOPE_DENIED")
        assertThat(noStudy.calls).isEmpty()
    }

    @Test
    fun `voice detail uses the returned owned record node and never exposes outside tree or mismatched identity`(): Unit = runBlocking {
        for ((record, expectedCode) in listOf(
            learningRecordPayload(201L) to "STUDY_SCOPE_DENIED",
            (learningRecordPayload(101L) + ("id" to "92")) to "INVALID_TOOL_RESULT",
            (learningRecordPayload(101L) + ("studyId" to "101")) to "INVALID_TOOL_RESULT",
        )) {
            val fixture = Fixture().apply {
                handler = { name, args ->
                    when (name) {
                        "get_voice_learning_record" -> success(record)
                        "get_study" -> success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to null))
                        else -> error("Unexpected tool")
                    }
                }
            }
            val result = fixture.adapter.execute(context(), "get_voice_learning_record", mapOf("record_id" to 91L))
            assertCode(result, expectedCode)
            assertThat(result.output).doesNotContain("private-prior-answer", "synthetic-prior-session")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(fixture.calls.map { it.name }).doesNotContain("get_record", "create_study_topic")
        }
    }

    @Test
    fun `new history rechecks active call and device authorization after suspended reads`(): Unit = runBlocking {
        for (logout in listOf(false, true)) {
            val fixture = Fixture()
            fixture.handler = { name, args ->
                if (name == "get_study") success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to null))
                else {
                    assertThat(name).isEqualTo("list_study_learning_records")
                    if (logout) fixture.authorized = false
                    else fixture.persistedSession = session().copy(status = VoiceTutorSessionStatus.ENDING)
                    success(mapOf("items" to listOf(learningRecordPayload(101L))))
                }
            }
            val result = fixture.adapter.execute(context(), "list_study_learning_records", mapOf("study_id" to 101L))
            assertCode(result, "CALL_NOT_AUTHORIZED")
            assertThat(result.output).doesNotContain("private-prior-answer")
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "list_study_learning_records")
        }
        val revoked = Fixture().apply { authorized = false }
        assertCode(revoked.adapter.execute(context(), "get_voice_learning_record", mapOf("record_id" to 91L)), "CALL_NOT_AUTHORIZED")
        assertThat(revoked.calls).isEmpty()
    }

    @Test
    fun `oversized learning pages and voice detail fail explicitly without truncating original evidence`(): Unit = runBlocking {
        for (name in listOf("list_study_learning_records", "get_voice_learning_record")) {
            val fixture = Fixture().apply {
                handler = { tool, args ->
                    if (tool == "get_study") success(mapOf("id" to args.getValue("study_id"), "parentStudyId" to null))
                    else success(learningRecordPayload(101L) + ("answer" to "private-prior-answer".repeat(2_000)))
                }
            }
            val args = if (name == "list_study_learning_records") mapOf("study_id" to 101L) else mapOf("record_id" to 91L)
            val result = fixture.adapter.execute(context(), name, args)
            assertCode(result, "RESULT_TOO_LARGE")
            assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16 * 1_024)
            assertThat(result.output).doesNotContain("private-prior-answer")
            assertThat(json(result).path("error").path("message").asText()).contains("not cut into partial JSON")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(result.createdStudyId).isNull()
        }
    }

    @Test
    fun `unavailable legacy port has no tools and never grants implicit access`(): Unit = runBlocking {
        assertThat(UnavailableVoiceTutorMcpToolPort.definitions()).isEmpty()
        val result = UnavailableVoiceTutorMcpToolPort.execute(context(), "get_study", mapOf("study_id" to 101L))
        assertThat(result.isError).isTrue()
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(json(result).path("error").path("code").asText()).isEqualTo("MCP_UNAVAILABLE")
    }

    @Test
    fun `executes the actual MCP adapter handler with the authenticated principal and no bearer token`(): Unit = runBlocking {
        var calledPrincipal: Principal? = null
        var calledId: Long? = null
        val useCase = proxy<BuddyStudyMcpUseCase> { method, arguments ->
            assertThat(method).isEqualTo("getStudy")
            calledPrincipal = arguments[0] as Principal
            calledId = arguments[1] as Long
            room(101)
        }
        val fixture = Fixture(BuddyStudyMcpAdapter(useCase, mapper))

        val result = fixture.adapter.execute(context(), "get_study", mapOf("study_id" to 101L, "language" to "ko"))

        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.createdStudyId).isNull()
        assertThat(calledPrincipal).isSameAs(principal)
        assertThat(calledId).isEqualTo(101L)
        assertThat(json(result).path("topic").asText()).isEqualTo("Redis")
        assertThat(result.output).doesNotContain("Authorization", "Bearer", "sessionId", "deviceId")
        // Entry, handler completion and metadata completion each revalidate;
        // the enrichment also reads the current (not originally accepted) focus.
        assertThat(fixture.authorizationCalls).isEqualTo(5)
        assertThat(fixture.persistenceCalls).isEqualTo(7)
    }

    @Test
    fun `missing anonymous or foreign principals cannot invoke any MCP handler`(): Unit = runBlocking {
        for (caller in listOf(null, principal.copy(anonymous = true), principal.copy(userId = 99L))) {
            val fixture = Fixture()
            val result = fixture.adapter.execute(context().copy(principal = caller), "list_studies", emptyMap())
            assertCode(result, "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls).isEmpty()
            assertThat(fixture.authorizationCalls).isZero()
        }
    }

    @Test
    fun `expired or mismatched original call contexts fail before session access`(): Unit = runBlocking {
        val invalidContexts = listOf(
            context().copy(callId = "rtc_other"),
            context().copy(session = session().copy(hardEndsAt = now)),
            context().copy(session = session().copy(status = VoiceTutorSessionStatus.COMPLETED)),
        )
        for (invalid in invalidContexts) {
            val fixture = Fixture()
            assertCode(fixture.adapter.execute(invalid, "list_studies", emptyMap()), "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls).isEmpty()
            assertThat(fixture.authorizationCalls).isZero()
        }
    }

    @Test
    fun `revoked device authorization is checked afresh for each tool`(): Unit = runBlocking {
        val fixture = Fixture()
        assertThat(fixture.adapter.execute(context(), "list_studies", emptyMap()).isError).isFalse()
        val authorizationCallsAfterSuccess = fixture.authorizationCalls
        val persistenceCallsAfterSuccess = fixture.persistenceCalls
        fixture.authorized = false

        assertCode(fixture.adapter.execute(context(), "list_studies", emptyMap()), "CALL_NOT_AUTHORIZED")

        // The revoked follow-up performs a fresh device check and stops before
        // another persistence read or MCP handler invocation. Exact successful
        // read recheck counts are deliberately not part of this contract.
        assertThat(fixture.authorizationCalls).isGreaterThan(authorizationCallsAfterSuccess)
        assertThat(fixture.persistenceCalls).isEqualTo(persistenceCallsAfterSuccess)
        assertThat(fixture.calls).hasSize(1)
    }

    @Test
    fun `current persisted call must still be active owned unexpired and match the provider and study`(): Unit = runBlocking {
        val invalidSessions = listOf(
            null,
            session().copy(status = VoiceTutorSessionStatus.READY),
            session().copy(status = VoiceTutorSessionStatus.ENDING),
            session().copy(status = VoiceTutorSessionStatus.COMPLETED),
            session().copy(status = VoiceTutorSessionStatus.FAILED),
            session().copy(id = "different-session"),
            session().copy(userId = 99L),
            session().copy(studyId = 999L),
            session().copy(acceptedStudyId = 999L),
            session().copy(providerSessionId = "rtc_different"),
            session().copy(hardEndsAt = now),
        )
        for (persisted in invalidSessions) {
            val fixture = Fixture().apply { persistedSession = persisted }
            assertCode(fixture.adapter.execute(context(), "list_studies", emptyMap()), "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls).isEmpty()
            assertThat(fixture.authorizationCalls).isEqualTo(1)
            assertThat(fixture.persistenceCalls).isEqualTo(1)
        }
    }

    @Test
    fun `all other existing MCP mutations remain unavailable in voice calls`(): Unit = runBlocking {
        for (name in listOf("create_study", "request_question", "submit_answer", "update_my_learning_context", "unknown")) {
            val fixture = Fixture()
            assertCode(fixture.adapter.execute(context(), name, emptyMap()), "TOOL_NOT_ALLOWED")
            assertThat(fixture.catalogReads).isZero()
            assertThat(fixture.calls).isEmpty()
        }
    }

    @Test
    fun `SDK schema validation rejects fractional overflowing missing foreign and malformed arguments`(): Unit = runBlocking {
        val invalid = listOf(
            "get_study" to emptyMap<String, Any>(),
            "get_study" to mapOf("study_id" to "101"),
            "get_study" to mapOf("study_id" to 1.25),
            "get_study" to mapOf("study_id" to Double.NaN),
            "get_study" to mapOf("study_id" to 0),
            "get_study" to mapOf("study_id" to 1e40),
            "get_study" to mapOf("study_id" to 101L, "user_id" to 99),
            "get_study" to mapOf("study_id" to 101L, "language" to "invalid"),
            "list_studies" to mapOf("limit" to 501),
            "list_studies" to mapOf("offset" to -1),
            "list_studies" to mapOf("offset" to Long.MAX_VALUE),
            "list_records" to mapOf("limit" to 101),
            "list_study_learning_records" to emptyMap<String, Any>(),
            "list_study_learning_records" to mapOf("study_id" to 101L, "limit" to 31),
            "list_study_learning_records" to mapOf("study_id" to 101L, "scope" to "all"),
            "list_study_learning_records" to mapOf("study_id" to 101L, "offset" to 1),
            "list_study_learning_records" to mapOf("study_id" to 101L, "cursor" to ""),
            "list_study_learning_records" to mapOf("study_id" to 101L, "cursor" to "x".repeat(513)),
            "get_voice_learning_record" to mapOf("record_id" to "voice:91"),
            "get_voice_learning_record" to mapOf("record_id" to 91.5),
            "get_voice_learning_record" to mapOf("record_id" to 91L, "user_id" to 99L),
            "create_study_topic" to mapOf("parent_study_id" to 101L, "topic" to ""),
            "create_study_topic" to mapOf("parent_study_id" to 101L, "topic" to "가".repeat(256)),
            "create_study_topic" to mapOf("parent_study_id" to 101L, "topic" to "Streams", "sort_order" to 10_001),
            "create_study_topic" to mapOf("parent_study_id" to 101L, "topic" to "Streams", "active_for_questions" to "true"),
        )
        for ((name, arguments) in invalid) {
            val fixture = Fixture()
            assertCode(fixture.adapter.execute(context(), name, arguments), "INVALID_ARGUMENTS")
            assertThat(fixture.calls).isEmpty()
            assertThat(fixture.authorizationCalls).isZero()
        }
    }

    @Test
    fun `argument byte bound rejects large data without reflecting it in the result`(): Unit = runBlocking {
        val fixture = Fixture()
        val result = fixture.adapter.execute(context(), "list_studies", mapOf("query" to "비공개".repeat(4_000)))
        assertCode(result, "INVALID_ARGUMENTS")
        assertThat(result.output).doesNotContain("비공개")
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `creates a child of the current study through the original handler preserving short titles`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.handler = { _, _ -> success(mapOf(
            "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "2", "difficultyLevel" to 7,
        )) }

        val result = fixture.adapter.execute(
            childContext(101L, "2", 7), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "2", "difficulty_level" to 7),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.createdStudyId).isEqualTo(102L)
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
        assertThat(fixture.calls.single().arguments)
            .isEqualTo(mapOf("parent_study_id" to 101L, "topic" to "2", "difficulty_level" to 7))
        assertThat(fixture.calls.single().principal).isSameAs(principal)
        assertThat(fixture.authorizationCalls).isEqualTo(2)
    }

    @Test
    fun `an idempotent existing child is available without announcing a new tree change or replaying`() = runBlocking {
        val contexts = ContextStore()
        val fixture = Fixture(studyContexts = contexts).apply {
            handler = { _, _ -> success(mapOf(
                "created" to false,
                "id" to 102L,
                "parentStudyId" to 101L,
                "topic" to "Redis Streams",
                "difficultyLevel" to 5,
            )) }
        }
        val context = childContext(101L, "redis streams", difficulty = 7)
        val arguments = mapOf<String, Any>(
            "parent_study_id" to 101L,
            "topic" to "redis streams",
            "difficulty_level" to 7,
        )

        val result = fixture.adapter.execute(context, "create_study_topic", arguments)

        assertThat(result.isError).isFalse()
        assertThat(json(result).path("created").asBoolean()).isFalse()
        assertThat(json(result).path("difficultyLevel").asInt()).isEqualTo(5)
        assertThat(json(result).path("notice").asText()).contains("already available", "level 5 was preserved")
        assertThat(result.studyTreeChanged).isFalse()
        assertThat(result.createdStudyId).isNull()
        assertThat(result.changedStudyId).isNull()
        assertThat(result.changeKind).isNull()
        assertThat(contexts.remembered).containsExactly(listOf(102L))
        assertCode(
            fixture.adapter.execute(context, "create_study_topic", arguments),
            "CHILD_CREATION_REQUEST_REQUIRED",
        )
        assertThat(fixture.calls.map { it.name }).containsExactly("create_study_topic")
    }

    @Test
    fun `walks owned ancestors before creating within a descendant`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.handler = { name, arguments ->
            when (name) {
                "get_study" -> when ((arguments.getValue("study_id") as Number).toLong()) {
                    301L -> success(mapOf("id" to 301L, "parentStudyId" to 201L))
                    201L -> success(mapOf("id" to 201L, "parentStudyId" to 101L))
                    else -> error("Unexpected ancestor")
                }
                "create_study_topic" -> success(mapOf(
                    "created" to true, "id" to 401L, "parentStudyId" to 301L, "topic" to "Streams", "difficultyLevel" to 5,
                ))
                else -> error("Unexpected tool")
            }
        }

        val result = fixture.adapter.execute(
            childContext(301L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 301L, "topic" to "Streams"),
        )

        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(fixture.calls.map { it.name }).containsExactly("get_study", "get_study", "create_study_topic")
        assertThat(fixture.calls.map { it.principal }).allMatch { it === principal }
        assertThat(fixture.calls.take(2).map { it.arguments["language"] }).containsExactly("ko", "ko")
    }

    @Test
    fun `different roots failed ownership checks and malformed ancestor results never authorize creation`(): Unit = runBlocking {
        val invalidAncestors = listOf(
            success(mapOf("id" to 201L, "parentStudyId" to null)),
            success(mapOf("id" to 999L, "parentStudyId" to 101L)),
            success(mapOf("id" to 201L, "parentStudyId" to "101")),
            success(mapOf("id" to 201L, "parentStudyId" to 101.5)),
            failure("PERMISSION_DENIED"),
            McpSchema.CallToolResult.builder().addTextContent("unstructured result").isError(false).build(),
        )
        for (response in invalidAncestors) {
            val fixture = Fixture().apply { handler = { _, _ -> response } }
            val result = fixture.adapter.execute(
                childContext(201L, "Streams"), "create_study_topic",
                mapOf("parent_study_id" to 201L, "topic" to "Streams"),
            )
            assertCode(result, "STUDY_SCOPE_DENIED")
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        }
    }

    @Test
    fun `a call with no study cannot create topics`(): Unit = runBlocking {
        val fixture = Fixture().apply { persistedSession = session().copy(studyId = null, acceptedStudyId = null) }
        val result = fixture.adapter.execute(
            childContext(101L, "Streams").copy(session = fixture.persistedSession!!),
            "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Streams"),
        )
        assertCode(result, "STUDY_SCOPE_DENIED")
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `ancestor cycles and excessive depth are bounded and fail closed`(): Unit = runBlocking {
        val cyclic = Fixture().apply {
            handler = { _, arguments ->
                val id = (arguments.getValue("study_id") as Number).toLong()
                success(mapOf("id" to id, "parentStudyId" to if (id == 201L) 301L else 201L))
            }
        }
        assertCode(cyclic.adapter.execute(
            childContext(201L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 201L, "topic" to "Streams"),
        ), "STUDY_SCOPE_DENIED")
        assertThat(cyclic.calls).hasSize(2)

        val deep = Fixture().apply {
            handler = { _, arguments ->
                val id = (arguments.getValue("study_id") as Number).toLong()
                success(mapOf("id" to id, "parentStudyId" to id + 1))
            }
        }
        assertCode(deep.adapter.execute(
            childContext(201L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 201L, "topic" to "Streams"),
        ), "STUDY_SCOPE_DENIED")
        assertThat(deep.calls).hasSize(32)
        assertThat(deep.calls.map { it.name }).containsOnly("get_study")
    }

    @Test
    fun `ending or logout during ancestry is rechecked before a write`(): Unit = runBlocking {
        for (logout in listOf(false, true)) {
            val fixture = Fixture()
            fixture.handler = { _, _ ->
                if (logout) fixture.authorized = false
                else fixture.persistedSession = session().copy(status = VoiceTutorSessionStatus.ENDING)
                success(mapOf("id" to 201L, "parentStudyId" to 101L))
            }
            assertCode(fixture.adapter.execute(
                childContext(201L, "Streams"), "create_study_topic",
                mapOf("parent_study_id" to 201L, "topic" to "Streams"),
            ), "CALL_NOT_AUTHORIZED")
            assertThat(fixture.calls.map { it.name }).containsExactly("get_study")
        }
    }

    @Test
    fun `MCP permission and conflict failures remain errors and cannot announce a tree change`(): Unit = runBlocking {
        for (code in listOf("PERMISSION_DENIED", "VALIDATION_ERROR")) {
            val fixture = Fixture().apply { handler = { _, _ -> failure(code) } }
            val result = fixture.adapter.execute(
                childContext(101L, "Streams"), "create_study_topic",
                mapOf("parent_study_id" to 101L, "topic" to "Streams"),
            )
            assertCode(result, code)
            assertThat(result.studyTreeChanged).isFalse()
            assertThat(result.createdStudyId).isNull()
        }
    }

    @Test
    fun `only positive integral bounded IDs from a successful creation become refresh targets`(): Unit = runBlocking {
        for (invalidId in listOf(-1, 0, 1.5, "102", 1e40)) {
            val fixture = Fixture().apply { handler = { _, _ -> success(mapOf(
                "created" to true, "id" to invalidId, "parentStudyId" to 101L, "topic" to "Streams", "difficultyLevel" to 5,
            )) } }
            val result = fixture.adapter.execute(
                childContext(101L, "Streams"), "create_study_topic",
                mapOf("parent_study_id" to 101L, "topic" to "Streams"),
            )
            assertCode(result, "CHILD_CREATION_RESULT_UNCONFIRMED")
            assertThat(result.createdStudyId).isNull()
        }
        val failureWithId = Fixture().apply {
            handler = { _, _ -> McpSchema.CallToolResult.builder().structuredContent(mapOf("id" to 102L)).isError(true).build() }
        }
        assertThat(failureWithId.adapter.execute(
            childContext(101L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Streams"),
        ).createdStudyId).isNull()
    }

    @Test
    fun `oversized UTF8 read results return bounded valid JSON with explicit retry guidance`(): Unit = runBlocking {
        val fixture = Fixture().apply { handler = { _, _ -> success(mapOf("studies" to listOf("가".repeat(30_000)))) } }
        val result = fixture.adapter.execute(context(), "list_studies", emptyMap())
        assertCode(result, "RESULT_TOO_LARGE")
        assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16 * 1_024)
        assertThat(json(result).path("error").path("message").asText()).contains("smaller page")
        assertThat(result.studyTreeChanged).isFalse()
    }

    @Test
    fun `read output accepts exactly sixteen KiB and rejects the next byte`(): Unit = runBlocking {
        val exact = Fixture().apply { handler = { _, _ -> success(mapOf("text" to "x".repeat(16 * 1_024 - 11))) } }
        val accepted = exact.adapter.execute(context(), "list_studies", emptyMap())
        assertThat(accepted.isError).isFalse()
        assertThat(accepted.output.toByteArray(Charsets.UTF_8).size).isEqualTo(16 * 1_024)

        val oversized = Fixture().apply { handler = { _, _ -> success(mapOf("text" to "x".repeat(16 * 1_024 - 10))) } }
        assertCode(oversized.adapter.execute(context(), "list_studies", emptyMap()), "RESULT_TOO_LARGE")
    }

    @Test
    fun `oversized successful creation retains success and only bounded tree metadata`(): Unit = runBlocking {
        val fixture = Fixture().apply {
            handler = { _, _ -> success(mapOf(
                "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Streams",
                "sortOrder" to 2, "difficultyLevel" to 5,
                "customPrompt" to "비공개".repeat(20_000),
            )) }
        }
        val result = fixture.adapter.execute(
            childContext(101L, "Streams"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "Streams"),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.createdStudyId).isEqualTo(102L)
        assertThat(json(result).path("id").asLong()).isEqualTo(102L)
        assertThat(json(result).path("parentStudyId").asLong()).isEqualTo(101L)
        assertThat(json(result).path("truncated").asBoolean()).isTrue()
        assertThat(result.output).doesNotContain("비공개", "customPrompt")
        assertThat(result.output.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(16 * 1_024)
    }

    @Test
    fun `unexpected handler failures are sanitized instead of leaking private exception details`(): Unit = runBlocking {
        val fixture = Fixture().apply { handler = { _, _ -> throw IllegalStateException("private-study-and-token-value") } }
        val result = fixture.adapter.execute(context(), "list_studies", emptyMap())
        assertCode(result, "MCP_UNAVAILABLE")
        assertThat(result.output).doesNotContain("private-study-and-token-value", "IllegalStateException")
    }

    @Test
    fun `call cancellation propagates instead of being converted to a successful tool result`() {
        val fixture = Fixture().apply { handler = { _, _ -> throw CancellationException("closed") } }
        assertThatThrownBy {
            runBlocking { fixture.adapter.execute(context(), "list_studies", emptyMap()) }
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `explicit update keeps node identity and captures a new question level after the saved write`(): Unit = runBlocking {
        val contexts = ContextStore()
        val fixture = mutationFixture(contexts)
        val result = fixture.adapter.execute(
            updateContext(102L, difficulty = 6), "update_study",
            mapOf("study_id" to 102L, "difficulty_level" to 6),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.changeKind).isEqualTo(VoiceTutorStudyChangeKind.UPDATED)
        assertThat(result.createdStudyId).isNull()
        assertThat(result.changedStudyId).isEqualTo(102)
        assertThat(result.lessonRevision).isEqualTo(1)
        assertThat(result.lessonFocus?.studyId).isEqualTo(101)
        assertThat(result.lessonFocus?.revision).isEqualTo(1)
        assertThat(result.lessonFocus?.topic).isEqualTo("Selected root")
        assertThat(result.lessonFocus?.difficulty).isEqualTo(5)
        assertThat(contexts.remembered).containsExactly(listOf(102L))
        assertThat(contexts.revised).containsExactly(102)
        assertThat(json(result).path("voiceLessonChangeApplies").asText()).isEqualTo("NEXT_QUESTION")
        assertThat(json(result).path("voiceLessonTopics")[0].path("difficulty").asInt()).isEqualTo(6)
        assertThat(fixture.calls.single { it.name == "update_study" }.arguments)
            .isEqualTo(mapOf<String, Any>("study_id" to 102L, "difficulty_level" to 6))
    }

    @Test
    fun `persisted child and update leases execute exact patches once and reject replay or field smuggling`(): Unit =
        runBlocking {
            val childFixture = Fixture(studyContexts = ContextStore()).apply {
                handler = { _, _ -> success(mapOf(
                    "created" to true, "id" to 102L, "parentStudyId" to 101L, "topic" to "Streams", "difficultyLevel" to 5,
                )) }
            }
            val childContext = childContext(101L, "Streams")
            val childArguments = mapOf<String, Any>("parent_study_id" to 101L, "topic" to "Streams")

            assertCode(
                childFixture.adapter.execute(
                    childContext,
                    "create_study_topic",
                    childArguments + ("difficulty_level" to 7),
                ),
                "CHILD_CREATION_REQUEST_MISMATCH",
            )
            assertThat(childFixture.calls).isEmpty()
            assertThat(childFixture.adapter.execute(childContext, "create_study_topic", childArguments).isError).isFalse()
            assertCode(
                childFixture.adapter.execute(childContext, "create_study_topic", childArguments),
                "CHILD_CREATION_REQUEST_REQUIRED",
            )
            assertThat(childFixture.calls.count { it.name == "create_study_topic" }).isEqualTo(1)

            val updateFixture = mutationFixture(ContextStore())
            val updateContext = updateContext(102L, topic = "Renamed cache", difficulty = 6)
            val updateArguments = mapOf<String, Any>(
                "study_id" to 102L,
                "topic" to "Renamed cache",
                "difficulty_level" to 6,
            )

            assertCode(
                updateFixture.adapter.execute(
                    updateContext,
                    "update_study",
                    mapOf("study_id" to 102L, "difficulty_level" to 6),
                ),
                "STUDY_UPDATE_REQUEST_MISMATCH",
            )
            assertThat(updateFixture.adapter.execute(updateContext, "update_study", updateArguments).isError).isFalse()
            assertCode(
                updateFixture.adapter.execute(updateContext, "update_study", updateArguments),
                "STUDY_UPDATE_REQUEST_REQUIRED",
            )
            assertCode(
                mutationFixture(ContextStore()).adapter.execute(
                    updateContext(102L, difficulty = 6),
                    "update_study",
                    mapOf("study_id" to 102L, "topic" to "   ", "difficulty_level" to 6),
                ),
                "INVALID_ARGUMENTS",
            )
            assertThat(updateFixture.calls.count { it.name == "update_study" }).isEqualTo(1)
            assertThat(updateFixture.calls.single { it.name == "update_study" }.arguments)
                .isEqualTo(updateArguments)
        }

    @Test
    fun `metadata capture failure keeps a confirmed update distinct from an unprepared lesson`(): Unit = runBlocking {
        val contexts = ContextStore().apply { failRevision = true }
        val fixture = mutationFixture(contexts)
        val result = fixture.adapter.execute(
            updateContext(102L, topic = "Renamed cache"), "update_study",
            mapOf("study_id" to 102L, "topic" to "Renamed cache"),
        )
        assertThat(result.isError).isFalse()
        assertThat(result.studyTreeChanged).isTrue()
        assertThat(result.changeKind).isEqualTo(VoiceTutorStudyChangeKind.UPDATED)
        assertThat(result.lessonRevision).isNull()
        assertThat(json(result).path("voiceLessonContextReady").asBoolean()).isFalse()
        assertThat(json(result).path("voiceLessonChangeApplies").asText()).isEqualTo("NOT_PREPARED")
        assertThat(result.output).doesNotContain("private-revision-detail")
        assertThat(fixture.calls.count { it.name == "update_study" }).isEqualTo(1)
    }

    @Test
    fun `unprepared and revision capped calls cannot change settings`(): Unit = runBlocking {
        for ((contexts, revision) in listOf(
            UnavailableVoiceTutorStudyContextPort to 0L,
            ContextStore().apply { revision = 32 } to 32L,
        )) {
            val fixture = mutationFixture(contexts)
            fixture.acceptedLessonRevision = revision
            assertCode(fixture.adapter.execute(
                updateContext(102L, difficulty = 6, lessonRevision = revision), "update_study",
                mapOf("study_id" to 102L, "difficulty_level" to 6),
            ), "LESSON_CONTEXT_UNAVAILABLE")
            assertThat(fixture.calls.none { it.name == "update_study" }).isTrue()
        }
    }

    @Test
    fun `mutations cannot escape the verified owned call tree`(): Unit = runBlocking {
        val fixture = mutationFixture()
        assertCode(fixture.adapter.execute(
            updateContext(900L, difficulty = 6), "update_study",
            mapOf("study_id" to 900L, "difficulty_level" to 6),
        ), "STUDY_SCOPE_DENIED")
        assertCode(fixture.adapter.execute(
            context(), "delete_study", mapOf("study_id" to 900L, "confirm" to false),
        ), "STUDY_SCOPE_DENIED")
        assertThat(fixture.calls.none { it.name in listOf("update_study", "delete_study") }).isTrue()
    }

    @Test
    fun `delete requires a spoken preview and a newer learner confirmation then executes exactly once`(): Unit = runBlocking {
        val fixture = mutationFixture()
        assertCode(fixture.adapter.execute(context(), "delete_study", mapOf("study_id" to 101L, "confirm" to true)), "CONFIRMATION_REQUIRED")
        val preview = fixture.adapter.execute(context(), "delete_study", mapOf("study_id" to 101L, "confirm" to false))
        assertThat(preview.isError).isFalse()
        assertThat(preview.studyTreeChanged).isFalse()
        assertThat(json(preview).path("deleted").asBoolean()).isFalse()
        assertThat(json(preview).path("descendantCount").asInt()).isEqualTo(2)
        val arguments = mapOf("study_id" to 101L, "confirm" to true, "confirmation_token" to json(preview).path("confirmation_token").asText())
        assertCode(fixture.adapter.execute(context(), "delete_study", arguments), "CONFIRMATION_REQUIRED")
        fixture.learnerTurnId = 12 // A new utterance BEFORE a spoken preview is still not confirmation.
        assertCode(fixture.adapter.execute(context(), "delete_study", arguments), "CONFIRMATION_REQUIRED")
        fixture.tutorTurnId = 13
        fixture.learnerTurnId = 14
        val deleted = fixture.adapter.execute(confirmedContext(), "delete_study", arguments)
        assertThat(deleted.isError).isFalse()
        assertThat(deleted.changeKind).isEqualTo(VoiceTutorStudyChangeKind.DELETED)
        assertThat(deleted.deletedStudyIds).containsExactly(101, 102, 103)
        assertThat(deleted.changedStudyId).isEqualTo(101)
        assertThat(json(deleted).path("voiceLessonSelectionDeleted").asBoolean()).isTrue()
        assertThat(fixture.calls.single { it.name == "delete_study" }.arguments)
            .containsEntry("expected_study_ids", listOf(101L, 102L, 103L))
            .doesNotContainKey("confirmation_token")
        assertCode(fixture.adapter.execute(confirmedContext(), "delete_study", arguments), "CONFIRMATION_REQUIRED")
        assertThat(fixture.calls.count { it.name == "delete_study" }).isEqualTo(1)
    }

    @Test
    fun `changed deletion scope consumes the preview without falsely announcing success or replaying`(): Unit = runBlocking {
        val fixture = mutationFixture()
        val preview = fixture.adapter.execute(context(), "delete_study", mapOf("study_id" to 101L, "confirm" to false))
        fixture.tutorTurnId = 13
        fixture.learnerTurnId = 14
        val ordinary = fixture.handler
        fixture.handler = { name, args -> if (name == "delete_study") failure("STUDY_TREE_CHANGED") else ordinary(name, args) }
        val arguments = mapOf("study_id" to 101L, "confirm" to true, "confirmation_token" to json(preview).path("confirmation_token").asText())
        val result = fixture.adapter.execute(confirmedContext(), "delete_study", arguments)
        assertCode(result, "STUDY_TREE_CHANGED")
        assertThat(result.studyTreeChanged).isFalse()
        assertCode(fixture.adapter.execute(confirmedContext(), "delete_study", arguments), "CONFIRMATION_REQUIRED")
        assertThat(fixture.calls.count { it.name == "delete_study" }).isEqualTo(1)
    }

    @Test
    fun `incomplete cyclic malformed or oversized subtree cannot create a deletion preview`(): Unit = runBlocking {
        for (page in listOf(
            mapOf("studies" to emptyList<Any>(), "totalCount" to 1, "offset" to 0),
            mapOf("studies" to listOf(mapOf("id" to 101L, "parentStudyId" to 101L)), "totalCount" to 1, "offset" to 0),
            mapOf("studies" to listOf(mapOf("id" to 102L, "parentStudyId" to 999L)), "totalCount" to 1, "offset" to 0),
            mapOf("studies" to (102L..230L).map { mapOf("id" to it, "parentStudyId" to 101L) }, "totalCount" to 129, "offset" to 0),
        )) {
            val fixture = mutationFixture()
            val ordinary = fixture.handler
            fixture.handler = { name, args -> if (name == "list_studies") success(page) else ordinary(name, args) }
            assertCode(fixture.adapter.execute(context(), "delete_study", mapOf("study_id" to 101L, "confirm" to false)), "DELETE_PREVIEW_UNAVAILABLE")
            assertThat(fixture.calls.none { it.name == "delete_study" }).isTrue()
        }
    }

    @Test
    fun `model supplied scope manifests and malformed confirmation tokens are rejected`(): Unit = runBlocking {
        val fixture = mutationFixture()
        for (extra in listOf(mapOf("expected_study_ids" to listOf(101L)), mapOf("confirmation_token" to 23), mapOf("confirmation_token" to "x".repeat(101)))) {
            assertCode(fixture.adapter.execute(context(), "delete_study", mapOf("study_id" to 101L, "confirm" to true) + extra), "INVALID_ARGUMENTS")
        }
        assertThat(fixture.calls).isEmpty()
    }

    @Test
    fun `deleting the selected live node preserves call authorization without granting a missing parent`(): Unit = runBlocking {
        val fixture = mutationFixture().apply { persistedSession = session().copy(studyId = null, acceptedStudyId = 101L) }
        val read = fixture.adapter.execute(context(), "list_studies", emptyMap())
        assertThat(read.isError).isFalse()
        assertCode(fixture.adapter.execute(
            childContext(101L, "New"), "create_study_topic",
            mapOf("parent_study_id" to 101L, "topic" to "New"),
        ), "STUDY_SCOPE_DENIED")
        assertThat(fixture.calls.none { it.name == "create_study_topic" }).isTrue()
    }

    @Test
    fun `deletion confirmation is bound to identity target fresh dialogue and expiry`() {
        var instant = now
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = instant
        }
        val tickets = VoiceTutorDeletionConfirmations(clock)
        val ticket = tickets.prepare(context(), 101, listOf(101, 102), 11)!!
        assertThat(tickets.consume(confirmedContext(), 102, ticket.token, 14)).isNull()
        assertThat(tickets.consume(confirmedContext().copy(callId = "another-call"), 101, ticket.token, 14)).isNull()
        assertThat(tickets.consume(confirmedContext(), 101, ticket.token, 11)).isNull()
        assertThat(tickets.consume(context(), 101, ticket.token, 14)).isNull()
        instant = now.plusSeconds(120)
        assertThat(tickets.consume(confirmedContext(), 101, ticket.token, 14)).isNull()
        instant = now
        val second = tickets.prepare(context(), 101, listOf(101), 11)!!
        assertThat(tickets.consume(confirmedContext(), 101, second.token, 14)).isNotNull()
        assertThat(tickets.consume(confirmedContext(), 101, second.token, 15)).isNull()
    }

    @Test
    fun `late accepted ASR from speech before the confirmation question is not new consent`() {
        val tickets = VoiceTutorDeletionConfirmations(Clock.fixed(now, ZoneOffset.UTC))
        val ticket = tickets.prepare(context(), 101, listOf(101), 11)!!
        val latePublication = confirmedContext().copy(dialogueBoundary = VoiceTutorDialogueBoundary(
            responseGeneration = 4, latestAcceptedLearnerSpeechStartedOrder = 4,
            precedingTutorSpeechStoppedOrder = 6, precedingSpokenResponseGeneration = 3,
        ))
        // The database assigns U14 AFTER T13, but its real speech began BEFORE
        // the confirmation prompt finished. Later unapproved noise cannot change this order.
        assertThat(tickets.consume(latePublication, 101, ticket.token, 14)).isNull()
        val oldMixedAudio = confirmedContext().copy(dialogueBoundary = VoiceTutorDialogueBoundary(
            responseGeneration = 4, latestAcceptedLearnerSpeechStartedOrder = 7,
            precedingTutorSpeechStoppedOrder = 6, precedingSpokenResponseGeneration = 2,
        ))
        assertThat(tickets.consume(oldMixedAudio, 101, ticket.token, 14)).isNull()
        assertThat(tickets.consume(confirmedContext(), 101, ticket.token, 15)).isNotNull()
    }

    private fun confirmedContext() = context().copy(dialogueBoundary = VoiceTutorDialogueBoundary(
        responseGeneration = 4, latestAcceptedLearnerSpeechStartedOrder = 7,
        precedingTutorSpeechStoppedOrder = 6, precedingSpokenResponseGeneration = 3,
    ))

    private fun mutationFixture(studyContexts: VoiceTutorStudyContextPort = ContextStore()) = Fixture(studyContexts = studyContexts).apply {
        handler = { name, args ->
            when (name) {
                "get_study" -> {
                    val id = (args.getValue("study_id") as Number).toLong()
                    success(mapOf("id" to id, "parentStudyId" to if (id in 102L..103L) 101L else null,
                        "topic" to "Cache", "difficultyLevel" to 3))
                }
                "list_studies" -> {
                    val children = if (args["parent_study_id"] == 101L) listOf(102L, 103L) else emptyList()
                    success(mapOf("studies" to children.map { mapOf("id" to it, "parentStudyId" to 101L) }, "totalCount" to children.size, "offset" to 0))
                }
                "update_study" -> success(mapOf("id" to args["study_id"], "topic" to (args["topic"] ?: "Cache"),
                    "difficultyLevel" to (args["difficulty_level"] ?: 3), "parentStudyId" to 101L))
                "delete_study" -> success(mapOf("deleted" to true, "studyId" to args["study_id"]))
                else -> failure("UNEXPECTED_TOOL")
            }
        }
    }

    private class Fixture(
        actualMcp: BuddyStudyMcpPort? = null,
        studyContexts: VoiceTutorStudyContextPort = UnavailableVoiceTutorStudyContextPort,
    ) {
        var authorized = true
        var persistedSession: VoiceTutorSession? = session()
        var authorizationCalls = 0
        var persistenceCalls = 0
        var catalogReads = 0
        var learnerTurnId: Long? = 11
        var tutorTurnId: Long? = 10
        var completedExchange = true
        var duringLearnerAuthorization: () -> Unit = {}
        var acceptedProviderItemId = "accepted-user-item"
        var acceptedLessonRevision = 0L
        var lastFocusLearnerTurnId = 0L
        var focusResult: VoiceTutorLessonFocusSelection? = null
        var afterFocus: () -> Unit = {}
        var lastExpectedTraversal: VoiceTutorStudyTargetTraversal? = null
        val focusSelections = mutableListOf<Long>()
        val focusHistory = mutableListOf<VoiceTutorLessonFocus>()
        val calls = mutableListOf<Call>()
        var handler: (String, Map<String, Any>) -> McpSchema.CallToolResult = { _, _ -> success(mapOf("ok" to true)) }
        val catalog = BuddyStudyMcpAdapter(proxy<BuddyStudyMcpUseCase> { method, _ -> error("Unexpected direct call: $method") }, mapper).tools()
        private val mcp = actualMcp ?: object : BuddyStudyMcpPort {
            override fun tools(): List<McpStatelessServerFeatures.AsyncToolSpecification> {
                catalogReads += 1
                return catalog.map { specification ->
                    McpStatelessServerFeatures.AsyncToolSpecification(specification.tool()) { context, request ->
                        Mono.fromCallable {
                            val caller = context.get(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY) as? Principal
                            val arguments = request.arguments().orEmpty()
                            calls += Call(request.name(), arguments, caller)
                            handler(request.name(), arguments)
                        }
                    }
                }
            }

            override fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification> = emptyList()
        }
        val adapter = McpVoiceTutorToolAdapter(
            mcp = mcp,
            authorization = object : VoiceTutorRelayAuthorizationPort {
                override suspend fun isAuthorized(userId: Long, deviceId: String, authSessionId: Long, now: Instant): Boolean {
                    authorizationCalls += 1
                    assertThat(userId).isEqualTo(principal.userId)
                    assertThat(deviceId).isEqualTo(principal.deviceId)
                    assertThat(authSessionId).isEqualTo(principal.sessionId)
                    return authorized
                }
            },
            persistence = proxy<VoiceTutorPersistencePort> { method, arguments ->
                assertThat(method).isEqualTo("findSession")
                assertThat(arguments[0]).isEqualTo(principal.userId)
                assertThat(arguments[1]).isEqualTo(session().id)
                persistenceCalls += 1
                persistedSession
            },
            objectMapper = mapper,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            studyContexts = studyContexts,
            confirmations = object : VoiceTutorMutationConfirmationPort {
                override suspend fun latestLearnerTurnId(userId: Long, sessionId: String) = learnerTurnId
                override suspend fun latestTutorTurnId(userId: Long, sessionId: String) = tutorTurnId
                override suspend fun learnerTurnAuthorization(
                    userId: Long,
                    sessionId: String,
                    providerItemId: String,
                    lessonRevision: Long,
                    expectedQuestionProviderItemId: String?,
                    expectedAnswerProviderItemId: String?,
                    expectedTutorFeedbackProviderItemId: String?,
                    expectedTutorNavigationOfferProviderItemId: String?,
                ): VoiceTutorLearnerTurnAuthorization? = learnerTurnId?.takeIf {
                    providerItemId == acceptedProviderItemId && lessonRevision == acceptedLessonRevision
                }?.let {
                    duringLearnerAuthorization()
                    VoiceTutorLearnerTurnAuthorization(it, completedExchange)
                }
            },
            lessonFocus = object : VoiceTutorLessonFocusPort {
                override suspend fun history(userId: Long, sessionId: String) = focusHistory.toList()
                override suspend fun focus(
                    userId: Long,
                    sessionId: String,
                    studyId: Long,
                    learnerTurnId: Long?,
                    expectedCurrentRevision: Long?,
                    authorization: VoiceTutorFocusAuthorization?,
                    expectedCandidate: VoiceTutorStudyTargetCandidate?,
                    commitAuthority: VoiceTutorFocusCommitAuthority?,
                    expectedTraversal: VoiceTutorStudyTargetTraversal?,
                ): VoiceTutorLessonFocusSelection? {
                    assertThat(learnerTurnId).isEqualTo(this@Fixture.learnerTurnId)
                    assertThat(expectedCurrentRevision).isEqualTo(acceptedLessonRevision)
                    assertThat(commitAuthority).isEqualTo(VoiceTutorFocusCommitAuthority(
                        principal.deviceId, principal.sessionId, session().providerSessionId!!,
                    ))
                    lastExpectedTraversal = expectedTraversal
                    if (authorization?.consume() != true) return null
                    val selected = selectFocus(userId, sessionId, studyId)
                    if (selected != null && learnerTurnId != null) lastFocusLearnerTurnId = learnerTurnId
                    return selected
                }

                override suspend fun advance(
                    userId: Long,
                    sessionId: String,
                    currentStudyId: Long,
                    childStudyId: Long,
                    learnerTurnId: Long,
                    expectedCurrentRevision: Long?,
                    authorization: VoiceTutorFocusAuthorization?,
                    expectedCandidate: VoiceTutorStudyTargetCandidate?,
                    commitAuthority: VoiceTutorFocusCommitAuthority?,
                    expectedTraversal: VoiceTutorStudyTargetTraversal?,
                ): VoiceTutorLessonFocusSelection? {
                    assertThat(persistedSession?.studyId).isEqualTo(currentStudyId)
                    assertThat(learnerTurnId).isEqualTo(this@Fixture.learnerTurnId)
                    assertThat(expectedCurrentRevision).isEqualTo(acceptedLessonRevision)
                    assertThat(commitAuthority).isEqualTo(VoiceTutorFocusCommitAuthority(
                        principal.deviceId, principal.sessionId, session().providerSessionId!!,
                    ))
                    lastExpectedTraversal = expectedTraversal
                    if (authorization?.consume() != true) return null
                    if (learnerTurnId <= lastFocusLearnerTurnId) return null
                    val selected = selectFocus(userId, sessionId, childStudyId)
                    if (selected != null) lastFocusLearnerTurnId = learnerTurnId
                    return selected
                }

                private fun selectFocus(
                    userId: Long,
                    sessionId: String,
                    studyId: Long,
                ): VoiceTutorLessonFocusSelection? {
                    assertThat(userId).isEqualTo(principal.userId)
                    assertThat(sessionId).isEqualTo(session().id)
                    focusSelections += studyId
                    val value = focusResult ?: return null
                    focusHistory += value.focus
                    persistedSession = persistedSession?.copy(studyId = value.studyId, topic = value.snapshot.topic, difficulty = value.snapshot.difficulty)
                    afterFocus()
                    return value
                }
            },
        )
    }

    private class ContextStore : VoiceTutorStudyContextPort {
        val remembered = mutableListOf<List<Long>>()
        val saved = linkedMapOf(101L to VoiceTutorStudySnapshot(101, null, "Selected root", 5))
        var fail = false
        var failList = false
        var listReads = 0
        var afterList: () -> Unit = {}
        var revision = 0L
        var failRevision = false
        val revised = mutableListOf<Long>()
        override suspend fun currentRevision(userId: Long, sessionId: String) = revision
        override suspend fun revise(userId: Long, sessionId: String, studyId: Long): List<VoiceTutorStudySnapshot> {
            if (failRevision) throw IllegalStateException("private-revision-detail")
            revised += studyId
            val next = saved.getValue(studyId).copy(difficulty = 6, revision = ++revision)
            saved[studyId] = next
            return listOf(next)
        }
        override suspend fun prepare(session: VoiceTutorSession) = emptyList<VoiceTutorStudySnapshot>()
        override suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> {
            assertThat(userId).isEqualTo(principal.userId)
            assertThat(sessionId).isEqualTo(session().id)
            listReads += 1
            if (failList) throw IllegalStateException("private-database-detail")
            afterList()
            return saved.values.toList()
        }
        override suspend fun remember(userId: Long, sessionId: String, studyIds: List<Long>): List<VoiceTutorStudySnapshot> {
            assertThat(userId).isEqualTo(principal.userId)
            assertThat(sessionId).isEqualTo(session().id)
            remembered += studyIds
            if (fail) throw IllegalStateException("private-database-detail")
            return studyIds.map { id -> saved.getOrPut(id) { VoiceTutorStudySnapshot(id, 101, "Cache", 3) } }
        }
    }

    private data class Call(val name: String, val arguments: Map<String, Any>, val principal: Principal?)

    private companion object {
        fun candidate(id: Long, parentStudyId: Long?): Map<String, Any?> = mapOf(
            "id" to id,
            "parentStudyId" to parentStudyId,
            "topic" to "Topic $id",
        )

        fun completePage(
            studies: List<Map<String, Any?>>,
            totalCount: Int,
            limit: Int,
            offset: Int = 0,
        ): Map<String, Any?> = mapOf(
            "studies" to studies,
            "totalCount" to totalCount,
            "limit" to limit,
            "offset" to offset,
        )

        fun learningRecordPayload(studyId: Long): Map<String, Any?> = linkedMapOf(
            "id" to "91", "sessionId" to "synthetic-prior-session", "studyId" to studyId,
            "parentStudyId" to 101L, "topic" to "Redis", "difficulty" to 3, "kind" to "TUTOR_QUESTION",
            "question" to "어떤 키를 제거하나요?", "answer" to "  private-prior-answer\n", "score" to 85,
            "feedback" to "85점입니다.", "strengths" to listOf("최근 사용 시점을 짚음"), "improvements" to emptyList<String>(),
            "depthSummary" to "LRU를 살펴봄", "questionTurnId" to 11L,
            "answerTurnIds" to listOf(12L), "feedbackTurnIds" to listOf(13L),
            "sourceLanguage" to "ko", "requestedLanguage" to "ko", "displayLanguage" to "ko", "translationPending" to false,
        )

        val now: Instant = Instant.parse("2026-08-31T00:00:00Z")
        val principal = Principal(userId = 7L, deviceId = "synthetic-device", sessionId = 11L, anonymous = false)
        val mapper = jacksonObjectMapper().findAndRegisterModules()

        fun discoverySession() = session().copy(studyId = null, acceptedStudyId = null, topic = "", difficulty = 0)
        fun focusSelection(id: Long, revision: Long) = VoiceTutorLessonFocusSelection(
            VoiceTutorLessonFocus(id, revision), VoiceTutorStudySnapshot(id, null, "Redis", 3, revision),
        )

        fun context(
            inputIntent: VoiceTutorInputIntent = VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
            lessonRevision: Long = 0,
            providerItemId: String? = "accepted-user-item",
            targetStudyId: Long? = when (inputIntent) {
                VoiceTutorInputIntent.SELECT_SAVED_TOPIC -> 101L
                VoiceTutorInputIntent.CONTINUE_TREE -> 102L
                else -> null
            },
            targetParentStudyId: Long? = 101L.takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
            targetTopic: String = if (inputIntent == VoiceTutorInputIntent.CONTINUE_TREE) "Cache" else "Redis",
            targetTraversal: VoiceTutorStudyTargetTraversal? = targetStudyId?.let {
                VoiceTutorStudyTargetTraversal()
            },
            rootTopic: String = "운영체제",
            rootDifficulty: Int = 6,
        ) = VoiceTutorWebRtcControlContext(
            session(), "rtc_synthetic_call", principal,
            dialogueBoundary = VoiceTutorDialogueBoundary(
                responseGeneration = 2, latestAcceptedLearnerSpeechStartedOrder = 3,
                precedingTutorSpeechStoppedOrder = 2, precedingSpokenResponseGeneration = 1,
                latestAcceptedLearnerProviderItemId = providerItemId,
                latestAcceptedLearnerLessonRevision = lessonRevision,
                latestAcceptedLearnerIntent = inputIntent,
                precedingTutorFeedbackForStudyAnswer = inputIntent == VoiceTutorInputIntent.CONTINUE_TREE,
                precedingQuestionProviderItemId = "accepted-question-item"
                    .takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
                precedingAnswerProviderItemId = "accepted-answer-item"
                    .takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
                precedingTutorFeedbackProviderItemId = "accepted-feedback-item"
                    .takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
                precedingTutorNavigationOfferProviderItemId = "accepted-offer-item"
                    .takeIf { inputIntent == VoiceTutorInputIntent.CONTINUE_TREE },
                latestAcceptedLearnerTargetStudyId = targetStudyId,
                latestAcceptedLearnerTargetOfferId = targetStudyId?.let { 1L },
                latestAcceptedLearnerTargetCandidate = targetStudyId?.let { id ->
                    VoiceTutorStudyTargetCandidate(
                        studyId = id,
                        parentStudyId = targetParentStudyId,
                        topic = targetTopic,
                    )
                },
                latestAcceptedLearnerTargetTraversal = targetTraversal,
                focusAuthorization = targetStudyId?.let { VoiceTutorFocusAuthorization() },
                rootStudyCreationAuthorization = if (inputIntent == VoiceTutorInputIntent.CREATE_ROOT_STUDY) {
                    VoiceTutorRootStudyCreationAuthorization(rootTopic, rootDifficulty)
                } else {
                    null
                },
            ),
        )

        fun childContext(
            parentStudyId: Long,
            topic: String,
            difficulty: Int = 5,
            lessonRevision: Long = 0,
        ): VoiceTutorWebRtcControlContext {
            val base = context(
                inputIntent = VoiceTutorInputIntent.CREATE_STUDY_TOPIC,
                lessonRevision = lessonRevision,
            )
            return base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
                childStudyCreationAuthorization = VoiceTutorChildStudyCreationAuthorization(
                    parentStudyId,
                    topic,
                    difficulty,
                ),
            ))
        }

        fun updateContext(
            studyId: Long,
            topic: String? = null,
            difficulty: Int? = null,
            lessonRevision: Long = 0,
        ): VoiceTutorWebRtcControlContext {
            val base = context(
                inputIntent = VoiceTutorInputIntent.UPDATE_STUDY,
                lessonRevision = lessonRevision,
            )
            return base.copy(dialogueBoundary = requireNotNull(base.dialogueBoundary).copy(
                studyUpdateAuthorization = VoiceTutorStudyUpdateAuthorization(studyId, topic, difficulty),
            ))
        }

        fun session() = VoiceTutorSession(
            id = "00000000-0000-0000-0000-000000000007", userId = principal.userId, studyId = 101L,
            idempotencyKey = "synthetic-call", providerSessionId = "rtc_synthetic_call",
            status = VoiceTutorSessionStatus.ACTIVE, resultStatus = VoiceTutorResultStatus.PENDING,
            language = "ko", model = "gpt-realtime", voice = "marin", topic = "Redis", difficulty = 5,
            periodStartedAt = now.minusSeconds(60), periodEndsAt = now.plusSeconds(86_400),
            reservedSeconds = 3_600, chargedSeconds = 0, maxSessionSeconds = 3_600,
            hardEndsAt = now.plusSeconds(3_600), connectedAt = now.minusSeconds(5), relayHeartbeatAt = now,
            acceptedAudioBytes = 0, endedAt = null, finalizedAt = null, endReason = null,
            failureCode = null, failureMessage = null, createdAt = now.minusSeconds(10), updatedAt = now,
        )

        fun room(id: Long) = StudyRoomResponse(
            id = id, parentStudyId = null, sortOrder = 0, topic = "Redis", difficultyLevel = 5,
            intervalMinutes = 30, enabled = false, activeForQuestions = true, notificationSound = null,
            customPrompt = "", openaiModel = "gpt-5.4", maxHistoryCount = 100,
            nextDueAt = null, lastSentAt = null, lastError = null, pendingQuestion = null,
            createdAt = now, updatedAt = now,
        )

        fun success(payload: Any): McpSchema.CallToolResult = McpSchema.CallToolResult.builder()
            .structuredContent(payload).isError(false).build()

        fun failure(code: String): McpSchema.CallToolResult = McpSchema.CallToolResult.builder()
            .structuredContent(mapOf("error" to mapOf("code" to code))).isError(true).build()

        fun json(result: VoiceTutorMcpToolResult): JsonNode = mapper.readTree(result.output)

        fun assertCode(result: VoiceTutorMcpToolResult, code: String) {
            assertThat(result.isError).isTrue()
            assertThat(json(result).path("error").path("code").asText()).isEqualTo(code)
        }

        inline fun <reified T> proxy(noinline handler: (String, List<Any?>) -> Any?): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "Synthetic${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> handler(method.name, arguments?.toList().orEmpty())
            }
        } as T
    }
}
