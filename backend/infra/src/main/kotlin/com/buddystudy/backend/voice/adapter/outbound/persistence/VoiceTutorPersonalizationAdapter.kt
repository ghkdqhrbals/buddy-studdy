package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersonalization
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorPersonalizationPort
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

@Repository
class VoiceTutorPersonalizationAdapter(
    private val database: DatabaseClient,
) : VoiceTutorPersonalizationPort {
    private val mapper = JsonMapperProvider.mapper

    override suspend fun load(userId: Long, studyId: Long): VoiceTutorPersonalization {
        val learningContext = database.sql(
            "select resume_markdown, interests_json from user_learning_contexts where user_id = :userId",
        ).bind("userId", userId)
            .map { row, _ ->
                val interestsJson = row.get("interests_json", String::class.java).orEmpty()
                LearningContext(
                    resumeMarkdown = row.get("resume_markdown", String::class.java),
                    interests = runCatching { mapper.readValue<List<String>>(interestsJson) }.getOrDefault(emptyList()),
                )
            }.one().awaitSingleOrNull() ?: LearningContext(null, emptyList())

        val evidence = if (studyId <= 0) {
            emptyList()
        } else {
            database.sql(
                """
                select question, answer, feedback, score
                from questions
                where user_id = :userId and study_id = :studyId
                  and deleted_at is null and score is not null
                order by coalesce(graded_at, answered_at, created_at) desc, id desc
                limit 10
                """.trimIndent(),
            ).bind("userId", userId).bind("studyId", studyId)
                .map { row, _ ->
                    buildString {
                        append("Question: ").append(row.get("question", String::class.java).orEmpty().take(300))
                        row.get("answer", String::class.java)?.takeIf(String::isNotBlank)?.let {
                            append(" | Learner answer: ").append(it.take(300))
                        }
                        (row.get("score") as? Number)?.toInt()?.let { append(" | Score: ").append(it) }
                        row.get("feedback", String::class.java)?.takeIf(String::isNotBlank)?.let {
                            append(" | Feedback: ").append(it.take(300))
                        }
                    }
                }.all().collectList().awaitSingle()
        }
        return VoiceTutorPersonalization(
            resumeMarkdown = learningContext.resumeMarkdown,
            interests = learningContext.interests,
            recentLearningEvidence = evidence,
        )
    }

    private data class LearningContext(
        val resumeMarkdown: String?,
        val interests: List<String>,
    )
}
