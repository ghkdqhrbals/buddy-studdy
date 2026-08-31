package com.buddystudy.backend.voice.application.model

import com.buddystudy.voice.domain.VoiceTutorStudySnapshot

/** Prompt metadata, not authorization: only frozen ID/parent edges establish a tree relationship. */
object VoiceTutorLessonTreeContext {
    private const val MAX_SNAPSHOTS = 64
    private const val MAX_PARENT_EDGES = 32

    fun metadata(
        selectedStudyId: Long?,
        snapshots: List<VoiceTutorStudySnapshot>,
        focusStudyIds: List<Long> = snapshots.take(MAX_SNAPSHOTS).map { it.studyId },
    ): Map<String, Any?> {
        val byId = linkedMapOf<Long, VoiceTutorStudySnapshot>()
        snapshots.take(MAX_SNAPSHOTS).filter {
            it.studyId > 0 && it.difficulty in 1..10 && it.topic.isNotBlank()
        }.forEach { byId.putIfAbsent(it.studyId, it) }
        val selectedPath = trace(selectedStudyId, byId)
        return linkedMapOf(
            "selectedStudyId" to selectedStudyId,
            "selectedRootStudyId" to selectedPath.rootId,
            "selectedPathStudyIds" to selectedPath.idsFromNode.reversed(),
            "selectedPathComplete" to selectedPath.complete,
            // The initial child list and every MCP page are bounded; absence never proves a leaf.
            "childrenMayBeIncomplete" to true,
            "nodes" to focusStudyIds.filter { it > 0 }.distinct().take(MAX_SNAPSHOTS).map { id ->
                val path = trace(id, byId)
                val relation = when {
                    id !in byId || selectedStudyId == null || selectedStudyId !in byId || path.invalid || selectedPath.invalid -> "UNRESOLVED"
                    id == selectedStudyId -> "SELECTED"
                    id in selectedPath.idsFromNode -> "ANCESTOR"
                    selectedStudyId in path.idsFromNode -> "DESCENDANT"
                    !path.complete || !selectedPath.complete -> "UNRESOLVED"
                    path.rootId == selectedPath.rootId -> "SAME_TREE_OTHER_BRANCH"
                    else -> "OTHER_TREE"
                }
                linkedMapOf(
                    "studyId" to id,
                    "relationToSelected" to relation,
                    "rootStudyId" to path.rootId,
                    "pathComplete" to path.complete,
                )
            },
        )
    }

    private fun trace(studyId: Long?, byId: Map<Long, VoiceTutorStudySnapshot>): Path {
        var currentId = studyId ?: return Path(emptyList())
        val path = mutableListOf<Long>()
        val seen = mutableSetOf<Long>()
        repeat(MAX_PARENT_EDGES + 1) {
            if (currentId <= 0 || !seen.add(currentId)) return Path(path, invalid = true)
            val node = byId[currentId] ?: return Path(path)
            path += currentId
            val parentId = node.parentStudyId ?: return Path(path, rootId = node.studyId, complete = true)
            currentId = parentId
        }
        return Path(path)
    }

    private data class Path(
        val idsFromNode: List<Long>,
        val rootId: Long? = null,
        val complete: Boolean = false,
        val invalid: Boolean = false,
    )
}
