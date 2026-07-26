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
        val language = users.findById(principal.userId)?.appLanguage ?: "ko"
        val requestedCount = count.coerceIn(1, 8)
        val recommended = suggestions.suggestTopics(
            rootTopic = root.topic,
            parentTopic = parent.topic,
            existingTopics = allStudies.map { it.topic },
            language = language,
            count = requestedCount,
        )
        val unique = linkedMapOf<String, String>()
        recommended.forEach { raw ->
            val topic = raw.trim().replace(Regex("\\s+"), " ")
            val key = topic.normalizedStudyTopicKey()
            if (topic.isNotEmpty() && key !in existingKeys) {
                unique.putIfAbsent(key, topic)
            }
        }
        return StudyTopicSuggestionsResponse(
            parentStudyId = parentStudyId,
            suggestions = unique.values.take(requestedCount),
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

    fun nextActiveTopic(root: StudyEntity, allStudies: Collection<StudyEntity>): StudyEntity? {
        return subtreeFor(root, allStudies)
            .filter { it.activeForQuestions }
            .minWithOrNull(
                compareBy<StudyEntity> { it.lastSentAt != null }
                    .thenBy { it.lastSentAt }
                    .thenBy { it.sortOrder }
                    .thenBy { it.id },
            )
    }
}
