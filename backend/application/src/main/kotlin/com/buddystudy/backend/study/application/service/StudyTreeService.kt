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
        val study = studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        study.activeForQuestions = command.active
        study.updatedAt = Instant.now()
        return studies.save(study).toStudyRoomResponse()
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

    fun nextActiveTopic(root: StudyEntity, allStudies: Collection<StudyEntity>): StudyEntity? {
        val byParent = allStudies.groupBy { it.parentStudyId }
        val subtree = buildList {
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
        return subtree
            .filter { it.activeForQuestions }
            .minWithOrNull(
                compareBy<StudyEntity> { it.lastSentAt != null }
                    .thenBy { it.lastSentAt }
                    .thenBy { it.sortOrder }
                    .thenBy { it.id },
            )
    }
}
