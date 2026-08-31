package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

/** One small session-owned metadata cache. It never reads or writes questions, answers or quota. */
@Repository
class VoiceTutorStudyContextAdapter(
    private val database: DatabaseClient,
    private val clock: Clock = Clock.systemUTC(),
) : VoiceTutorStudyContextPort {
    @Transactional
    override suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot> {
        val accepted = lockActive(session.userId, session.id) ?: return emptyList()
        val studyId = accepted.studyId ?: return emptyList()
        val parentId = readOwned(session.userId, listOf(studyId)).firstOrNull()?.parentStudyId
        val selected = VoiceTutorStudySnapshot(studyId, parentId, accepted.topic, accepted.difficulty)
        val children = database.sql(
            """
            select id, parent_study_id, topic, difficulty_level
            from studies where user_id = :userId and parent_study_id = :studyId
            order by sort_order, id limit $INITIAL_CHILDREN
            """.trimIndent(),
        ).bind("userId", session.userId).bind("studyId", studyId)
            .map { row, _ -> ownedSnapshot(row) }.all().collectList().awaitSingle()
        append(session.userId, session.id, listOf(selected) + children)
        return list(session.userId, session.id)
    }

    @Transactional
    override suspend fun remember(
        userId: Long,
        sessionId: String,
        studyIds: List<Long>,
    ): List<VoiceTutorStudySnapshot> {
        val ids = studyIds.filter { it > 0 }.distinct().take(MAX_CAPTURE_BATCH)
        if (ids.isEmpty() || lockActive(userId, sessionId) == null) return emptyList()
        append(userId, sessionId, readOwned(userId, ids))
        // Return immutable first-seen levels, even if live study settings changed during the call.
        return list(userId, sessionId).filter { it.studyId in ids }
    }

    override suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> =
        database.sql(
            """
            select snapshot.study_id, snapshot.parent_study_id, snapshot.topic, snapshot.difficulty
            from voice_tutor_study_snapshots snapshot
            join voice_tutor_sessions session on session.id = snapshot.session_id
            where session.user_id = :userId and session.id = :sessionId
            order by snapshot.captured_at, snapshot.study_id limit $MAX_SNAPSHOTS
            """.trimIndent(),
        ).bind("userId", userId).bind("sessionId", sessionId)
            .map { row, _ ->
                VoiceTutorStudySnapshot(
                    studyId = (row.get("study_id") as Number).toLong(),
                    parentStudyId = (row.get("parent_study_id") as? Number)?.toLong(),
                    topic = row.get("topic", String::class.java).orEmpty(),
                    difficulty = (row.get("difficulty") as Number).toInt(),
                )
            }.all().collectList().awaitSingle()

    private suspend fun lockActive(userId: Long, sessionId: String): AcceptedStudy? = database.sql(
        """
        select study_id, topic_snapshot, difficulty_snapshot
        from voice_tutor_sessions
        where id = :sessionId and user_id = :userId and status = 'ACTIVE'
          and ended_at is null and hard_ends_at > :now
        for update
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId).bind("now", utcNow())
        .map { row, _ ->
            AcceptedStudy(
                (row.get("study_id") as? Number)?.toLong(),
                row.get("topic_snapshot", String::class.java).orEmpty(),
                (row.get("difficulty_snapshot") as Number).toInt(),
            )
        }.one().awaitSingleOrNull()

    private suspend fun readOwned(userId: Long, studyIds: List<Long>): List<VoiceTutorStudySnapshot> {
        if (studyIds.isEmpty()) return emptyList()
        return database.sql(
            """
            select id, parent_study_id, topic, difficulty_level
            from studies where user_id = :userId and id in (:studyIds)
            order by sort_order, id limit $MAX_CAPTURE_BATCH
            """.trimIndent(),
        ).bind("userId", userId).bind("studyIds", studyIds)
            .map { row, _ -> ownedSnapshot(row) }.all().collectList().awaitSingle()
    }

    private suspend fun append(userId: Long, sessionId: String, candidates: List<VoiceTutorStudySnapshot>) {
        val existing = list(userId, sessionId)
        val existingIds = existing.mapTo(mutableSetOf()) { it.studyId }
        // All writers hold the same session row lock, so the per-session bound also holds under concurrency.
        val additions = candidates.filter {
            it.studyId > 0 && it.difficulty in 1..10 && it.topic.isNotBlank() && existingIds.add(it.studyId)
        }.take((MAX_SNAPSHOTS - existing.size).coerceAtLeast(0))
        for (snapshot in additions) {
            var query = database.sql(
                """
                insert into voice_tutor_study_snapshots
                    (session_id, study_id, parent_study_id, topic, difficulty, captured_at)
                values (:sessionId, :studyId, :parentId, :topic, :difficulty, :now)
                """.trimIndent(),
            ).bind("sessionId", sessionId).bind("studyId", snapshot.studyId)
                .bind("topic", snapshot.topic.take(255)).bind("difficulty", snapshot.difficulty)
                .bind("now", utcNow())
            query = snapshot.parentStudyId?.let { query.bind("parentId", it) }
                ?: query.bindNull("parentId", java.lang.Long::class.java)
            query.fetch().rowsUpdated().awaitSingle()
        }
    }

    private fun ownedSnapshot(row: Row) = VoiceTutorStudySnapshot(
        studyId = (row.get("id") as Number).toLong(),
        parentStudyId = (row.get("parent_study_id") as? Number)?.toLong(),
        topic = row.get("topic", String::class.java).orEmpty(),
        difficulty = (row.get("difficulty_level") as Number).toInt(),
    )

    private fun utcNow(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private data class AcceptedStudy(val studyId: Long?, val topic: String, val difficulty: Int)

    private companion object {
        const val INITIAL_CHILDREN = 10
        const val MAX_CAPTURE_BATCH = 32
        const val MAX_SNAPSHOTS = 64
    }
}
