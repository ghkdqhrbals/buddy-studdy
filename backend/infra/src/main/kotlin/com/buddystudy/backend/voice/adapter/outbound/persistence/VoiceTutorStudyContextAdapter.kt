package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorStudyRevisionIndex
import com.buddystudy.voice.domain.VoiceTutorStudyRevisionLimits
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
        val studyId = accepted.acceptedStudyId ?: return emptyList()
        val existing = currentView(session.userId, session.id)
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
        return currentView(session.userId, session.id)
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
        val existing = currentView(userId, sessionId)
        // Admit the actual requested nodes first; ancestry can use only the capacity left over.
        val saved = append(sessionId, readOwned(userId, ids), existing, accepted.hardEndsAt)
        captureAncestors(userId, sessionId, ids, saved, accepted.hardEndsAt)
        // Reads never adopt live changes. Only an explicit revise() can advance a lesson's metadata.
        return currentView(userId, sessionId).filter { it.studyId in ids }
    }

    @Transactional
    override suspend fun revise(userId: Long, sessionId: String, studyId: Long): List<VoiceTutorStudySnapshot> {
        require(studyId > 0) { "Voice lesson revision requires a positive study identity." }
        val accepted = checkNotNull(lockActive(userId, sessionId)) { "Voice lesson revision requires an active owned session." }
        val history = list(userId, sessionId)
        // The caller must capture the baseline BEFORE mutating the live node. Capturing a new
        // revision-zero row here would falsely apply post-update metadata to earlier questions.
        check(history.any { it.studyId == studyId && it.revision == 0L }) { "Voice lesson revision baseline is unavailable." }
        val index = VoiceTutorStudyRevisionIndex(history)
        val previous = checkNotNull(index.resolve(studyId, index.currentRevision)) { "Voice lesson revision baseline is ambiguous." }
        val live = checkNotNull(readOwned(userId, listOf(studyId)).singleOrNull()) { "Voice lesson revision study is unavailable." }
        check(live.topic.isNotBlank() && live.difficulty in 1..10 && (live.parentStudyId?.let { it > 0 } != false)) {
            "Voice lesson revision metadata is invalid."
        }
        check(utcNow().isBefore(accepted.hardEndsAt)) { "Voice lesson revision lease expired." }
        if (previous.copy(revision = 0) == live) return index.currentView()
        check(index.currentRevision < MAX_REVISIONS && history.count { it.revision > 0 } < MAX_REVISIONS) {
            "Voice lesson revision limit was reached."
        }
        check(utcNow().isBefore(accepted.hardEndsAt)) { "Voice lesson revision lease expired." }
        val revised = live.copy(revision = index.currentRevision + 1)
        var insert = database.sql(
            """
            insert into voice_tutor_study_revisions
                (session_id, revision, study_id, parent_study_id, topic, difficulty, captured_at)
            values (:sessionId, :revision, :studyId, :parentId, :topic, :difficulty, :now)
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("revision", revised.revision).bind("studyId", revised.studyId)
            .bind("topic", revised.topic).bind("difficulty", revised.difficulty).bind("now", utcNow())
        insert = revised.parentStudyId?.let { insert.bind("parentId", it) }
            ?: insert.bindNull("parentId", java.lang.Long::class.java)
        check(insert.fetch().rowsUpdated().awaitSingle() == 1L) { "Voice lesson revision was not saved." }
        return VoiceTutorStudyRevisionIndex(history + revised).currentView()
    }

    override suspend fun currentRevision(userId: Long, sessionId: String): Long = database.sql(
        """
        select coalesce(max(revision.revision), 0) as current_revision
        from voice_tutor_study_revisions revision
        join voice_tutor_sessions session on session.id = revision.session_id
        where session.user_id = :userId and session.id = :sessionId
        """.trimIndent(),
    ).bind("userId", userId).bind("sessionId", sessionId)
        .map { row, _ -> (row.get("current_revision") as Number).toLong() }.one().awaitSingle()

    override suspend fun list(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> =
        database.sql(
            """
            select snapshot.study_id, snapshot.parent_study_id, snapshot.topic, snapshot.difficulty, snapshot.revision
            from (
                select session_id, study_id, parent_study_id, topic, difficulty, captured_at, 0 as revision
                from voice_tutor_study_snapshots where session_id = :sessionId
                union all
                select session_id, study_id, parent_study_id, topic, difficulty, captured_at, revision
                from voice_tutor_study_revisions where session_id = :sessionId
            ) snapshot
            join voice_tutor_sessions session on session.id = snapshot.session_id
            where session.user_id = :userId and session.id = :sessionId
            order by snapshot.revision, snapshot.captured_at, snapshot.study_id limit $MAX_HISTORY_SNAPSHOTS
            """.trimIndent(),
        ).bind("userId", userId).bind("sessionId", sessionId)
            .map { row, _ ->
                VoiceTutorStudySnapshot(
                    studyId = (row.get("study_id") as Number).toLong(),
                    parentStudyId = (row.get("parent_study_id") as? Number)?.toLong(),
                    topic = row.get("topic", String::class.java).orEmpty(),
                    difficulty = (row.get("difficulty") as Number).toInt(),
                    revision = (row.get("revision") as Number).toLong(),
                )
            }.all().collectList().awaitSingle()

    private suspend fun lockActive(userId: Long, sessionId: String): AcceptedStudy? = database.sql(
        """
        select accepted_study_id, topic_snapshot, difficulty_snapshot, hard_ends_at
        from voice_tutor_sessions
        where id = :sessionId and user_id = :userId and status = 'ACTIVE'
          and ended_at is null and hard_ends_at > :now
        for update
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId).bind("now", utcNow())
        .map { row, _ ->
            AcceptedStudy(
                (row.get("accepted_study_id") as? Number)?.toLong(),
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

    private suspend fun currentView(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> =
        VoiceTutorStudyRevisionIndex(list(userId, sessionId)).currentView()

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
            // Captured version edges win over unadopted live reparenting. Missing/foreign parents end that path;
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
        val acceptedStudyId: Long?,
        val topic: String,
        val difficulty: Int,
        val hardEndsAt: LocalDateTime,
    )

    private companion object {
        const val INITIAL_CHILDREN = 10
        const val MAX_CAPTURE_BATCH = 32
        const val MAX_SNAPSHOTS = VoiceTutorStudyRevisionLimits.MAX_BASE_SNAPSHOTS
        const val MAX_REVISIONS = VoiceTutorStudyRevisionLimits.MAX_REVISIONS
        const val MAX_HISTORY_SNAPSHOTS = VoiceTutorStudyRevisionLimits.MAX_HISTORY_SNAPSHOTS
        const val MAX_PARENT_EDGES = 32
    }
}
