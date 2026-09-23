package com.buddystudy.backend

import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyRecordType
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.util.UUID

/** Real post-V105 rows: canonical record ID and internal extension ID are separate identities. */
internal suspend fun publicFeedVoiceFixture(
    database: DatabaseClient,
    questions: QuestionPort,
    userId: Long,
    topic: String,
    text: String,
    isPublic: Boolean = true,
    answer: String = "PRIVATE VOICE ANSWER",
    deleted: Boolean = false,
): QuestionEntity {
    val session = UUID.randomUUID().toString()
    val now = Instant.now()
    database.sql("""
        insert into voice_tutor_sessions (
            id, user_id, idempotency_key, status, result_status, language, model, voice,
            topic_snapshot, difficulty_snapshot, period_started_at, period_ends_at,
            reserved_seconds, max_session_seconds, hard_ends_at, created_at, updated_at
        ) values (
            :id, :user, :id, 'COMPLETED', 'COMPLETED', 'ko', 'test', 'marin',
            :topic, 5, :now, :end, 60, 60, :end, :now, :now
        )
    """.trimIndent()).bind("id", session).bind("user", userId).bind("topic", topic)
        .bind("now", now).bind("end", now.plusSeconds(3600)).fetch().rowsUpdated().awaitSingle()
    val extensionId = database.sql("""
        insert into voice_study_learning_records (
            user_id, session_id, study_id, kind, strengths_json, improvements_json,
            depth_summary, question_turn_id, answer_turn_ids_json, feedback_turn_ids_json,
            source_languages_json, source_hash, created_at
        ) values (
            :user, :session, 1, 'TUTOR_QUESTION', '[]', '[]', '', 1, '[2]', '[]',
            '{"question":"ko","answer":"ko"}', :hash, :now
        )
    """.trimIndent()).bind("user", userId).bind("session", session).bind("hash", session)
        .bind("now", now).filter { it.returnGeneratedValues("id") }
        .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
    return questions.save(QuestionEntity(
        userId = userId, question = text, topic = topic, answer = answer,
        sourceLanguage = SupportedLanguage.KOREAN, answerSourceLanguage = SupportedLanguage.KOREAN,
        recordType = StudyRecordType.VOICE_TUTOR, voiceRecordId = extensionId,
        status = QuestionStatus.COMPLETED, source = QuestionSource.VOICE_TUTOR,
        publicQuestion = isPublic, deletedAt = if (deleted) now else null,
    ))
}
