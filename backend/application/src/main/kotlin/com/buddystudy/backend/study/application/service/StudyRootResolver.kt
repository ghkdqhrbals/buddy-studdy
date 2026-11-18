package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.entity.StudyEntity

/** Resolves only ancestors of visible nodes; siblings and complete trees are never fetched. */
internal class StudyRootResolver(
    private val studies: StudyPort,
    initial: Collection<StudyEntity>,
) {
    private val nodes = initial.associateBy { it.id }.toMutableMap()
    private val roots = mutableMapOf<Long, StudyEntity>()

    suspend fun rootFor(study: StudyEntity): StudyEntity {
        roots[study.id]?.let { return it }
        val path = mutableListOf<StudyEntity>()
        val visited = mutableSetOf<Long>()
        var current = study
        // Existing deeper trees remain readable, while malformed chains stay bounded.
        repeat(64) {
            roots[current.id]?.let { root -> return remember(path, root) }
            if (!visited.add(current.id)) return remember(path, current)
            path += current
            val parentId = current.parentStudyId ?: return remember(path, current)
            val parent = nodes[parentId]
                ?: studies.findByIdAndUserId(parentId, study.userId)?.also { nodes[it.id] = it }
                ?: return remember(path, current)
            current = parent
        }
        return remember(path, current)
    }

    private fun remember(path: List<StudyEntity>, root: StudyEntity): StudyEntity {
        path.forEach { roots[it.id] = root }
        return root
    }
}
