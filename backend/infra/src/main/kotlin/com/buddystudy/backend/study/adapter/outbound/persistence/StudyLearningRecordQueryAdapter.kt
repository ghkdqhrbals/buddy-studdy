package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.model.StudyLearningRecordKey
import com.buddystudy.backend.study.application.model.StudyLearningRecordScope
import com.buddystudy.backend.study.application.model.StudyLearningRecordSource
import com.buddystudy.backend.study.application.model.StudyLearningRecordsCursor
import com.buddystudy.backend.study.application.port.outbound.StudyLearningRecordQueryPort
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class StudyLearningRecordQueryAdapter(private val client: DatabaseClient) : StudyLearningRecordQueryPort {
    override suspend fun pageKeys(
        userId: Long,
        studyId: Long,
        scope: StudyLearningRecordScope,
        limit: Int,
        cursor: StudyLearningRecordsCursor?,
    ): List<StudyLearningRecordKey> {
        // The question's actual study_id is authoritative: matching a topic label is never enough.
        val nodes = if (scope == StudyLearningRecordScope.SUBTREE) {
            """
            with recursive tree_nodes(id, depth) as (
                select id, 0 from studies where id = :studyId and user_id = :userId
                union all
                select child.id, parent.depth + 1 from studies child
                join tree_nodes parent on child.parent_study_id = parent.id
                where child.user_id = :userId and parent.depth < 64
            ), selected_nodes(id) as (select distinct id from tree_nodes),
            """.trimIndent()
        } else {
            "with selected_nodes(id) as (select id from studies where id = :studyId and user_id = :userId),"
        }
        val position = if (cursor == null) "" else """
            where occurred_at < :cursorAt
               or (occurred_at = :cursorAt and record_source > :cursorSource)
               or (occurred_at = :cursorAt and record_source = :cursorSource and record_id < :cursorId)
        """.trimIndent()
        var query = client.sql(
            """
            $nodes
            learning_records(record_id, study_id, record_source, occurred_at) as (
                select q.id as record_id, q.study_id, 'QUESTION' as record_source,
                       coalesce(q.answered_at, q.created_at) as occurred_at
                from questions q join selected_nodes n on n.id = q.study_id
                where q.user_id = :userId and q.record_type = 'QUESTION'
                  and q.deleted_at is null and q.skipped_at is null and q.score is not null
                union all
                -- Preserve the v1 source/extension cursor key, never duplicate the canonical row.
                select r.id as record_id, q.study_id, 'VOICE_TUTOR' as record_source, q.created_at
                from questions q join selected_nodes n on n.id = q.study_id
                join voice_study_learning_records r on r.id = q.voice_record_id and r.study_id = q.study_id
                join voice_tutor_sessions s on s.id = r.session_id and s.user_id = r.user_id
                where q.user_id = :userId and r.user_id = :userId
                  and q.record_type = 'VOICE_TUTOR' and q.status = 'completed'
                  and q.deleted_at is null and q.skipped_at is null and s.ended_at is not null
            )
            select record_id, study_id, record_source, occurred_at from learning_records
            $position
            order by occurred_at desc, record_source asc, record_id desc
            limit :pageLimit
            """.trimIndent(),
        ).bind("userId", userId).bind("studyId", studyId).bind("pageLimit", limit.coerceIn(1, 101))
        if (cursor != null) query = query
            .bind("cursorAt", LocalDateTime.ofInstant(cursor.createdAt, ZoneOffset.UTC))
            .bind("cursorSource", cursor.source.name).bind("cursorId", cursor.recordId)
        return query.map { row, _ ->
            StudyLearningRecordKey(
                StudyLearningRecordSource.valueOf(row.get("record_source", String::class.java)!!),
                row.get("record_id", java.lang.Long::class.java)!!.toLong(),
                row.get("study_id", java.lang.Long::class.java)!!.toLong(),
                row.get("occurred_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
            )
        }.all().collectList().awaitSingle()
    }
}
