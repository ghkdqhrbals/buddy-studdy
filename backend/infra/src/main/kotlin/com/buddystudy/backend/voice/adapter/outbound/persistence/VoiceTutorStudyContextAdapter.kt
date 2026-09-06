package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorStudyContextPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorFocusCommitAuthority
import com.buddystudy.backend.voice.application.model.VoiceTutorFocusAuthorization
import com.buddystudy.backend.voice.application.model.VoiceTutorInitialStudyMutationSnapshot
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorLessonFocusIndex
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
) : VoiceTutorStudyContextPort, VoiceTutorLessonFocusPort {
    @Transactional(readOnly = true)
    override suspend fun initialMutationSnapshot(
        userId: Long,
        sessionId: String,
        maxCandidates: Int,
    ): VoiceTutorInitialStudyMutationSnapshot? {
        if (userId <= 0 || sessionId.isBlank() || sessionId.length > 191 ||
            maxCandidates !in 1..VoiceTutorInitialStudyMutationSnapshot.MAX_CANDIDATES
        ) return null
        val rows = database.sql(
            """
            select study.id, study.parent_study_id, study.topic, study.difficulty_level
            from studies study
            where study.user_id = :userId
              and exists (
                select 1 from voice_tutor_sessions session
                where session.id = :sessionId and session.user_id = :userId and session.status = 'ACTIVE'
              )
              and not exists (
                select 1 from voice_tutor_transcript_turns turn
                where turn.session_id = :sessionId and turn.role = 'USER'
              )
            order by study.id
            limit ${maxCandidates + 1}
            """.trimIndent(),
        ).bind("userId", userId).bind("sessionId", sessionId)
            .map { row, _ -> ownedSnapshot(row) }.all().collectList().awaitSingle()
        if (rows.isEmpty() || rows.size > maxCandidates || rows.any { !validMetadata(it) }) return null
        return VoiceTutorInitialStudyMutationSnapshot(rows.map { snapshot ->
            VoiceTutorStudyTargetCandidate(
                studyId = snapshot.studyId,
                parentStudyId = snapshot.parentStudyId,
                topic = snapshot.topic,
                difficulty = snapshot.difficulty,
            )
        }).takeIf { it.isValid(maxCandidates) }
    }

    @Transactional
    override suspend fun prepare(session: VoiceTutorSession): List<VoiceTutorStudySnapshot> {
        val accepted = lockActive(session.userId, session.id) ?: return emptyList()
        val focus = VoiceTutorLessonFocusIndex(history(session.userId, session.id), accepted.acceptedStudyId).current
            ?: return emptyList()
        val studyId = focus.studyId
        val existing = currentView(session.userId, session.id)
        val selected = existing.firstOrNull { it.studyId == studyId }
            ?: readOwned(session.userId, listOf(studyId)).firstOrNull()?.takeIf {
                // Only an original selection can use the creation snapshot. Explicit focuses
                // have an atomic revision entry and must never recover by mixing current text
                // with a different accepted identity.
                focus.revision == 0L && studyId == accepted.acceptedStudyId &&
                    accepted.topic.isNotBlank() && accepted.difficulty in 1..10
            }?.let {
                VoiceTutorStudySnapshot(studyId, it.parentStudyId, accepted.topic, accepted.difficulty)
            }
            // A missing/foreign live node is not evidence that the selected node is a root.
            ?: return existing
        if (accepted.studyId != studyId) return existing
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
    override suspend fun focusFromRealtimeModel(
        userId: Long,
        sessionId: String,
        studyId: Long,
        learnerTurnId: Long,
        expectedCurrentRevision: Long,
        expectedCandidate: VoiceTutorStudyTargetCandidate,
        commitAuthority: VoiceTutorFocusCommitAuthority,
        expectedParentStudyId: Long?,
    ): VoiceTutorLessonFocusSelection? = focusLocked(
        userId, sessionId, studyId, expectedParentStudyId, learnerTurnId,
        expectedCurrentRevision, null, expectedCandidate, commitAuthority, null,
        realtimeModelChoice = true,
    )

    @Transactional
    override suspend fun focus(
        userId: Long,
        sessionId: String,
        studyId: Long,
        learnerTurnId: Long?,
        expectedCurrentRevision: Long?,
        authorization: VoiceTutorFocusAuthorization?,
        expectedCandidate: VoiceTutorStudyTargetCandidate?,
        commitAuthority: VoiceTutorFocusCommitAuthority?,
        expectedTraversal: VoiceTutorStudyTargetTraversal?,
    ): VoiceTutorLessonFocusSelection? =
        focusLocked(
            userId,
            sessionId,
            studyId,
            expectedParentStudyId = null,
            learnerTurnId = learnerTurnId,
            expectedCurrentRevision = expectedCurrentRevision,
            authorization = authorization,
            expectedCandidate = expectedCandidate,
            commitAuthority = commitAuthority,
            expectedTraversal = expectedTraversal,
        )

    @Transactional
    override suspend fun advance(
        userId: Long,
        sessionId: String,
        currentStudyId: Long,
        childStudyId: Long,
        learnerTurnId: Long,
        expectedCurrentRevision: Long?,
        authorization: VoiceTutorFocusAuthorization?,
        expectedCandidate: VoiceTutorStudyTargetCandidate?,
        commitAuthority: VoiceTutorFocusCommitAuthority?,
        expectedTraversal: VoiceTutorStudyTargetTraversal?,
    ): VoiceTutorLessonFocusSelection? =
        focusLocked(
            userId,
            sessionId,
            childStudyId,
            expectedParentStudyId = currentStudyId,
            learnerTurnId = learnerTurnId,
            expectedCurrentRevision = expectedCurrentRevision,
            authorization = authorization,
            expectedCandidate = expectedCandidate,
            commitAuthority = commitAuthority,
            expectedTraversal = expectedTraversal,
        )

    private suspend fun focusLocked(
        userId: Long,
        sessionId: String,
        studyId: Long,
        expectedParentStudyId: Long?,
        learnerTurnId: Long?,
        expectedCurrentRevision: Long?,
        authorization: VoiceTutorFocusAuthorization?,
        expectedCandidate: VoiceTutorStudyTargetCandidate?,
        commitAuthority: VoiceTutorFocusCommitAuthority?,
        expectedTraversal: VoiceTutorStudyTargetTraversal?,
        realtimeModelChoice: Boolean = false,
    ): VoiceTutorLessonFocusSelection? {
        if (studyId <= 0 || learnerTurnId?.let { it <= 0 } == true ||
            (learnerTurnId != null && ((!realtimeModelChoice && authorization == null) || commitAuthority == null)) ||
            (realtimeModelChoice && (learnerTurnId == null || expectedCandidate == null || expectedCurrentRevision == null)) ||
            commitAuthority?.let {
                it.authSessionId <= 0 || it.deviceId.isBlank() || it.deviceId.length > 191 ||
                    it.providerCallId.isBlank() || it.providerCallId.length > 191
            } == true ||
            expectedCandidate?.let {
                it.studyId != studyId || it.studyId <= 0 ||
                    it.parentStudyId?.let { parent -> parent <= 0 } == true ||
                    it.topic.isBlank() || it.topic.length > 255 ||
                    it.difficulty?.let { difficulty -> difficulty !in 1..10 } == true
            } == true || expectedTraversal?.let { traversal ->
                expectedCandidate?.let(traversal::isValidFor) != true
            } == true
        ) return null
        val lockedCommit = when {
            commitAuthority != null -> lockAuthorizedActiveCall(userId, sessionId, commitAuthority)
            learnerTurnId == null -> lockActive(userId, sessionId)?.let(::LockedFocusCommit)
            else -> null
        } ?: return null
        val accepted = lockedCommit.accepted
        val metadataHistory = list(userId, sessionId)
        val revisions = VoiceTutorStudyRevisionIndex(metadataHistory)
        if (expectedCurrentRevision != null && revisions.currentRevision != expectedCurrentRevision) return null
        if (learnerTurnId != null && !isLatestPersistedLearnerTurn(sessionId, learnerTurnId, realtimeModelChoice)) return null
        val existing = revisions.currentView()
        val previous = VoiceTutorLessonFocusIndex(history(userId, sessionId), accepted.acceptedStudyId)
            .at(revisions.currentRevision)
        val live = readOwned(userId, listOf(studyId)).singleOrNull()?.takeIf(::validMetadata) ?: return null
        if (expectedCandidate != null && (
                live.parentStudyId != expectedCandidate.parentStudyId ||
                    live.topic != expectedCandidate.topic ||
                    expectedCandidate.difficulty?.let { live.difficulty != it } == true
            )
        ) return null
        if (expectedParentStudyId != null && (
                accepted.studyId != expectedParentStudyId || previous?.studyId != expectedParentStudyId ||
                    live.parentStudyId != expectedParentStudyId || learnerTurnId == null
            )
        ) return null
        val legacyAcceptedSnapshot =
            previous?.revision == 0L && accepted.acceptedStudyId == studyId && accepted.studyId == studyId &&
            accepted.topic.isNotBlank() && accepted.difficulty in 1..10
        val selected = existing.firstOrNull { it.studyId == studyId } ?: if (
            legacyAcceptedSnapshot
        ) live.copy(topic = accepted.topic, difficulty = accepted.difficulty) else live
        // Verify the full frozen path against actual ownership before writing any capture. An
        // unresolved/missing/foreign parent or cycle does not turn this candidate into a root.
        val path = verifiedFocusPath(userId, selected, existing, live) ?: return null
        if (!lockAndRecheckFocusPath(
                userId, path, existing,
                immutableMetadataIds = if (legacyAcceptedSnapshot) setOf(studyId) else emptySet(),
                expectedCandidate = expectedCandidate,
            )
        ) return null
        if (expectedTraversal != null && expectedCandidate != null &&
            !lockAndRecheckTargetTraversal(userId, expectedCandidate, expectedTraversal)
        ) return null
        val existingIds = existing.mapTo(mutableSetOf()) { it.studyId }
        if (existing.size + path.count { it.studyId !in existingIds } > MAX_SNAPSHOTS) return null
        val sameFocus = previous != null && previous.studyId == studyId && previous.revision > 0 && accepted.studyId == studyId
        if (!sameFocus && learnerTurnId != null && (
                if (realtimeModelChoice) alreadyFocusedFromLearnerTurn(sessionId, learnerTurnId)
                else learnerTurnId <= latestFocusLearnerTurn(sessionId)
            )) return null
        if (!sameFocus && (revisions.currentRevision >= MAX_REVISIONS || metadataHistory.count { it.revision > 0 } >= MAX_REVISIONS)) {
            return null
        }
        if (!utcNow().isBefore(accepted.hardEndsAt)) return null
        // Natural expiry needs no row update, so validate the timestamp read from the locked exact
        // authorization row again at the one-shot consumption boundary.
        if (lockedCommit.authSessionExpiresAt?.let { !utcNow().isBefore(it) } == true) return null
        if (learnerTurnId != null && !realtimeModelChoice && authorization?.consume() != true) return null
        val captured = append(sessionId, path, existing, accepted.hardEndsAt)
        check(path.all { candidate -> captured.any { it.studyId == candidate.studyId } }) {
            "Voice lesson focus capture expired."
        }
        check(utcNow().isBefore(accepted.hardEndsAt)) { "Voice lesson focus lease expired." }
        val frozen = captured.single { it.studyId == studyId }
        if (sameFocus) {
            return VoiceTutorLessonFocusSelection(requireNotNull(previous), frozen, revisions.currentRevision)
        }
        val revision = revisions.currentRevision + 1
        val revised = frozen.copy(revision = revision)
        insertRevision(sessionId, revised)
        var focusInsert = database.sql(
            """
            insert into voice_tutor_lesson_focuses
                (session_id, revision, study_id, learner_turn_id, captured_at)
            values (:sessionId, :revision, :studyId, :learnerTurnId, :now)
            """.trimIndent(),
        ).bind("sessionId", sessionId).bind("revision", revision).bind("studyId", studyId)
            .bind("now", utcNow())
        focusInsert = learnerTurnId?.let { focusInsert.bind("learnerTurnId", it) }
            ?: focusInsert.bindNull("learnerTurnId", java.lang.Long::class.java)
        focusInsert.fetch().rowsUpdated().awaitSingle()
        check(updateCurrentMetadata(userId, sessionId, revised, accepted.hardEndsAt) == 1L) {
            "Voice lesson focus is no longer available."
        }
        return VoiceTutorLessonFocusSelection(VoiceTutorLessonFocus(studyId, revision), revised)
    }

    private suspend fun isLatestPersistedLearnerTurn(sessionId: String, learnerTurnId: Long, allowTutorPreamble: Boolean = false): Boolean = database.sql(
        """
        select id, role
        from voice_tutor_transcript_turns
        where session_id = :sessionId
          ${if (allowTutorPreamble) "and role = 'USER'" else ""}
        order by sequence_number desc, id desc
        limit 1
        """.trimIndent(),
    ).bind("sessionId", sessionId)
        .map { row, _ ->
            (row.get("id") as Number).toLong() == learnerTurnId &&
                row.get("role", String::class.java) == "USER"
        }.one().awaitSingleOrNull() == true

    private suspend fun alreadyFocusedFromLearnerTurn(sessionId: String, learnerTurnId: Long): Boolean = database.sql(
        """
        select learner_turn_id from voice_tutor_lesson_focuses
        where session_id = :sessionId and learner_turn_id = :learnerTurnId
        limit 1
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("learnerTurnId", learnerTurnId)
        .map { _, _ -> true }.one().awaitSingleOrNull() == true

    private suspend fun latestFocusLearnerTurn(sessionId: String): Long = database.sql(
        """
        select coalesce(learner_turn_id, 0) as learner_turn_id
        from voice_tutor_lesson_focuses
        where session_id = :sessionId
        order by revision desc
        limit 1
        """.trimIndent(),
    ).bind("sessionId", sessionId)
        .map { row, _ -> (row.get("learner_turn_id") as Number).toLong() }
        .one().awaitSingleOrNull() ?: 0L

    override suspend fun history(userId: Long, sessionId: String): List<VoiceTutorLessonFocus> = database.sql(
        """
        select focus.study_id, focus.revision
        from voice_tutor_lesson_focuses focus
        join voice_tutor_sessions session on session.id = focus.session_id
        where session.id = :sessionId and session.user_id = :userId
        order by focus.revision limit ${MAX_REVISIONS + 1}
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId)
        .map { row, _ -> VoiceTutorLessonFocus((row.get("study_id") as Number).toLong(), (row.get("revision") as Number).toLong()) }
        .all().collectList().awaitSingle()

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
        insertRevision(sessionId, revised)
        if (accepted.studyId == studyId) {
            check(updateCurrentMetadata(userId, sessionId, revised, accepted.hardEndsAt) == 1L) {
                "Voice lesson revision is no longer available."
            }
        }
        return VoiceTutorStudyRevisionIndex(history + revised).currentView()
    }

    private suspend fun insertRevision(sessionId: String, revised: VoiceTutorStudySnapshot) {
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

    /**
     * Lock the exact device authorization before the call row. Logout/revocation updates the same
     * user_devices row, so it linearizes either before this validation or after the focus commit.
     */
    private suspend fun lockAuthorizedActiveCall(
        userId: Long,
        sessionId: String,
        authority: VoiceTutorFocusCommitAuthority,
    ): LockedFocusCommit? {
        val authorization = database.sql(
            """
            select session_expires_at
            from user_devices
            where id = :authSessionId and user_id = :userId and device_id = :deviceId
              and logged_out_at is null and revoked_at is null
              and (session_expires_at is null or session_expires_at > :now)
            for update
            """.trimIndent(),
        ).bind("authSessionId", authority.authSessionId).bind("userId", userId)
            .bind("deviceId", authority.deviceId).bind("now", utcNow())
            .map { row, _ ->
                LockedAuthorization(row.get("session_expires_at", LocalDateTime::class.java))
            }.one().awaitSingleOrNull() ?: return null
        val accepted = lockActive(userId, sessionId, authority.providerCallId) ?: return null
        return LockedFocusCommit(accepted, authorization.sessionExpiresAt)
    }

    private suspend fun lockActive(
        userId: Long,
        sessionId: String,
        providerCallId: String? = null,
    ): AcceptedStudy? {
        val providerPredicate = if (providerCallId == null) "" else "and provider_session_id = :providerCallId"
        var query = database.sql(
        """
        select study_id, accepted_study_id, topic_snapshot, difficulty_snapshot, hard_ends_at
        from voice_tutor_sessions
        where id = :sessionId and user_id = :userId and status = 'ACTIVE'
          and ended_at is null and hard_ends_at > :now
          $providerPredicate
        for update
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId).bind("now", utcNow())
        if (providerCallId != null) query = query.bind("providerCallId", providerCallId)
        return query.map { row, _ ->
            AcceptedStudy(
                (row.get("study_id") as? Number)?.toLong(),
                (row.get("accepted_study_id") as? Number)?.toLong(),
                row.get("topic_snapshot", String::class.java).orEmpty(),
                (row.get("difficulty_snapshot") as Number).toInt(),
                requireNotNull(row.get("hard_ends_at", LocalDateTime::class.java)),
            )
        }.one().awaitSingleOrNull()
    }

    private suspend fun verifiedFocusPath(
        userId: Long,
        selected: VoiceTutorStudySnapshot,
        existing: List<VoiceTutorStudySnapshot>,
        liveSelected: VoiceTutorStudySnapshot,
    ): List<VoiceTutorStudySnapshot>? {
        val byId = existing.associateBy { it.studyId }
        val path = mutableListOf<VoiceTutorStudySnapshot>()
        val seen = mutableSetOf<Long>()
        var candidate = selected
        repeat(MAX_PARENT_EDGES + 1) {
            if (!seen.add(candidate.studyId) || !validMetadata(candidate)) return null
            val live = if (candidate.studyId == selected.studyId) liveSelected else {
                readOwned(userId, listOf(candidate.studyId)).singleOrNull() ?: return null
            }
            if (!validMetadata(live)) return null
            path += candidate
            val parentId = candidate.parentStudyId ?: return path
            candidate = byId[parentId] ?: readOwned(userId, listOf(parentId)).singleOrNull() ?: return null
        }
        return null
    }

    /**
     * The exploratory reads above discover a bounded path but cannot authorize a write. Lock every
     * live row in stable ID order, then recheck every edge before appending the focus revision.
     * Concurrent reparent/update/delete therefore linearizes either before this check or after the
     * focus transaction, never between the verified direct edge and the focus write.
     */
    private suspend fun lockAndRecheckFocusPath(
        userId: Long,
        path: List<VoiceTutorStudySnapshot>,
        existing: List<VoiceTutorStudySnapshot>,
        immutableMetadataIds: Set<Long> = emptySet(),
        expectedCandidate: VoiceTutorStudyTargetCandidate? = null,
    ): Boolean {
        val ids = path.map { it.studyId }.distinct().sorted()
        if (ids.size != path.size) return false
        val locked = readOwned(userId, ids, forUpdate = true)
        if (locked.size != ids.size) return false
        val lockedById = locked.associateBy { it.studyId }
        val frozenIds = existing.mapTo(mutableSetOf()) { it.studyId }.apply { addAll(immutableMetadataIds) }
        return path.all { candidate ->
            val live = lockedById[candidate.studyId] ?: return@all false
            val offeredMetadataStillExact = expectedCandidate?.takeIf { it.studyId == candidate.studyId }?.let {
                live.parentStudyId == it.parentStudyId && live.topic == it.topic &&
                    it.difficulty?.let { expectedDifficulty -> live.difficulty == expectedDifficulty } != false
            } ?: true
            validMetadata(live) && offeredMetadataStillExact && live.parentStudyId == candidate.parentStudyId &&
                (candidate.studyId in frozenIds ||
                    (live.topic == candidate.topic && live.difficulty == candidate.difficulty))
        }
    }

    /**
     * Recheck the exact topology that justified automatic descent. The indexed child-range reads
     * use `FOR UPDATE`: on MySQL/InnoDB they also hold the matching next-key gap until commit, so a
     * sibling insertion or endpoint-child insertion linearizes outside this focus decision.
     */
    private suspend fun lockAndRecheckTargetTraversal(
        userId: Long,
        candidate: VoiceTutorStudyTargetCandidate,
        traversal: VoiceTutorStudyTargetTraversal,
    ): Boolean {
        if (!traversal.isValidFor(candidate, MAX_PARENT_EDGES)) return false
        val expectedSingleChildren = traversal.singleChildEdges.associate {
            it.parentStudyId to it.childStudyId
        }
        if (expectedSingleChildren.size != traversal.singleChildEdges.size) return false
        val parents = (expectedSingleChildren.keys + listOfNotNull(traversal.terminalLeafStudyId))
            .distinct().sorted()
        for (parentStudyId in parents) {
            val children = lockDirectChildren(userId, parentStudyId)
            val expectedChild = expectedSingleChildren[parentStudyId]
            if (expectedChild != null) {
                if (children != listOf(expectedChild)) return false
            } else if (children.isNotEmpty()) {
                return false
            }
        }
        return true
    }

    private suspend fun lockDirectChildren(userId: Long, parentStudyId: Long): List<Long> = database.sql(
        """
        select id
        from studies
        where user_id = :userId and parent_study_id = :parentStudyId
        order by sort_order, id
        limit 2 for update
        """.trimIndent(),
    ).bind("userId", userId).bind("parentStudyId", parentStudyId)
        .map { row, _ -> (row.get("id") as Number).toLong() }
        .all().collectList().awaitSingle()

    private fun validMetadata(snapshot: VoiceTutorStudySnapshot): Boolean =
        snapshot.studyId > 0 && snapshot.topic.isNotBlank() && snapshot.difficulty in 1..10 &&
            snapshot.parentStudyId.let { it == null || it > 0 }

    private suspend fun updateCurrentMetadata(
        userId: Long,
        sessionId: String,
        snapshot: VoiceTutorStudySnapshot,
        hardEndsAt: LocalDateTime,
    ): Long {
        val now = utcNow()
        check(now.isBefore(hardEndsAt)) { "Voice lesson focus lease expired." }
        return database.sql(
            """
            update voice_tutor_sessions
            set study_id = :studyId, topic_snapshot = :topic, difficulty_snapshot = :difficulty, updated_at = :now
            where id = :sessionId and user_id = :userId and status = 'ACTIVE'
              and ended_at is null and hard_ends_at > :now
              and exists (select 1 from studies where id = :studyId and user_id = :userId)
            """.trimIndent(),
        ).bind("studyId", snapshot.studyId).bind("topic", snapshot.topic).bind("difficulty", snapshot.difficulty)
            .bind("now", now).bind("sessionId", sessionId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun readOwned(
        userId: Long,
        studyIds: List<Long>,
        forUpdate: Boolean = false,
    ): List<VoiceTutorStudySnapshot> {
        if (studyIds.isEmpty()) return emptyList()
        return database.sql(
            """
            select id, parent_study_id, topic, difficulty_level
            from studies where user_id = :userId and id in (:studyIds)
            order by ${if (forUpdate) "id" else "sort_order, id"}
            limit $MAX_READ_BATCH${if (forUpdate) " for update" else ""}
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
        val studyId: Long?,
        val acceptedStudyId: Long?,
        val topic: String,
        val difficulty: Int,
        val hardEndsAt: LocalDateTime,
    )

    private data class LockedAuthorization(val sessionExpiresAt: LocalDateTime?)

    private data class LockedFocusCommit(
        val accepted: AcceptedStudy,
        val authSessionExpiresAt: LocalDateTime? = null,
    )

    private companion object {
        const val INITIAL_CHILDREN = 10
        const val MAX_PARENT_EDGES = 32
        const val MAX_CAPTURE_BATCH = 32
        // A valid path may contain MAX_PARENT_EDGES + 1 nodes. Keep only the
        // stable-ID verification read wide enough to lock the deepest path;
        // ordinary snapshot capture retains its established 32-node batch bound.
        const val MAX_READ_BATCH = MAX_PARENT_EDGES + 1
        const val MAX_SNAPSHOTS = VoiceTutorStudyRevisionLimits.MAX_BASE_SNAPSHOTS
        const val MAX_REVISIONS = VoiceTutorStudyRevisionLimits.MAX_REVISIONS
        const val MAX_HISTORY_SNAPSHOTS = VoiceTutorStudyRevisionLimits.MAX_HISTORY_SNAPSHOTS
    }
}
