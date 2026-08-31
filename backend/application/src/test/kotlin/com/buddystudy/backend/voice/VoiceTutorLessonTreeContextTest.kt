package com.buddystudy.backend.voice

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorLessonTreeContext
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoiceTutorLessonTreeContextTest {
    @Test
    fun `exact frozen edges distinguish focus ancestors descendants sibling branches and other roots`() {
        // Deliberately identical names and unrelated levels: neither establishes membership or scope.
        val snapshots = listOf(
            node(1, level = 9), node(7, 1, level = 2), node(42, 7, level = 5),
            node(43, 42, level = 3), node(44, 43, level = 8), node(8, 1), node(90), node(91, 90),
        )
        val tree = metadata(42, snapshots)

        assertThat(tree.path("selectedStudyId").asLong()).isEqualTo(42)
        assertThat(tree.path("selectedRootStudyId").asLong()).isEqualTo(1)
        assertThat(tree.path("selectedPathStudyIds").map { it.asLong() }).containsExactly(1, 7, 42)
        assertThat(tree.path("selectedPathComplete").asBoolean()).isTrue()
        assertThat(tree.path("childrenMayBeIncomplete").asBoolean()).isTrue()
        assertRelation(tree, 1, "ANCESTOR")
        assertRelation(tree, 7, "ANCESTOR")
        assertRelation(tree, 42, "SELECTED")
        assertRelation(tree, 43, "DESCENDANT")
        assertRelation(tree, 44, "DESCENDANT")
        assertRelation(tree, 8, "SAME_TREE_OTHER_BRANCH")
        assertRelation(tree, 90, "OTHER_TREE")
        assertRelation(tree, 91, "OTHER_TREE")
        assertThat(tree.toString()).doesNotContain("Identical title", "difficulty")
    }

    @Test
    fun `missing parent never becomes a root while exact known descendant edges remain usable`() {
        val tree = metadata(42, listOf(node(42, 7), node(43, 42), node(90)))
        assertThat(tree.path("selectedRootStudyId").isNull).isTrue()
        assertThat(tree.path("selectedPathStudyIds").map { it.asLong() }).containsExactly(42)
        assertThat(tree.path("selectedPathComplete").asBoolean()).isFalse()
        assertRelation(tree, 42, "SELECTED")
        assertRelation(tree, 43, "DESCENDANT")
        assertThat(treeNode(tree, 43).path("pathComplete").asBoolean()).isFalse()
        assertThat(treeNode(tree, 43).path("rootStudyId").isNull).isTrue()
        assertRelation(tree, 90, "UNRESOLVED")
    }

    @Test
    fun `cycles and invalid parent ids never certify a relation or complete root path`() {
        for (snapshots in listOf(
            listOf(node(42, 7), node(7, 42), node(43, 42)),
            listOf(node(42, 42), node(43, 42)),
            listOf(node(42, -1), node(43, 42)),
        )) {
            val tree = metadata(42, snapshots)
            assertThat(tree.path("selectedPathComplete").asBoolean()).isFalse()
            assertThat(tree.path("selectedRootStudyId").isNull).isTrue()
            assertRelation(tree, 42, "UNRESOLVED")
            assertRelation(tree, 43, "UNRESOLVED")
        }
    }

    @Test
    fun `a path follows at most thirty two parent edges and reports a longer path as incomplete`() {
        val chain = (1L..40L).map { node(it, if (it == 1L) null else it - 1) }
        val boundary = metadata(33, chain)
        assertThat(boundary.path("selectedPathComplete").asBoolean()).isTrue()
        assertThat(boundary.path("selectedRootStudyId").asLong()).isEqualTo(1)
        assertThat(boundary.path("selectedPathStudyIds").size()).isEqualTo(33)

        val longer = metadata(34, chain)
        assertThat(longer.path("selectedPathComplete").asBoolean()).isFalse()
        assertThat(longer.path("selectedRootStudyId").isNull).isTrue()
        assertThat(longer.path("selectedPathStudyIds").size()).isEqualTo(33)
        assertThat(longer.path("selectedPathStudyIds").first().asLong()).isEqualTo(2)
    }

    @Test
    fun `missing selected identity and uncaptured focus ids remain unresolved instead of inferring from a title`() {
        for (selected in listOf(null, 42L)) {
            val tree = metadata(selected, listOf(node(7)), listOf(7, 42, 99))
            assertThat(tree.path("selectedPathComplete").asBoolean()).isFalse()
            assertThat(tree.path("selectedPathStudyIds")).isEmpty()
            assertThat(tree.path("selectedRootStudyId").isNull).isTrue()
            assertRelation(tree, 7, "UNRESOLVED")
            assertRelation(tree, 42, "UNRESOLVED")
            assertRelation(tree, 99, "UNRESOLVED")
        }
    }

    @Test
    fun `MCP metadata names only the requested ids while ancestry can use the rest of the saved context`() {
        val tree = metadata(42, listOf(node(1), node(42, 1), node(43, 42), node(90)), listOf(43, -1, 0, 43))
        assertThat(tree.path("nodes").map { it.path("studyId").asLong() }).containsExactly(43)
        assertRelation(tree, 43, "DESCENDANT")
        assertThat(treeNode(tree, 43).path("pathComplete").asBoolean()).isTrue()
        assertThat(tree.path("selectedPathStudyIds").map { it.asLong() }).containsExactly(1, 42)
    }

    @Test
    fun `first snapshot wins over a conflicting later parent and both inputs and output stay bounded`() {
        val snapshots = listOf(node(42, 1), node(42, 90), node(1), node(90)) + (100L..200L).map { node(it) }
        val tree = metadata(42, snapshots, (1L..100L).toList())
        assertThat(tree.path("selectedRootStudyId").asLong()).isEqualTo(1)
        assertThat(tree.path("nodes").size()).isEqualTo(64)
        assertRelation(tree, 42, "SELECTED")
        val omitted = metadata(42, snapshots, listOf(200))
        assertRelation(omitted, 200, "UNRESOLVED")
        assertThat(treeNode(omitted, 200).path("pathComplete").asBoolean()).isFalse()
    }

    private fun node(id: Long, parent: Long? = null, level: Int = 5) =
        VoiceTutorStudySnapshot(id, parent, "Identical title", level)

    private fun metadata(
        selected: Long?,
        snapshots: List<VoiceTutorStudySnapshot>,
        focus: List<Long> = snapshots.map { it.studyId },
    ): JsonNode = JsonMapperProvider.mapper.valueToTree(VoiceTutorLessonTreeContext.metadata(selected, snapshots, focus))

    private fun treeNode(tree: JsonNode, id: Long) = tree.path("nodes").single { it.path("studyId").asLong() == id }

    private fun assertRelation(tree: JsonNode, id: Long, relation: String) {
        assertThat(treeNode(tree, id).path("relationToSelected").asText()).describedAs("study $id").isEqualTo(relation)
    }
}
