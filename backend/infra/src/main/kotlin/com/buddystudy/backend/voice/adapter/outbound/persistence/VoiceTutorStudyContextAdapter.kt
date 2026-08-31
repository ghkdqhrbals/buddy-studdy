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
        val existing = list(session.userId, session.id)
        val selected = existing.firstOrNull { it.studyId == studyId }
            ?: readOwned(session.userId, listOf(studyId)).firstOrNull()?.let {
                VoiceTutorStudySnapshot(studyId, it.parentStudyId, accepted.topic, accepted.difficulty)
            }
            // A missing/foreign live node is not evidence that the selected node is a root.
            ?: return existing
        val children = database.sql(
            """
            select id, parent_study_id, topic, difficulty_level
            from studies where user_id = :userId and parent_study_id = :studyId
            order by sort_order, id limit $INITIAL_CHILDREN
            """.trimIndent(),
        ).bind("userId", session.userId).bind("studyId", studyId)
            .map { row, _ -> ownedSnapshot(row) }.all().collectList().awaitSingle()
        val saved = append(session.id, listOf(selected) + children, existing, accepted.hardEndsAt)
        captureAncestors(
            session.userId, session.id, listOf(selected.studyId) + children.map { it.studyId }, saved, accepted.hardEndsAt,
        )
        return list(session.userId, session.id)
    }

    @Transactional
    override suspend fun remember(
        userId: Long,
        sessionId: String,
        studyIds: List<Long>,
    ): List<VoiceTutorStudySnapshot> {
        val ids = studyIds.filter { it > 0 }.distinct().take(MAX_CAPTURE_BATCH)
        if (ids.isEmpty()) return emptyList()
        val accepted = lockActive(userId, sessionId) ?: return emptyList()
        val existing = list(userId, sessionId)
        // Admit the actual requested nodes first; ancestry can use only the capacity left over.
        val saved = append(sessionId, readOwned(userId, ids), existing, accepted.hardEndsAt)
        captureAncestors(userId, sessionId, ids, saved, accepted.hardEndsAt)
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
        select study_id, topic_snapshot, difficulty_snapshot, hard_ends_at
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
                requireNotNull(row.get("hard_ends_at", LocalDateTime::class.java)),
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

    private suspend fun captureAncestors(
        userId: Long,
        sessionId: String,
        focusStudyIds: List<Long>,
        initial: List<VoiceTutorStudySnapshot>,
        hardEndsAt: LocalDateTime,
    ) {
        var saved = initial
        var byId = saved.associateBy { it.studyId }
        var frontier = focusStudyIds.mapNotNull { byId[it] }
        val visited = focusStudyIds.toMutableSet()
        var remainingReads = MAX_SNAPSHOTS
        repeat(MAX_PARENT_EDGES) {
            if (saved.size >= MAX_SNAPSHOTS || !utcNow().isBefore(hardEndsAt)) return
            val parentIds = frontier.mapNotNull { it.parentStudyId }.filter { it > 0 && visited.add(it) }
            if (parentIds.isEmpty()) return
            val missing = parentIds.filter { it !in byId }
                .take(minOf(MAX_CAPTURE_BATCH, MAX_SNAPSHOTS - saved.size, remainingReads))
            if (missing.isNotEmpty()) {
                remainingReads -= missing.size
                saved = append(sessionId, readOwned(userId, missing), saved, hardEndsAt)
                byId = saved.associateBy { it.studyId }
            }
            // Frozen edges win even after reparenting. Missing/foreign parents end that path;
            // no sibling discovery, name matching or invented root fills the gap.
            frontier = parentIds.mapNotNull { byId[it] }
        }
    }

    private suspend fun append(
        sessionId: String,
        candidates: List<VoiceTutorStudySnapshot>,
        existing: List<VoiceTutorStudySnapshot>,
        hardEndsAt: LocalDateTime,
    ): List<VoiceTutorStudySnapshot> {
        val existingIds = existing.mapTo(mutableSetOf()) { it.studyId }
        // All writers hold the same session row lock, so the per-session bound also holds under concurrency.
        val additions = candidates.filter {
            it.studyId > 0 && it.difficulty in 1..10 && it.topic.isNotBlank() && existingIds.add(it.studyId)
        }.take((MAX_SNAPSHOTS - existing.size).coerceAtLeast(0))
        val captured = mutableListOf<VoiceTutorStudySnapshot>()
        for (snapshot in additions) {
            if (!utcNow().isBefore(hardEndsAt)) break
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
            captured += snapshot.copy(topic = snapshot.topic.take(255))
        }
        return existing + captured
    }

    private fun ownedSnapshot(row: Row) = VoiceTutorStudySnapshot(
        studyId = (row.get("id") as Number).toLong(),
        parentStudyId = (row.get("parent_study_id") as? Number)?.toLong(),
        topic = row.get("topic", String::class.java).orEmpty(),
        difficulty = (row.get("difficulty_level") as Number).toInt(),
    )

    private fun utcNow(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private data class AcceptedStudy(
        val studyId: Long?,
        val topic: String,
        val difficulty: Int,
        val hardEndsAt: LocalDateTime,
    )

    private companion object {
        const val INITIAL_CHILDREN = 10
        const val MAX_CAPTURE_BATCH = 32
        const val MAX_SNAPSHOTS = 64
        const val MAX_PARENT_EDGES = 32
    }
}
