package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentTranslationEventPort
import com.buddystudy.backend.localization.application.port.VoiceStudyLearningLocalizationPort
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionSearchProjectionManager
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.VoiceStudyLearningRecordQueryPort
import com.buddystudy.backend.voice.adapter.outbound.VoiceTutorExplorationJsonCodec
import com.buddystudy.backend.voice.application.port.outbound.VoiceStudyLearningRecordAppendPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceStudyLearningRecordProjectionCandidate
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyRecordType
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorStudyRevisionLimits
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class VoiceStudyLearningRecordPersistenceAdapter(
    private val database: DatabaseClient,
    private val translations: ContentTranslationEventPort,
    private val languageDetector: ContentLanguageDetectionPort,
    private val questions: QuestionPort,
    private val searchProjection: QuestionSearchProjectionManager,
) : VoiceStudyLearningRecordAppendPort, VoiceStudyLearningRecordQueryPort, VoiceStudyLearningLocalizationPort {
    private val mapper = JsonMapperProvider.mapper

    @Transactional
    override suspend fun appendCompletedSession(
        userId: Long,
        sessionId: String,
        explorations: List<VoiceTutorExploration>,
        now: Instant,
    ) {
        // Same session-first lock order as result completion; retries never overwrite existing evidence.
        val header = database.sql(
            "select id, user_id, accepted_study_id, language, records_default_public from voice_tutor_sessions where id = :sessionId and user_id = :userId and ended_at is not null for update",
        ).bind("sessionId", sessionId).bind("userId", userId).map { row, _ ->
            CompletedSessionHeader(
                row.string("id"), (row.get("accepted_study_id") as? Number)?.toLong(), row.string("language"),
                row.get("records_default_public", Boolean::class.javaObjectType)!!,
            )
        }.one().awaitSingleOrNull() ?: return
        val result = database.sql(
            "select learning_records_projected_at from voice_tutor_results where session_id = :sessionId and status = 'COMPLETED'",
        ).bind("sessionId", sessionId).map { row, _ -> row.get("learning_records_projected_at") != null }
            .one().awaitSingleOrNull() ?: return
        if (result) return
        val activeOwner = database.sql("select id from users where id = :userId and status = 'ACTIVE'")
            .bind("userId", userId).map { row, _ -> (row.get("id") as Number).toLong() }
            .one().awaitSingleOrNull() ?: return
        check(activeOwner == userId)
        val snapshots = snapshots(userId, sessionId)
        val ownedStudyIds = if (snapshots.isEmpty()) emptySet() else {
            database.sql("select id from studies where user_id = :userId and id in (:ids)")
                .bind("userId", userId).bind("ids", snapshots.map { it.studyId }.distinct())
                .map { row, _ -> (row.get("id") as Number).toLong() }.all().collectList().awaitSingle().toSet()
        }
        val records = VoiceStudyLearningRecordProjector.project(
            userId = userId, sessionId = header.id, acceptedStudyId = header.acceptedStudyId, language = header.language,
            explorations = explorations, transcript = transcript(sessionId), snapshots = snapshots,
            ownedStudyIds = ownedStudyIds, detectLanguage = languageDetector::detect,
            focuses = focuses(userId, sessionId),
        )
        for (record in records) {
            val extensionId = insertExtension(record, now)
            // A replay, including a user-deleted canonical row, must never rewrite or resurrect it.
            val existing = database.sql("select id from questions where voice_record_id = :voiceId")
                .bind("voiceId", extensionId).map { row, _ -> row.long("id") }.one().awaitSingleOrNull()
            if (existing == null) {
                questions.save(record.canonicalQuestion(extensionId, header.recordsDefaultPublic, now))
            }
            val persisted = database.sql(
                "$OWNED_RECORD_SELECT where r.session_id = :sessionId and r.question_turn_id = :turnId and r.user_id = :userId",
            ).bind("sessionId", sessionId).bind("turnId", record.questionTurnId).bind("userId", userId)
                .map { row, _ -> row.record() }.one().awaitSingleOrNull() ?: continue
            // Same SQL transaction as record/result; normal outbox recovery publishes after commit.
            for (language in QuestionLanguage.supported.sorted()) request(persisted, language, now)
        }
        database.sql("update voice_tutor_results set learning_records_projected_at = :now where session_id = :sessionId and status = 'COMPLETED' and learning_records_projected_at is null")
            .bind("now", now.utc()).bind("sessionId", sessionId).fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun completedCandidates(limit: Int): List<VoiceStudyLearningRecordProjectionCandidate> = database.sql(
        """
        select s.user_id, s.id, r.explorations_json
        from voice_tutor_results r join voice_tutor_sessions s on s.id = r.session_id
        join users u on u.id = s.user_id and u.status = 'ACTIVE'
        where r.status = 'COMPLETED' and r.explorations_json is not null
          and r.learning_records_projected_at is null and s.ended_at is not null
        order by r.updated_at, s.id limit :limit
        """.trimIndent(),
    ).bind("limit", limit.coerceIn(1, 10)).map { row, _ ->
        VoiceStudyLearningRecordProjectionCandidate(
            (row.get("user_id") as Number).toLong(), row.get("id", String::class.java)!!,
            VoiceTutorExplorationJsonCodec.decode(row.get("explorations_json", String::class.java)),
        )
    }.all().collectList().awaitSingle()

    override suspend fun findOwned(userId: Long, recordId: Long): VoiceStudyLearningRecord? = database.sql(
        "$OWNED_RECORD_SELECT where r.id = :recordId and r.user_id = :userId and s.user_id = :userId",
    ).bind("recordId", recordId).bind("userId", userId)
        .map { row, _ -> row.record() }.one().awaitSingleOrNull()

    override suspend fun findAllOwned(userId: Long, recordIds: Collection<Long>): List<VoiceStudyLearningRecord> {
        if (recordIds.isEmpty()) return emptyList()
        require(recordIds.size <= 100)
        return database.sql("$OWNED_RECORD_SELECT where r.id in (:ids) and r.user_id = :userId and s.user_id = :userId")
            .bind("ids", recordIds).bind("userId", userId).map { row, _ -> row.record() }
            .all().collectList().awaitSingle()
    }

    override suspend fun content(recordId: Long): VoiceStudyLearningRecord? = database.sql(
        "$OWNED_RECORD_SELECT join users u on u.id = s.user_id and u.status = 'ACTIVE' where r.id = :recordId and r.user_id = s.user_id",
    ).bind("recordId", recordId).map { row, _ -> row.record() }.one().awaitSingleOrNull()

    override suspend fun snapshot(recordId: Long, targetLanguage: String): TextLocalizationSnapshot? = database.sql(
        """
        select l.* from voice_study_learning_localizations l
        join voice_study_learning_records r on r.id = l.record_id
        join questions q on q.voice_record_id = r.id and q.user_id = r.user_id
        join voice_tutor_sessions s on s.id = r.session_id and s.user_id = r.user_id
        where l.record_id = :recordId and l.target_language = :language
          and q.record_type = 'VOICE_TUTOR' and q.status = 'completed' and q.deleted_at is null
        """.trimIndent(),
    ).bind("recordId", recordId).bind("language", targetLanguage).map { row, _ ->
        TextLocalizationSnapshot(
            row.string("source_language"), row.string("target_language"), row.string("source_hash"),
            row.string("status"), textMap(row.get("fields_json", String::class.java)), row.get("provider", String::class.java),
        )
    }.one().awaitSingleOrNull()

    @Transactional
    override suspend fun request(record: VoiceStudyLearningRecord, targetLanguage: String, now: Instant) {
        val target = QuestionLanguage.normalize(targetLanguage)
        if (record.translatableFields().keys.all { record.sourceLanguages[it] == target }) return
        if (!lockAvailableRecord(record)) return
        val previous = database.sql(
            "select source_hash, status, updated_at from voice_study_learning_localizations where record_id = :id and target_language = :target",
        ).bind("id", record.id).bind("target", target).map { row, _ ->
            Triple(row.string("source_hash"), row.string("status"), row.instant("updated_at"))
        }.one().awaitSingleOrNull()
        if (previous?.first == record.sourceHash &&
            (previous.second == "READY" || previous.third.isAfter(now.minus(RETRY_DELAY)))
        ) return
        val token = UUID.randomUUID().toString()
        if (previous == null) {
            database.sql(
                """
                insert into voice_study_learning_localizations
                (record_id, target_language, source_language, source_hash, request_token, status, created_at, updated_at)
                values (:id, :target, :source, :hash, :token, 'PENDING', :now, :now)
                """.trimIndent(),
            ).bind("id", record.id).bind("target", target).bind("source", record.sourceLanguage)
                .bind("hash", record.sourceHash).bind("token", token).bind("now", now.utc())
                .fetch().rowsUpdated().awaitSingle()
        } else {
            database.sql(
                """
                update voice_study_learning_localizations set source_language = :source, source_hash = :hash,
                    request_token = :token, status = 'PENDING', fields_json = null, error_message = null, updated_at = :now
                where record_id = :id and target_language = :target
                """.trimIndent(),
            ).bind("source", record.sourceLanguage).bind("hash", record.sourceHash).bind("token", token)
                .bind("now", now.utc()).bind("id", record.id).bind("target", target).fetch().rowsUpdated().awaitSingle()
        }
        translations.append(
            ContentTranslationRequestedEvent(
                eventId = "content-translation-$token", contentType = LocalizableContentType.VOICE_STUDY_RECORD,
                contentId = record.id, targetLanguage = target, sourceHash = record.sourceHash, requestedAt = now,
            ), now,
        )
    }

    @Transactional
    override suspend fun saveReady(
        record: VoiceStudyLearningRecord,
        event: ContentTranslationRequestedEvent,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean {
        if (record.id != event.contentId || record.sourceHash != event.sourceHash ||
            event.contentType != LocalizableContentType.VOICE_STUDY_RECORD
        ) return false
        if (!lockAvailableRecord(record)) return false
        val sourceFields = record.translatableFields()
        require(result.fields.keys == sourceFields.keys && result.fields.values.all { !it.isNullOrBlank() }) {
            "Voice learning translation returned an invalid field set."
        }
        val json = mapper.writeValueAsString(result.fields)
        require(json.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Voice learning translation exceeded its size limit." }
        val saved = database.sql(
            """
            update voice_study_learning_localizations set status = 'READY', fields_json = :fields,
                provider = :provider, error_message = null, updated_at = :now
            where record_id = :id and target_language = :target and source_hash = :hash
              and request_token = :token and status = 'PENDING'
              and exists (
                  select 1 from voice_study_learning_records r
                  join questions q on q.voice_record_id = r.id and q.user_id = r.user_id
                  where r.id = :id and r.source_hash = :hash
                    and q.record_type = 'VOICE_TUTOR' and q.status = 'completed' and q.deleted_at is null
              )
            """.trimIndent(),
        ).bind("fields", json).bind("provider", result.provider.take(64)).bind("now", now.utc())
            .bind("id", record.id).bind("target", event.targetLanguage).bind("hash", event.sourceHash)
            .bind("token", event.eventId.removePrefix("content-translation-"))
            .fetch().rowsUpdated().awaitSingle() == 1L
        if (saved) searchProjection.refresh(requireNotNull(record.recordId))
        return saved
    }

    override suspend fun markFailed(event: ContentTranslationRequestedEvent, error: String, now: Instant) {
        if (event.contentType != LocalizableContentType.VOICE_STUDY_RECORD) return
        database.sql(
            """
            update voice_study_learning_localizations set status = 'FAILED', error_message = :error, updated_at = :now
            where record_id = :id and target_language = :target and source_hash = :hash
              and request_token = :token and status = 'PENDING'
              and exists (
                  select 1 from voice_study_learning_records r
                  join questions q on q.voice_record_id = r.id and q.user_id = r.user_id
                  join voice_tutor_sessions s on s.id = r.session_id and s.user_id = r.user_id
                  where r.id = :id and r.source_hash = :hash
                    and q.record_type = 'VOICE_TUTOR' and q.status = 'completed' and q.deleted_at is null
              )
            """.trimIndent(),
        ).bind("error", "Voice learning record translation failed.").bind("now", now.utc())
            .bind("id", event.contentId).bind("target", event.targetLanguage).bind("hash", event.sourceHash)
            .bind("token", event.eventId.removePrefix("content-translation-"))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun snapshots(userId: Long, sessionId: String): List<VoiceTutorStudySnapshot> = database.sql(
        """
        select v.study_id, v.parent_study_id, v.topic, v.difficulty, v.revision from (
            select session_id, study_id, parent_study_id, topic, difficulty, captured_at, 0 as revision
            from voice_tutor_study_snapshots where session_id = :sessionId
            union all
            select session_id, study_id, parent_study_id, topic, difficulty, captured_at, revision
            from voice_tutor_study_revisions where session_id = :sessionId
        ) v
        join voice_tutor_sessions s on s.id = v.session_id
        where s.id = :sessionId and s.user_id = :userId
        order by v.revision, v.captured_at, v.study_id limit ${VoiceTutorStudyRevisionLimits.MAX_HISTORY_SNAPSHOTS}
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId).map { row, _ ->
        VoiceTutorStudySnapshot(row.long("study_id"), (row.get("parent_study_id") as? Number)?.toLong(), row.string("topic"), row.int("difficulty"), row.long("revision"))
    }.all().collectList().awaitSingle()

    private suspend fun transcript(sessionId: String): List<VoiceTutorTranscriptTurn> = database.sql(
        "select * from voice_tutor_transcript_turns where session_id = :sessionId order by sequence_number, id limit 2000",
    ).bind("sessionId", sessionId).map { row, _ ->
        VoiceTutorTranscriptTurn(
            row.long("id"), row.string("session_id"), row.string("provider_item_id"),
            VoiceTutorTranscriptRole.valueOf(row.string("role")), row.string("transcript"),
            row.long("sequence_number"), row.instant("occurred_at"), row.long("lesson_revision"),
            (row.get("study_question_turn_id") as? Number)?.toLong(),
            (row.get("study_answer_turn_id") as? Number)?.toLong(),
            row.get("asked_study_question", java.lang.Boolean::class.java) == true,
            row.get("is_study_question", java.lang.Boolean::class.java) == true,
        )
    }.all().collectList().awaitSingle()

    private suspend fun focuses(userId: Long, sessionId: String): List<VoiceTutorLessonFocus> = database.sql(
        """
        select focus.study_id, focus.revision from voice_tutor_lesson_focuses focus
        join voice_tutor_sessions session on session.id = focus.session_id
        where session.id = :sessionId and session.user_id = :userId
        order by focus.revision limit ${VoiceTutorStudyRevisionLimits.MAX_REVISIONS + 1}
        """.trimIndent(),
    ).bind("sessionId", sessionId).bind("userId", userId)
        .map { row, _ -> VoiceTutorLessonFocus(row.long("study_id"), row.long("revision")) }
        .all().collectList().awaitSingle()

    private suspend fun insertExtension(record: VoiceStudyLearningRecord, now: Instant): Long {
        database.sql(
            """
            insert into voice_study_learning_records (
                user_id, session_id, study_id, parent_study_id, kind,
                strengths_json, improvements_json, depth_summary,
                question_turn_id, answer_turn_ids_json, feedback_turn_ids_json,
                source_languages_json, source_hash, created_at
            ) values (
                :userId, :sessionId, :studyId, :parentId, :kind,
                :strengths, :improvements, :depth,
                :questionTurnId, :answerIds, :feedbackIds, :sourceLanguages, :hash, :now
            ) on duplicate key update id = id
            """.trimIndent(),
        ).bind("userId", record.userId).bind("sessionId", record.sessionId).bind("studyId", record.studyId)
            .nullable("parentId", record.parentStudyId, Long::class.javaObjectType)
            .bind("kind", record.kind.name)
            .bind("strengths", mapper.writeValueAsString(record.strengths)).bind("improvements", mapper.writeValueAsString(record.improvements))
            .bind("depth", record.depthSummary).bind("questionTurnId", record.questionTurnId)
            .bind("answerIds", mapper.writeValueAsString(record.answerTurnIds)).bind("feedbackIds", mapper.writeValueAsString(record.feedbackTurnIds))
            .bind("sourceLanguages", mapper.writeValueAsString(record.sourceLanguages))
            .bind("hash", record.sourceHash).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        return database.sql(
            "select id from voice_study_learning_records where session_id = :sessionId and question_turn_id = :turnId and user_id = :userId",
        ).bind("sessionId", record.sessionId).bind("turnId", record.questionTurnId).bind("userId", record.userId)
            .map { row, _ -> row.long("id") }.one().awaitSingle()
    }

    private fun VoiceStudyLearningRecord.canonicalQuestion(extensionId: Long, defaultPublic: Boolean, now: Instant) = QuestionEntity(
        // Voice has no question-generation/push device. Never invent a historical installation.
        deviceId = "", userId = userId, studyId = studyId,
        question = question, topic = topic, difficultyLevel = difficulty,
        sourceLanguage = SupportedLanguage.fromLocale(sourceLanguages["question"] ?: sourceLanguage),
        scheduledFor = createdAt, status = QuestionStatus.COMPLETED, source = QuestionSource.VOICE_TUTOR,
        answer = answer, score = score, feedback = feedback,
        answerSourceLanguage = answer?.let { SupportedLanguage.fromLocale(sourceLanguages["answer"] ?: sourceLanguage) },
        aiResponseSourceLanguage = feedback?.let { SupportedLanguage.fromLocale(sourceLanguages["feedback"] ?: sourceLanguage) },
        // Existing sessions retain their private creation snapshot; account publicity is a second live gate.
        publicQuestion = defaultPublic, createdAt = createdAt, updatedAt = now,
        recordType = StudyRecordType.VOICE_TUTOR, voiceRecordId = extensionId,
    )

    private suspend fun lockAvailableRecord(record: VoiceStudyLearningRecord): Boolean {
        val canonicalId = record.recordId ?: return false
        return database.sql(
            """
            select q.id from questions q
            where q.id = :canonicalId and q.voice_record_id = :id and q.user_id = :userId
              and q.record_type = 'VOICE_TUTOR' and q.status = 'completed' and q.deleted_at is null
              and exists (
                  select 1 from voice_study_learning_records r
                  join voice_tutor_sessions s on s.id = r.session_id and s.user_id = r.user_id
                  join users u on u.id = s.user_id and u.status = 'ACTIVE'
                  where r.id = :id and r.user_id = :userId and r.source_hash = :hash
              )
            for update
            """.trimIndent(),
        ).bind("canonicalId", canonicalId).bind("id", record.id).bind("userId", record.userId).bind("hash", record.sourceHash)
            .map { row, _ -> row.long("id") }.one().awaitSingleOrNull() == canonicalId
    }

    private fun Row.record() = VoiceStudyLearningRecord(
        id = long("id"), userId = long("user_id"), sessionId = string("session_id"), studyId = long("study_id"),
        parentStudyId = (get("parent_study_id") as? Number)?.toLong(), topic = string("topic"), difficulty = int("difficulty"),
        createdAt = instant("occurred_at"), kind = VoiceTutorExchangeKind.valueOf(string("kind")),
        question = string("question"), answer = get("answer", String::class.java), score = (get("score") as? Number)?.toInt(),
        strengths = strings(string("strengths_json")), improvements = strings(string("improvements_json")),
        depthSummary = string("depth_summary"), feedback = get("feedback", String::class.java),
        questionTurnId = long("question_turn_id"), answerTurnIds = longs(string("answer_turn_ids_json")),
        feedbackTurnIds = longs(string("feedback_turn_ids_json")), sourceLanguage = string("source_language"),
        sourceLanguages = textMap(string("source_languages_json")).mapValues { it.value.orEmpty() }, sourceHash = string("source_hash"),
        recordId = long("canonical_record_id"),
    )

    private fun strings(json: String): List<String> = mapper.readTree(json).map { it.asText() }
    private fun longs(json: String): List<Long> = mapper.readTree(json).map { it.longValue() }
    private fun textMap(json: String?): Map<String, String?> = json?.let { text ->
        mapper.readTree(text).fields().asSequence().associate { (key, value) -> key to if (value.isNull) null else value.asText() }
    }.orEmpty()
    private fun Row.string(name: String) = get(name, String::class.java)!!
    private fun Row.long(name: String) = (get(name) as Number).toLong()
    private fun Row.int(name: String) = (get(name) as Number).toInt()
    private fun Row.instant(name: String) = get(name, LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC)
    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
    private fun DatabaseClient.GenericExecuteSpec.nullable(name: String, value: Any?, type: Class<*>) =
        if (value == null) bindNull(name, type) else bind(name, value)

    private companion object {
        const val OWNED_RECORD_SELECT = """
            select r.*, q.id as canonical_record_id, q.question, q.answer, q.score, q.feedback,
                   q.topic, q.difficulty_level as difficulty, q.source_language, q.created_at as occurred_at
            from voice_study_learning_records r
            join voice_tutor_sessions s on s.id = r.session_id and s.user_id = r.user_id
            join questions q on q.voice_record_id = r.id and q.user_id = r.user_id
                and q.record_type = 'VOICE_TUTOR' and q.status = 'completed' and q.deleted_at is null
        """
        val RETRY_DELAY: Duration = Duration.ofMinutes(5)
    }

    private data class CompletedSessionHeader(
        val id: String, val acceptedStudyId: Long?, val language: String, val recordsDefaultPublic: Boolean,
    )
}
