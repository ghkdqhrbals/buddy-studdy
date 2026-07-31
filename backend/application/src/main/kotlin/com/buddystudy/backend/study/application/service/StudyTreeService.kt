package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.model.StudyRoomResponse
import com.buddystudy.backend.study.application.model.StudyTopicSuggestionsResponse
import com.buddystudy.backend.study.application.port.inbound.StudyTreeUseCase
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyTopicActivationCommand
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.port.outbound.StudyTopicSuggestionPort
import com.buddystudy.backend.study.application.port.outbound.SystemTopicCatalogPort
import com.buddystudy.study.domain.entity.StudyEntity
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudyTreeService(
    private val studies: StudyPort,
    private val users: UserPort,
    private val suggestions: StudyTopicSuggestionPort,
    private val topicCatalog: SystemTopicCatalogPort,
) : StudyTreeUseCase {
    override suspend fun suggestTopics(
        principal: Principal,
        parentStudyId: Long,
        count: Int,
    ): StudyTopicSuggestionsResponse {
        val allStudies = studies.findAllByUserId(principal.userId)
        val parent = allStudies.firstOrNull { it.id == parentStudyId }
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Parent study not found.")
        val root = StudyTreeSelector.rootFor(parent, allStudies)
        val existingKeys = allStudies.map { it.topic.normalizedStudyTopicKey() }.toSet()
        val language = users.findById(principal.userId)?.appLanguage?.databaseValue ?: "ko"
        val requestedCount = count.coerceIn(1, MAX_CHILDREN)
        val path = StudyTreeSelector.pathFromRoot(parent, allStudies)
        val childDepth = path.size
        if (childDepth > MAX_DEPTH) {
            return StudyTopicSuggestionsResponse(
                parentStudyId = parentStudyId,
                suggestions = emptyList(),
                source = "DEPTH_LIMIT",
                depth = childDepth,
                maxDepth = MAX_DEPTH,
                childLimit = MAX_CHILDREN,
            )
        }

        val rootTopicKey = root.topic.normalizedStudyTopicKey()
        val parentPathKey = path.joinToString("/") { it.topic.normalizedStudyTopicKey() }
        val cached = topicCatalog.findChildren(
            rootTopicKey = rootTopicKey,
            parentPathKey = parentPathKey,
            language = language,
            depth = childDepth,
            limit = MAX_CHILDREN,
        )
        val reusable = cached
            .map { it.topic }
            .filter { it.normalizedStudyTopicKey() !in existingKeys }
        val missingCount = (requestedCount - reusable.size).coerceAtLeast(0)
        val generated = if (missingCount > 0) {
            suggestions.suggestTopics(
                rootTopic = root.topic,
                parentTopic = parent.topic,
                existingTopics = allStudies.map { it.topic } + cached.map { it.topic },
                language = language,
                count = missingCount,
            )
        } else {
            emptyList()
        }
        val unique = linkedMapOf<String, String>()
        (reusable + generated).forEach { raw ->
            val topic = raw.trim().replace(Regex("\\s+"), " ")
            val key = topic.normalizedStudyTopicKey()
            if (topic.isNotEmpty() && key !in existingKeys) {
                unique.putIfAbsent(key, topic)
            }
        }
        if (generated.isNotEmpty()) {
            topicCatalog.saveChildren(
                rootTopicKey = rootTopicKey,
                parentPathKey = parentPathKey,
                language = language,
                depth = childDepth,
                topics = generated,
                now = Instant.now(),
            )
        }
        return StudyTopicSuggestionsResponse(
            parentStudyId = parentStudyId,
            suggestions = unique.values.take(requestedCount),
            source = if (generated.isEmpty()) "CATALOG" else if (reusable.isEmpty()) "GENERATED" else "MIXED",
            depth = childDepth,
            maxDepth = MAX_DEPTH,
            childLimit = MAX_CHILDREN,
        )
    }

    @Transactional
    override suspend fun updateTopicActivation(
        principal: Principal,
        studyId: Long,
        command: UpdateStudyTopicActivationCommand,
    ): StudyRoomResponse {
        val allStudies = studies.findAllByUserId(principal.userId)
        val study = allStudies.firstOrNull { it.id == studyId }
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        val root = StudyTreeSelector.rootFor(study, allStudies)
        val subtree = StudyTreeSelector.subtreeFor(root, allStudies)
        if (!command.active && study.activeForQuestions && subtree.none { it.id != study.id && it.activeForQuestions }) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "At least one topic must remain active for scheduled questions.",
            )
        }

        val now = Instant.now()
        study.activeForQuestions = command.active
        study.updatedAt = now

        if (command.active) {
            root.enabled = true
            if (root.nextDueAt == null) {
                root.nextDueAt = now.plusSeconds(root.intervalMinutes.coerceAtLeast(1).toLong() * 60)
            }
            root.lastError = null
            root.updatedAt = now
        }

        val savedStudy = studies.save(study)
        if (root.id != study.id && command.active) {
            studies.save(root)
        }
        return savedStudy.toStudyRoomResponse()
    }

    private companion object {
        const val MAX_DEPTH = 5
        const val MAX_CHILDREN = 10
    }
}

internal object StudyTreeSelector {
    fun rootFor(study: StudyEntity, allStudies: Collection<StudyEntity>): StudyEntity {
        val byId = allStudies.associateBy { it.id }
        var current = study
        val visited = mutableSetOf<Long>()
        while (current.parentStudyId != null && visited.add(current.id)) {
            current = byId[current.parentStudyId] ?: break
        }
        return current
    }

    fun subtreeFor(root: StudyEntity, allStudies: Collection<StudyEntity>): List<StudyEntity> {
        val byParent = allStudies.groupBy { it.parentStudyId }
        return buildList {
            val pending = ArrayDeque<StudyEntity>()
            pending.add(root)
            val visited = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!visited.add(current.id)) continue
                add(current)
                byParent[current.id].orEmpty()
                    .sortedWith(compareBy<StudyEntity> { it.sortOrder }.thenBy { it.id })
                    .forEach(pending::addLast)
            }
        }
    }

    fun pathFromRoot(study: StudyEntity, allStudies: Collection<StudyEntity>): List<StudyEntity> {
        val byId = allStudies.associateBy { it.id }
        val reversed = mutableListOf<StudyEntity>()
        val visited = mutableSetOf<Long>()
        var current: StudyEntity? = study
        while (current != null && visited.add(current.id)) {
            reversed += current
            current = current.parentStudyId?.let(byId::get)
        }
        return reversed.asReversed()
    }

    fun activeTopics(root: StudyEntity, allStudies: Collection<StudyEntity>): List<StudyEntity> =
        subtreeFor(root, allStudies).filter { it.activeForQuestions }

    fun nextActiveTopic(
        root: StudyEntity,
        allStudies: Collection<StudyEntity>,
        excludedStudyIds: Set<Long> = emptySet(),
    ): StudyEntity? {
        return activeTopics(root, allStudies)
            .filterNot { it.id in excludedStudyIds }
            .minWithOrNull(
                compareBy<StudyEntity> { it.lastSentAt != null }
                    .thenBy { it.lastSentAt }
                    .thenBy { it.sortOrder }
                    .thenBy { it.id },
            )
    }
}
