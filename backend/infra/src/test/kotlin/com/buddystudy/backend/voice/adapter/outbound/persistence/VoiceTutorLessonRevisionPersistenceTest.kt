package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Production adapter against isolated H2 only; not a MySQL locking/migration equivalence claim. */
class VoiceTutorLessonRevisionPersistenceTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///voice-revision-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = VoiceTutorPersistenceAdapter(database)
    private val now = Instant.parse("2026-09-01T00:00:00Z")

    @BeforeEach
    fun schema(): Unit = runBlocking {
        execute("create table studies (id bigint primary key)")
        execute("insert into studies (id) values (42), (43)")
        execute("""
            create table voice_tutor_sessions (
                id varchar(36) primary key, user_id bigint not null,
                study_id bigint null references studies(id) on delete set null, accepted_study_id bigint null,
                idempotency_key varchar(191) not null default 'fixture', provider_session_id varchar(191) null,
                status varchar(24) not null default 'ACTIVE', result_status varchar(24) not null default 'PENDING',
                language varchar(8) not null default 'ko', model varchar(128) not null default 'gpt-realtime',
                voice varchar(64) not null default 'marin', topic_snapshot varchar(255) not null default 'Redis',
                difficulty_snapshot int not null default 3,
                period_started_at timestamp not null, period_ends_at timestamp not null,
                reserved_seconds int not null default 3600, charged_seconds int not null default 0,
                max_session_seconds int not null default 3600, hard_ends_at timestamp not null,
                monthly_quota_exhausts_at_hard_end boolean not null default false,
                post_call_transcript_incomplete boolean not null default false,
                connected_at timestamp null, relay_heartbeat_at timestamp null,
                accepted_audio_bytes bigint not null default 0, recording_consented_at timestamp null,
                recording_consent_version varchar(64) null, ended_at timestamp null, finalized_at timestamp null,
                end_reason varchar(64) null, failure_code varchar(64) null, failure_message varchar(1000) null,
                created_at timestamp not null, updated_at timestamp not null
            )
        """.trimIndent())
        execute("""
            create table voice_tutor_transcript_turns (
                id bigint auto_increment primary key,
                session_id varchar(36) not null references voice_tutor_sessions(id) on delete cascade,
                provider_item_id varchar(191) not null, role varchar(16) not null, transcript text not null,
                sequence_number bigint not null, occurred_at timestamp not null, created_at timestamp not null,
                lesson_revision bigint not null default 0 check (lesson_revision >= -1),
                study_question_turn_id bigint null,
                study_answer_turn_id bigint null,
                asked_study_question boolean not null default false,
                is_study_question boolean not null default false,
                post_call_evidence boolean not null default false,
                interrupted boolean not null default false,
                check (study_question_turn_id is null or role = 'USER'),
                check (study_question_turn_id is null or asked_study_question = false),
                check (study_answer_turn_id is null or role = 'TUTOR'),
                check (is_study_question = false or role = 'TUTOR'),
                unique (session_id, provider_item_id, role),
                unique (session_id, sequence_number)
            )
        """.trimIndent())
        execute("""
            create table voice_tutor_lesson_focuses (
                session_id varchar(36) not null references voice_tutor_sessions(id) on delete cascade,
                revision bigint not null, study_id bigint not null, captured_at timestamp not null,
                primary key (session_id, revision)
            )
        """.trimIndent())
        seedSession("owned", userId = 7, studyId = 42)
        seedSession("foreign", userId = 8, studyId = 43)
    }

    @Test
    fun `native source integrity fence is durable owner bound and idempotent`(): Unit = runBlocking {
        assertThat(adapter.findSession(7, "owned")!!.postCallTranscriptIncomplete).isFalse()
        assertThat(adapter.markTranscriptIncomplete(8, "owned", now)).isFalse()
        assertThat(adapter.markTranscriptIncomplete(7, "owned", now)).isTrue()
        assertThat(adapter.markTranscriptIncomplete(7, "owned", now.plusSeconds(1))).isTrue()
        assertThat(adapter.findSession(7, "owned")!!.postCallTranscriptIncomplete).isTrue()
        assertThat(adapter.findSession(8, "foreign")!!.postCallTranscriptIncomplete).isFalse()
        execute("update voice_tutor_sessions set result_status = 'COMPLETED' where id = 'foreign'")
        assertThat(adapter.markTranscriptIncomplete(8, "foreign", now)).isFalse()
    }

    @Test
    fun `append and read preserve each original transcript and its response time lesson revision`(): Unit = runBlocking {
        assertThat(append("question-old", VoiceTutorTranscriptRole.TUTOR, "기존 난도의 질문입니다.", 0)).isTrue()
        assertThat(append("answer-old", VoiceTutorTranscriptRole.USER, "기존 질문에 대한 제 답변입니다.", 0)).isTrue()
        assertThat(append("question-new", VoiceTutorTranscriptRole.TUTOR, "변경된 난도의 새 질문입니다.", 3)).isTrue()

        val turns = adapter.transcript(7, "owned", 4_000)

        assertThat(turns.map { it.lessonRevision }).containsExactly(0L, 0L, 3L)
        assertThat(turns.map { it.transcript }).containsExactly(
            "기존 난도의 질문입니다.", "기존 질문에 대한 제 답변입니다.", "변경된 난도의 새 질문입니다.",
        )
        assertThat(turns.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L)
        assertThat(turns.map { it.occurredAt }.distinct()).containsExactly(now)
    }

    @Test
    fun `a replay at a newer revision cannot rewrite the first saved question or its level evidence`(): Unit = runBlocking {
        assertThat(append("question", VoiceTutorTranscriptRole.TUTOR, "원래 질문", 1)).isTrue()
        assertThat(append("question", VoiceTutorTranscriptRole.TUTOR, "바꾸면 안 되는 재전송", 5)).isFalse()

        val stored = adapter.transcript(7, "owned", 4_000).single()

        assertThat(stored.transcript).isEqualTo("원래 질문")
        assertThat(stored.lessonRevision).isEqualTo(1)
    }

    @Test
    fun `missing revision evidence keeps the exact source as unassigned rather than revision zero`(): Unit = runBlocking {
        assertThat(append("unknown-epoch", VoiceTutorTranscriptRole.USER, "메타데이터와 무관하게 보존할 원문", -1)).isTrue()

        val stored = adapter.transcript(7, "owned", 4_000).single()

        assertThat(stored.lessonRevision).isEqualTo(-1)
        assertThat(stored.transcript).isEqualTo("메타데이터와 무관하게 보존할 원문")
        assertThat(adapter.transcript(8, "owned", 4_000)).isEmpty()
    }

    @Test
    fun `legacy database default remains zero while owner and terminal state still gate new transcripts`(): Unit = runBlocking {
        database.sql("""
            insert into voice_tutor_transcript_turns
                (session_id, provider_item_id, role, transcript, sequence_number, occurred_at, created_at)
            values ('owned', 'legacy', 'TUTOR', 'Legacy question', 1, :now, :now)
        """.trimIndent()).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(adapter.transcript(7, "owned", 4_000).single().lessonRevision).isZero()
        assertThat(adapter.appendTranscript(8, "owned", "foreign-write", VoiceTutorTranscriptRole.USER,
            "not owned", now, 4_000, 10, lessonRevision = 2)).isFalse()
        execute("update voice_tutor_sessions set status = 'COMPLETED' where id = 'owned'")
        assertThat(append("late-write", VoiceTutorTranscriptRole.USER, "late source", 2)).isFalse()
        assertThat(adapter.transcript(7, "owned", 4_000)).hasSize(1)
    }

    @Test
    fun `active learner transcripts honor the hard boundary and WebRTC trusted receipt`(): Unit = runBlocking {
        val hardEnd = now.plusSeconds(3_600)
        assertThat(adapter.appendTranscript(
            7, "owned", "legacy-at-boundary", VoiceTutorTranscriptRole.USER, "legacy", hardEnd,
            4_000, 20,
        )).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "legacy-after-boundary", VoiceTutorTranscriptRole.USER, "late legacy",
            hardEnd.plusMillis(1), 4_000, 20,
        )).isFalse()

        execute("update voice_tutor_sessions set provider_session_id = 'rtc_owned' where id = 'owned'")
        assertThat(adapter.appendTranscript(
            7, "owned", "rtc-untrusted", VoiceTutorTranscriptRole.USER, "untrusted", now.plusSeconds(1),
            4_000, 20,
        )).isFalse()
        assertThat(adapter.appendTranscript(
            7, "owned", "rtc-trusted", VoiceTutorTranscriptRole.USER, "trusted", hardEnd,
            4_000, 20, acceptedBeforeQuotaCutoff = true,
        )).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "rtc-trusted-late", VoiceTutorTranscriptRole.USER, "late trusted",
            hardEnd.plusMillis(1), 4_000, 20, acceptedBeforeQuotaCutoff = true,
        )).isFalse()
    }

    @Test
    fun `accepted study identity survives live study deletion and remains owner scoped`(): Unit = runBlocking {
        val before = adapter.findSession(7, "owned")!!
        assertThat(before.studyId).isEqualTo(42)
        assertThat(before.acceptedStudyId).isEqualTo(42)

        execute("delete from studies where id = 42")

        val after = adapter.findSession(7, "owned")!!
        assertThat(after.studyId).isNull()
        assertThat(after.acceptedStudyId).isEqualTo(42)
        assertThat(after.topic).isEqualTo(before.topic)
        assertThat(after.difficulty).isEqualTo(before.difficulty)
        assertThat(adapter.findSession(8, "owned")).isNull()
        assertThat(adapter.findSession(8, "foreign")!!.acceptedStudyId).isEqualTo(43)
    }

    @Test
    fun `reaching the storage turn limit returns false without inserting a consent bearing source`(): Unit = runBlocking {
        assertThat(append("first", VoiceTutorTranscriptRole.USER, "기존에 저장한 응답", 1)).isTrue()

        val stored = adapter.appendTranscript(
            7, "owned", "over-capacity", VoiceTutorTranscriptRole.USER,
            "확인으로 승격하면 안 되는 미저장 응답", now, 4_000, 1, lessonRevision = 2,
        )

        assertThat(stored).isFalse()
        assertThat(adapter.transcript(7, "owned", 4_000).map { it.providerItemId }).containsExactly("first")
        assertThat(adapter.findSession(7, "owned")!!.status.name).isEqualTo("ACTIVE")
    }

    @Test
    fun `only a final assessed answer linked to an exact focused tutor question becomes learning evidence`(): Unit = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("study-question", VoiceTutorTranscriptRole.TUTOR, "DI의 장점은 무엇인가요?", 1, true)).isTrue()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()

        assertThat(
            adapter.appendTranscript(
                7, "owned", "study-answer", VoiceTutorTranscriptRole.USER,
                "결합도를 낮추고 테스트를 쉽게 합니다.", now, 4_000, 20, lessonRevision = 1,
                studyQuestionProviderItemId = "study-question",
                studyAnswerProviderItemIds = listOf("study-answer"),
            ),
        ).isTrue()

        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isTrue()
        assertThat(adapter.hasVerifiedLearningExchange(8, "owned")).isFalse()
        assertThat(count("select count(*) from voice_tutor_transcript_turns where study_question_turn_id is not null"))
            .isEqualTo(1)
        val turns = adapter.transcript(7, "owned", 4_000)
        assertThat(turns.single { it.providerItemId == "study-question" }.isStudyQuestion).isTrue()
        assertThat(turns.single { it.providerItemId == "study-question" }.studyQuestionTurnId).isNull()
        assertThat(turns.single { it.providerItemId == "study-answer" }.studyQuestionTurnId)
            .isEqualTo(turns.single { it.providerItemId == "study-question" }.id)
    }

    @Test
    fun `answer link allows user checkpoints but rejects any intervening tutor after the exact question`() = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("question", VoiceTutorTranscriptRole.TUTOR, "DI가 무엇인가요?", 1, true)).isTrue()
        assertThat(append("checkpoint", VoiceTutorTranscriptRole.USER, "외부에서", 1)).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "answer", VoiceTutorTranscriptRole.USER, "외부에서 의존성을 주입합니다.", now,
            4_000, 20, lessonRevision = 1, studyQuestionProviderItemId = "question",
            studyAnswerProviderItemIds = listOf("answer"),
        )).isTrue()
        assertThat(adapter.transcript(7, "owned", 4_000).last().studyQuestionTurnId).isNotNull()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isTrue()

        assertThat(append("next-question", VoiceTutorTranscriptRole.TUTOR, "설정 확인입니다.", 1)).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "late-answer", VoiceTutorTranscriptRole.USER, "이전 질문의 늦은 답입니다.", now,
            4_000, 20, lessonRevision = 1, studyQuestionProviderItemId = "question",
            studyAnswerProviderItemIds = listOf("late-answer"),
        )).isFalse()
        assertThat(adapter.transcript(7, "owned", 4_000).map { it.providerItemId })
            .doesNotContain("late-answer")
    }

    @Test
    fun `final filler atomically promotes the exact ordered durable answer parts and feedback anchors the last part`() =
        runBlocking {
            database.sql(
                "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) " +
                    "values ('owned', 1, 42, :now)",
            ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
            assertThat(append("multipart-question", VoiceTutorTranscriptRole.TUTOR, "DI의 장점은?", 1, true))
                .isTrue()
            assertThat(append("answer-part-1", VoiceTutorTranscriptRole.USER, "결합도를 낮추고", 1)).isTrue()
            assertThat(append("answer-part-2", VoiceTutorTranscriptRole.USER, "테스트를 쉽게 합니다.", 1)).isTrue()

            assertThat(adapter.appendTranscript(
                7, "owned", "filler-tail", VoiceTutorTranscriptRole.USER, "음…", now,
                4_000, 20, lessonRevision = 1,
                studyQuestionProviderItemId = "multipart-question",
                studyAnswerProviderItemIds = listOf("answer-part-1", "answer-part-2"),
            )).isTrue()

            val turns = adapter.transcript(7, "owned", 4_000)
            val questionId = turns.single { it.providerItemId == "multipart-question" }.id
            assertThat(turns.filter { it.studyQuestionTurnId == questionId }.map { it.providerItemId })
                .containsExactly("answer-part-1", "answer-part-2")
            assertThat(turns.single { it.providerItemId == "filler-tail" }.studyQuestionTurnId).isNull()
            assertThat(adapter.appendTranscript(
                7, "owned", "multipart-feedback", VoiceTutorTranscriptRole.TUTOR,
                "정확합니다. 두 장점을 잘 설명했습니다.", now, 4_000, 20,
                lessonRevision = 1, studyAnswerProviderItemId = "answer-part-2",
            )).isTrue()
            val withFeedback = adapter.transcript(7, "owned", 4_000)
            assertThat(withFeedback.single { it.providerItemId == "multipart-feedback" }.studyAnswerTurnId)
                .isEqualTo(withFeedback.single { it.providerItemId == "answer-part-2" }.id)
        }

    @Test
    fun `feedback cannot anchor an earlier durable part of a multipart answer`() = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) " +
                "values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("multipart-question", VoiceTutorTranscriptRole.TUTOR, "DI의 장점은?", 1, true))
            .isTrue()
        assertThat(append("answer-part-1", VoiceTutorTranscriptRole.USER, "결합도를 낮추고", 1)).isTrue()
        assertThat(append("answer-part-2", VoiceTutorTranscriptRole.USER, "테스트를 쉽게 합니다.", 1)).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "filler-tail", VoiceTutorTranscriptRole.USER, "음…", now,
            4_000, 20, lessonRevision = 1,
            studyQuestionProviderItemId = "multipart-question",
            studyAnswerProviderItemIds = listOf("answer-part-1", "answer-part-2"),
        )).isTrue()

        assertThat(adapter.appendTranscript(
            7, "owned", "earlier-part-feedback", VoiceTutorTranscriptRole.TUTOR,
            "85점입니다. 결합도를 잘 설명했습니다.", now, 4_000, 20,
            lessonRevision = 1, studyAnswerProviderItemId = "answer-part-1",
        )).isTrue()

        val turns = adapter.transcript(7, "owned", 4_000)
        assertThat(turns.single { it.providerItemId == "earlier-part-feedback" }.studyAnswerTurnId).isNull()
    }

    @Test
    fun `duplicate reverse missing and revision mismatched promotion proofs fail without partial links`() =
        runBlocking {
            database.sql(
                "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) " +
                    "values ('owned', 1, 42, :now)",
            ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
            assertThat(append("strict-question", VoiceTutorTranscriptRole.TUTOR, "DI를 설명해 보세요.", 1, true))
                .isTrue()
            assertThat(append("strict-part-1", VoiceTutorTranscriptRole.USER, "외부에서", 1)).isTrue()
            assertThat(append("strict-part-2", VoiceTutorTranscriptRole.USER, "주입합니다.", 1)).isTrue()

            val invalidProofs = listOf(
                listOf("strict-part-1", "strict-part-1"),
                listOf("strict-part-2", "strict-part-1"),
                listOf("missing-part"),
            )
            invalidProofs.forEachIndexed { index, proof ->
                assertThat(adapter.appendTranscript(
                    7, "owned", "invalid-final-$index", VoiceTutorTranscriptRole.USER, "음…", now,
                    4_000, 20, lessonRevision = 1,
                    studyQuestionProviderItemId = "strict-question",
                    studyAnswerProviderItemIds = proof,
                )).isFalse()
            }
            assertThat(adapter.appendTranscript(
                7, "owned", "wrong-revision-final", VoiceTutorTranscriptRole.USER, "음…", now,
                4_000, 20, lessonRevision = 2,
                studyQuestionProviderItemId = "strict-question",
                studyAnswerProviderItemIds = listOf("strict-part-1", "strict-part-2"),
            )).isFalse()

            assertThat(adapter.transcript(7, "owned", 4_000)
                .filter { it.providerItemId.startsWith("strict-part") }
                .map { it.studyQuestionTurnId }).containsOnlyNulls()
            assertThat(count(
                "select count(*) from voice_tutor_transcript_turns where provider_item_id like 'invalid-final-%' " +
                    "or provider_item_id = 'wrong-revision-final'",
            )).isZero()
        }

    @Test
    fun `feedback provenance links only a clean tutor item to its exact verified answer`() = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("question", VoiceTutorTranscriptRole.TUTOR, "DI의 장점은?", 1, true)).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "answer", VoiceTutorTranscriptRole.USER, "결합도를 낮춥니다.", now,
            4_000, 20, lessonRevision = 1, studyQuestionProviderItemId = "question",
            studyAnswerProviderItemIds = listOf("answer"),
        )).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "feedback", VoiceTutorTranscriptRole.TUTOR, "85점입니다. 장점을 정확히 짚었습니다.", now,
            4_000, 20, lessonRevision = 1, studyAnswerProviderItemId = "answer",
        )).isTrue()

        val turns = adapter.transcript(7, "owned", 4_000)
        assertThat(turns.last().studyAnswerTurnId).isEqualTo(turns[1].id)
        assertThat(turns.last().isStudyQuestion).isFalse()
    }

    @Test
    fun `wrong answer revision next tutor and forged role cannot mint feedback provenance`() = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("question", VoiceTutorTranscriptRole.TUTOR, "DI의 장점은?", 1, true)).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "answer", VoiceTutorTranscriptRole.USER, "결합도를 낮춥니다.", now,
            4_000, 20, lessonRevision = 1, studyQuestionProviderItemId = "question",
            studyAnswerProviderItemIds = listOf("answer"),
        )).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "wrong-answer", VoiceTutorTranscriptRole.TUTOR, "70점입니다.", now,
            4_000, 20, lessonRevision = 1, studyAnswerProviderItemId = "some-other-answer",
        )).isTrue()
        assertThat(append("next-question", VoiceTutorTranscriptRole.TUTOR, "다음 질문입니다.", 1, true)).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "late-feedback", VoiceTutorTranscriptRole.TUTOR, "90점입니다.", now,
            4_000, 20, lessonRevision = 1, studyAnswerProviderItemId = "answer",
        )).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "wrong-revision", VoiceTutorTranscriptRole.TUTOR, "80점입니다.", now,
            4_000, 20, lessonRevision = 2, studyAnswerProviderItemId = "answer",
        )).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "forged-user", VoiceTutorTranscriptRole.USER, "가짜 피드백", now,
            4_000, 20, lessonRevision = 1, studyAnswerProviderItemId = "answer",
        )).isTrue()

        assertThat(adapter.transcript(7, "owned", 4_000).takeLast(4).map { it.studyAnswerTurnId })
            .containsOnlyNulls()
    }

    @Test
    fun `transcript character budget returns complete turns only and never a later suffix`() = runBlocking {
        assertThat(append("first", VoiceTutorTranscriptRole.TUTOR, "12345", 0)).isTrue()
        assertThat(append("second", VoiceTutorTranscriptRole.USER, "67890", 0)).isTrue()
        assertThat(append("third", VoiceTutorTranscriptRole.TUTOR, "suffix", 0)).isTrue()

        assertThat(adapter.transcript(7, "owned", 9).map { it.providerItemId }).containsExactly("first")
        assertThat(adapter.transcript(7, "owned", 9).single().transcript).isEqualTo("12345")
    }

    @Test
    fun `transcript character budget never cuts a multipart answer or skips an interleaved row`() = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) " +
                "values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("budget-question", VoiceTutorTranscriptRole.TUTOR, "QQ", 1, true)).isTrue()
        assertThat(append("budget-part-1", VoiceTutorTranscriptRole.USER, "AA", 1)).isTrue()
        assertThat(append("budget-noise", VoiceTutorTranscriptRole.USER, "F", 1)).isTrue()
        assertThat(append("budget-part-2", VoiceTutorTranscriptRole.USER, "BB", 1)).isTrue()
        assertThat(adapter.appendTranscript(
            7, "owned", "budget-final", VoiceTutorTranscriptRole.USER, "T", now,
            4_000, 20, lessonRevision = 1, studyQuestionProviderItemId = "budget-question",
            studyAnswerProviderItemIds = listOf("budget-part-1", "budget-part-2"),
        )).isTrue()

        assertThat(adapter.transcript(7, "owned", 6)).isEmpty()
        assertThat(adapter.transcript(7, "owned", 7).map { it.providerItemId }).containsExactly(
            "budget-question", "budget-part-1", "budget-noise", "budget-part-2",
        )
    }

    @Test
    fun `focused but unverified tutor speech cannot be linked as a study question`() = runBlocking<Unit> {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("setup-in-focus", VoiceTutorTranscriptRole.TUTOR, "좋아요, 학습을 시작하겠습니다.", 1)).isTrue()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "forced-answer", VoiceTutorTranscriptRole.USER,
                "강제로 답변으로 분류된 설정 응답", now, 4_000, 20,
                lessonRevision = 1, studyQuestionProviderItemId = "setup-in-focus",
                studyAnswerProviderItemIds = listOf("forced-answer"),
            ),
        ).isFalse()

        val turns = adapter.transcript(7, "owned", 4_000)
        assertThat(turns.single { it.providerItemId == "setup-in-focus" }.isStudyQuestion).isFalse()
        assertThat(turns.map { it.providerItemId }).doesNotContain("forced-answer")
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()
    }

    @Test
    fun `natural mutation after a real study question remains setup and never becomes an answer`() = runBlocking<Unit> {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("study-question", VoiceTutorTranscriptRole.TUTOR, "DI의 장점은 무엇인가요?", 1, true))
            .isTrue()
        assertThat(
            append(
                "mutation-command", VoiceTutorTranscriptRole.USER,
                "Spring 주제 이름을 Spring Boot로 바꾸고 레벨을 7로 수정해줘.", 1,
            ),
        ).isTrue()
        assertThat(
            append(
                "mutation-acknowledgement", VoiceTutorTranscriptRole.TUTOR,
                "Spring Boot, 레벨 7로 수정했습니다.", 2,
            ),
        ).isTrue()

        val turns = adapter.transcript(7, "owned", 4_000)
        assertThat(turns.single { it.providerItemId == "study-question" }.isStudyQuestion).isTrue()
        assertThat(turns.single { it.providerItemId == "mutation-command" }.studyQuestionTurnId).isNull()
        assertThat(turns.single { it.providerItemId == "mutation-command" }.askedStudyQuestion).isFalse()
        assertThat(turns.single { it.providerItemId == "mutation-acknowledgement" }.studyAnswerTurnId).isNull()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()
    }

    @Test
    fun `the latest earlier focus carries forward to later lesson revisions for both learning attestations`(): Unit = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 1, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("carried-question", VoiceTutorTranscriptRole.TUTOR, "캐시 무효화가 왜 어려운가요?", 2, true)).isTrue()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "carried-answer", VoiceTutorTranscriptRole.USER,
                "원본과 캐시의 일관성을 유지해야 하기 때문입니다.", now, 4_000, 20,
                lessonRevision = 2, studyQuestionProviderItemId = "carried-question", askedStudyQuestion = true,
                studyAnswerProviderItemIds = listOf("carried-answer"),
            ),
        ).isTrue()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "carried-learner-question", VoiceTutorTranscriptRole.USER,
                "TTL은 언제 사용하는 게 좋나요?", now, 4_000, 20,
                lessonRevision = 2, askedStudyQuestion = true,
            ),
        ).isTrue()

        val turns = adapter.transcript(7, "owned", 4_000)
        val question = turns.single { it.providerItemId == "carried-question" }
        assertThat(turns.single { it.providerItemId == "carried-answer" }.studyQuestionTurnId).isEqualTo(question.id)
        assertThat(turns.single { it.providerItemId == "carried-answer" }.askedStudyQuestion).isFalse()
        assertThat(turns.single { it.providerItemId == "carried-learner-question" }.askedStudyQuestion).isTrue()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isTrue()
    }

    @Test
    fun `a future focus never attests an earlier tutor answer or learner question`(): Unit = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 3, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("past-question", VoiceTutorTranscriptRole.TUTOR, "아직 선택되지 않은 주제 질문", 2, true)).isTrue()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "past-answer", VoiceTutorTranscriptRole.USER,
                "미래 선택으로 인증되면 안 됩니다.", now, 4_000, 20,
                lessonRevision = 2, studyQuestionProviderItemId = "past-question",
                studyAnswerProviderItemIds = listOf("past-answer"),
            ),
        ).isFalse()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "past-learner-question", VoiceTutorTranscriptRole.USER,
                "이 질문도 미래 선택에 기대면 안 되나요?", now, 4_000, 20,
                lessonRevision = 2, askedStudyQuestion = true,
            ),
        ).isTrue()

        val turns = adapter.transcript(7, "owned", 4_000)
        assertThat(turns.map { it.providerItemId }).doesNotContain("past-answer")
        assertThat(turns.single { it.providerItemId == "past-learner-question" }.askedStudyQuestion).isFalse()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()
    }

    @Test
    fun `setup replies and mismatched question identities never become verified learning exchanges`(): Unit = runBlocking {
        assertThat(append("setup-question", VoiceTutorTranscriptRole.TUTOR, "새 루트를 만들까요?", 0, true)).isTrue()
        assertThat(append("setup-answer", VoiceTutorTranscriptRole.USER, "만들어줘.", 0)).isTrue()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "unfocused-answer", VoiceTutorTranscriptRole.USER,
                "네.", now, 4_000, 20, lessonRevision = 0,
                studyQuestionProviderItemId = "setup-question",
                studyAnswerProviderItemIds = listOf("unfocused-answer"),
            ),
        ).isFalse()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "unknown-answer", VoiceTutorTranscriptRole.USER,
                "응답입니다.", now, 4_000, 20, lessonRevision = 2,
                studyQuestionProviderItemId = "missing-question",
                studyAnswerProviderItemIds = listOf("unknown-answer"),
            ),
        ).isFalse()

        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()
        assertThat(count("select count(*) from voice_tutor_transcript_turns where study_question_turn_id is not null"))
            .isZero()
        assertThat(adapter.transcript(7, "owned", 4_000).map { it.providerItemId })
            .doesNotContain("unfocused-answer", "unknown-answer")
    }

    @Test
    fun `learner follow-up alone does not qualify until a focused tutor question has a verified answer`(): Unit = runBlocking {
        assertThat(
            adapter.appendTranscript(
                7, "owned", "unfocused-question", VoiceTutorTranscriptRole.USER,
                "왜 그런가요?", now, 4_000, 20, lessonRevision = 2, askedStudyQuestion = true,
            ),
        ).isTrue()
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 3, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "focused-question", VoiceTutorTranscriptRole.USER,
                "LFU는 빈도를 어떻게 추적하나요?", now, 4_000, 20,
                lessonRevision = 3, askedStudyQuestion = true,
            ),
        ).isTrue()
        assertThat(append("focused-answer", VoiceTutorTranscriptRole.TUTOR, "빈도별 연결 목록을 사용합니다.", 3)).isTrue()

        val turns = adapter.transcript(7, "owned", 4_000)
        assertThat(turns.single { it.providerItemId == "unfocused-question" }.askedStudyQuestion).isFalse()
        assertThat(turns.single { it.providerItemId == "focused-question" }.askedStudyQuestion).isTrue()
        assertThat(turns.single { it.providerItemId == "focused-answer" }.askedStudyQuestion).isFalse()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()

        assertThat(append("study-question", VoiceTutorTranscriptRole.TUTOR, "LFU와 LRU의 차이는 무엇인가요?", 3, true)).isTrue()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "study-answer", VoiceTutorTranscriptRole.USER,
                "LFU는 사용 빈도를, LRU는 최근 사용 시점을 기준으로 제거합니다.", now, 4_000, 20,
                lessonRevision = 3, studyQuestionProviderItemId = "study-question",
                studyAnswerProviderItemIds = listOf("study-answer"),
            ),
        ).isTrue()

        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isTrue()
    }

    @Test
    fun `replay cannot upgrade an unverified learner row into an attested study question`(): Unit = runBlocking {
        database.sql(
            "insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 4, 42, :now)",
        ).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(append("replayed-question", VoiceTutorTranscriptRole.USER, "설정 질문입니다.", 4)).isTrue()
        assertThat(
            adapter.appendTranscript(
                7, "owned", "replayed-question", VoiceTutorTranscriptRole.USER,
                "바뀐 텍스트", now, 4_000, 20, lessonRevision = 4, askedStudyQuestion = true,
            ),
        ).isFalse()

        assertThat(adapter.transcript(7, "owned", 4_000).single().askedStudyQuestion).isFalse()
    }

    @Test
    fun `native final ASR arrival order cannot reorder conversation or establish learning`() = runBlocking<Unit> {
        execute("insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 1, 42, current_timestamp)")
        assertThat(adapter.appendTranscript(7, "owned", "user", VoiceTutorTranscriptRole.USER, "응", now,
            4000, 20, lessonRevision = 1, postCallEvidence = true, conversationSequence = 20)).isTrue()
        assertThat(adapter.appendTranscript(7, "owned", "tutor", VoiceTutorTranscriptRole.TUTOR, "레벨을 바꿀까요?", now,
            4000, 20, lessonRevision = 1, postCallEvidence = true, conversationSequence = 10)).isTrue()
        val turns = adapter.transcript(7, "owned", 4000)
        assertThat(turns.map { it.providerItemId }).containsExactly("tutor", "user")
        assertThat(turns.map { it.sequenceNumber }).containsExactly(10L, 20L)
        assertThat(turns.all { it.postCallEvidence && !it.isStudyQuestion && it.studyQuestionTurnId == null }).isTrue()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()
        assertThat(adapter.hasPostCallLearningCandidates(7, "owned")).isTrue()
        assertThat(adapter.hasPostCallLearningCandidates(8, "owned")).isFalse()
    }

    @Test
    fun `native input cannot mix live semantic flags or supply an invalid sequence`() = runBlocking<Unit> {
        assertThat(adapter.appendTranscript(7, "owned", "invalid", VoiceTutorTranscriptRole.TUTOR, "원문", now,
            4000, 20, postCallEvidence = true, conversationSequence = 0)).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "invalid", VoiceTutorTranscriptRole.TUTOR, "원문", now,
            4000, 20, postCallEvidence = true, conversationSequence = 1, isStudyQuestion = true)).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "invalid", VoiceTutorTranscriptRole.USER, "원문", now,
            4000, 20, postCallEvidence = true, conversationSequence = 1, studyQuestionProviderItemId = "question")).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "invalid", VoiceTutorTranscriptRole.USER, "원문", now,
            4000, 20, conversationSequence = 1)).isFalse()
        assertThat(adapter.transcript(7, "owned", 4000)).isEmpty()
    }

    @Test
    fun `private canonical question and late answer keep ordering without becoming post call candidates`() = runBlocking<Unit> {
        execute("insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 2, 42, current_timestamp)")
        assertThat(adapter.appendTranscript(7, "owned", "cancelled-answer", VoiceTutorTranscriptRole.USER,
            "  취소한 미제출 답변\n마지막 인식 결과  ", now, 4000, 20, lessonRevision = 2,
            canonicalAnswerSource = true, conversationSequence = 12)).isTrue()
        assertThat(adapter.appendTranscript(7, "owned", "canonical-question", VoiceTutorTranscriptRole.TUTOR,
            "  저장된 문제의 원문입니다.  ", now, 4000, 20, lessonRevision = 2,
            canonicalAnswerSource = true, conversationSequence = 11)).isTrue()
        val rows = adapter.transcript(7, "owned", 4000)
        assertThat(rows.map { it.providerItemId }).containsExactly("canonical-question", "cancelled-answer")
        assertThat(rows.map { it.sequenceNumber }).containsExactly(11L, 12L)
        assertThat(rows.map { it.transcript }).containsExactly("  저장된 문제의 원문입니다.  ", "  취소한 미제출 답변\n마지막 인식 결과  ")
        assertThat(rows.all { !it.postCallEvidence && !it.interrupted && !it.isStudyQuestion &&
            !it.askedStudyQuestion && it.studyQuestionTurnId == null && it.studyAnswerTurnId == null }).isTrue()
        assertThat(adapter.hasPostCallLearningCandidates(7, "owned")).isFalse()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()
        // The exact source is still available if the learner deliberately submits
        // a reviewed answer; the existing canonical exclusion is idempotent.
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "cancelled-answer", "canonical-question", 2))
            .containsExactlyElementsOf(rows)
        assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", listOf("canonical-question", "cancelled-answer"))).isTrue()
        assertThat(adapter.transcript(7, "owned", 4000)).containsExactlyElementsOf(rows)
    }

    @Test
    fun `private canonical source rejects unowned unordered and semantically attested inserts`() = runBlocking<Unit> {
        suspend fun append(userId: Long = 7, sequence: Long? = 1, revision: Long = 0,
            postCall: Boolean = false, interrupted: Boolean = false, question: String? = null,
            isQuestion: Boolean = false, acceptedAt: Instant = now) = adapter.appendTranscript(
                userId, "owned", "invalid-private", VoiceTutorTranscriptRole.TUTOR, "보존할 원문", acceptedAt,
                4000, 20, lessonRevision = revision, studyQuestionProviderItemId = question,
                isStudyQuestion = isQuestion, postCallEvidence = postCall, conversationSequence = sequence,
                interrupted = interrupted, canonicalAnswerSource = true)
        assertThat(append(userId = 8)).isFalse()
        assertThat(append(sequence = null)).isFalse()
        assertThat(append(sequence = 0)).isFalse()
        assertThat(append(revision = -1)).isFalse()
        assertThat(append(postCall = true)).isFalse()
        assertThat(append(interrupted = true)).isFalse()
        assertThat(append(question = "question")).isFalse()
        assertThat(append(isQuestion = true)).isFalse()
        assertThat(append(acceptedAt = now.minusSeconds(1))).isFalse()
        assertThat(adapter.transcript(7, "owned", 4000)).isEmpty()
    }

    @Test
    fun `canonical answer lookup returns the complete latest run beyond a clipped history prefix`() = runBlocking<Unit> {
        native("old", VoiceTutorTranscriptRole.TUTOR, 1, text = "오래된 대화".repeat(500))
        native("question", VoiceTutorTranscriptRole.TUTOR, 100, text = "원래 도착한 문제입니다.")
        native("part-1", VoiceTutorTranscriptRole.USER, 101, text = "  첫 설명입니다.\n")
        native("part-2", VoiceTutorTranscriptRole.USER, 102, text = "마지막 설명입니다.  ")

        assertThat(adapter.transcript(7, "owned", 1000).map { it.providerItemId }).doesNotContain("part-2")
        val rows = adapter.canonicalAnswerTurns(7, "owned", "part-2", "question", 2)
        assertThat(rows.map { it.providerItemId }).containsExactly("question", "part-1", "part-2")
        assertThat(rows.map { it.sequenceNumber }).containsExactly(100L, 101L, 102L)
        assertThat(rows.map { it.transcript }).containsExactly("원래 도착한 문제입니다.", "  첫 설명입니다.\n", "마지막 설명입니다.  ")
    }

    @Test
    fun `canonical answer lookup rejects wrong owner missing anchors and terminal sessions`() = runBlocking<Unit> {
        native("question", VoiceTutorTranscriptRole.TUTOR, 1)
        native("answer", VoiceTutorTranscriptRole.USER, 2)
        assertThat(adapter.canonicalAnswerTurns(8, "owned", "answer", "question", 2)).isEmpty()
        assertThat(adapter.canonicalAnswerTurns(7, "foreign", "answer", "question", 2)).isEmpty()
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "missing", "question", 2)).isEmpty()
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "answer", "missing", 2)).isEmpty()
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "question", "answer", 2)).isEmpty()
        assertThat(adapter.excludeCanonicalQuestionTurns(8, "owned", listOf("question", "answer"))).isFalse()
        for (status in listOf("ENDING", "COMPLETED", "FAILED")) {
            database.sql("update voice_tutor_sessions set status = :status where id = 'owned'")
                .bind("status", status).fetch().rowsUpdated().awaitSingle()
            assertThat(adapter.canonicalAnswerTurns(7, "owned", "answer", "question", 2)).isEmpty()
            assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", listOf("question", "answer"))).isFalse()
        }
        assertThat(adapter.transcript(7, "owned", 4000).all { it.postCallEvidence }).isTrue()
    }

    @Test
    fun `canonical answer lookup and exclusion reject mixed revisions and intervening tutor output`() = runBlocking<Unit> {
        native("question", VoiceTutorTranscriptRole.TUTOR, 1)
        native("part-1", VoiceTutorTranscriptRole.USER, 2)
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "part-1", "question", 3)).isEmpty()
        native("part-2", VoiceTutorTranscriptRole.USER, 4, revision = 3)
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "part-2", "question", 2)).isEmpty()
        assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", listOf("question", "part-1", "part-2"))).isFalse()
        execute("update voice_tutor_transcript_turns set lesson_revision = 2 where provider_item_id = 'part-2'")
        native("intervening", VoiceTutorTranscriptRole.TUTOR, 3)
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "part-2", "question", 2)).isEmpty()
        assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", listOf("question", "part-1", "part-2"))).isFalse()
        assertThat(adapter.transcript(7, "owned", 4000).all { it.postCallEvidence }).isTrue()
    }

    @Test
    fun `newer learner input invalidates both a previously read answer and its exclusion`() = runBlocking<Unit> {
        native("question", VoiceTutorTranscriptRole.TUTOR, 1)
        native("answer", VoiceTutorTranscriptRole.USER, 2)
        val original = adapter.canonicalAnswerTurns(7, "owned", "answer", "question", 2)
        assertThat(original).hasSize(2)
        native("newer", VoiceTutorTranscriptRole.USER, 3)

        assertThat(adapter.canonicalAnswerTurns(7, "owned", "answer", "question", 2)).isEmpty()
        assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", original.map { it.providerItemId })).isFalse()
        assertThat(adapter.transcript(7, "owned", 4000).all { it.postCallEvidence }).isTrue()
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "newer", "question", 2).map { it.providerItemId })
            .containsExactly("question", "answer", "newer")
    }

    @Test
    fun `canonical answer permits thirty two learner parts but never truncates a longer run`() = runBlocking<Unit> {
        native("question", VoiceTutorTranscriptRole.TUTOR, 1)
        for (part in 1..32) native("part-$part", VoiceTutorTranscriptRole.USER, part.toLong() + 1)
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "part-32", "question", 2)).hasSize(33)
        native("part-33", VoiceTutorTranscriptRole.USER, 34)
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "part-33", "question", 2)).isEmpty()
        assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", listOf("question") + (1..33).map { "part-$it" })).isFalse()
        assertThat(adapter.transcript(7, "owned", 40_000).all { it.postCallEvidence }).isTrue()
    }

    @Test
    fun `canonical exclusion validates every identity and the complete ordered run before changing any row`() = runBlocking<Unit> {
        native("question", VoiceTutorTranscriptRole.TUTOR, 1)
        native("part-1", VoiceTutorTranscriptRole.USER, 2)
        native("part-2", VoiceTutorTranscriptRole.USER, 3)
        for (ids in listOf(
            emptyList(), listOf("question"), listOf("question", "missing"),
            listOf("question", "part-2"), listOf("question", "part-1"),
            listOf("question", "part-2", "part-1"), listOf("question", "part-1", "part-1"),
            listOf("part-1", "part-2"), listOf("", "part-2"), listOf("x".repeat(192), "part-2"),
        )) {
            assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", ids)).isFalse()
            assertThat(adapter.transcript(7, "owned", 4000).all { it.postCallEvidence }).isTrue()
        }
    }

    @Test
    fun `canonical exclusion is idempotent retains source history and cannot be undone by a late native replay`() = runBlocking<Unit> {
        native("old", VoiceTutorTranscriptRole.TUTOR, 1)
        native("question", VoiceTutorTranscriptRole.TUTOR, 2)
        native("answer", VoiceTutorTranscriptRole.USER, 3, text = "  원래 답변입니다.\n")
        val before = adapter.transcript(7, "owned", 4000)
        val ids = listOf("question", "answer")
        assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", ids)).isTrue()
        assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", ids)).isTrue()
        val after = adapter.transcript(7, "owned", 4000)
        assertThat(after.map { it.copy(postCallEvidence = true) }).containsExactlyElementsOf(before)
        assertThat(after.map { it.postCallEvidence }).containsExactly(true, false, false)
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "answer", "question", 2).map { it.providerItemId })
            .containsExactlyElementsOf(ids)
        assertThat(adapter.appendTranscript(7, "owned", "answer", VoiceTutorTranscriptRole.USER, "  원래 답변입니다.\n", now,
            40_000, 2000, lessonRevision = 2, postCallEvidence = true, conversationSequence = 3)).isFalse()
        assertThat(adapter.transcript(7, "owned", 4000)).containsExactlyElementsOf(after)
    }

    @Test
    fun `interrupted tutor archives remain private ordered immutable history after terminal sessions`() = runBlocking<Unit> {
        native("before", VoiceTutorTranscriptRole.TUTOR, 1)
        native("latest-user", VoiceTutorTranscriptRole.USER, 3)
        val original = "  중단되기 전에 생성된 설명입니다.\n"
        assertThat(adapter.appendTranscript(7, "owned", "partial", VoiceTutorTranscriptRole.TUTOR, original, now,
            4000, 20, lessonRevision = 2, conversationSequence = 2, interrupted = true)).isTrue()
        assertThat(adapter.appendTranscript(7, "owned", "partial", VoiceTutorTranscriptRole.TUTOR, "늦은 전체 응답", now,
            4000, 20, lessonRevision = 3, postCallEvidence = true, conversationSequence = 4)).isFalse()
        val originalRows = adapter.transcript(7, "owned", 4000)
        assertThat(originalRows.map { it.providerItemId }).containsExactly("before", "partial", "latest-user")
        assertThat(originalRows.map { it.interrupted }).containsExactly(false, true, false)
        assertThat(originalRows[1].transcript).isEqualTo(original)
        assertThat(originalRows[1].postCallEvidence).isFalse()
        assertThat(originalRows[1].isStudyQuestion).isFalse()
        assertThat(originalRows[1].studyAnswerTurnId).isNull()
        assertThat(adapter.transcript(8, "owned", 4000)).isEmpty()
        for (status in listOf("ENDING", "COMPLETED", "FAILED")) {
            database.sql("update voice_tutor_sessions set status = :status where id = 'owned'")
                .bind("status", status).fetch().rowsUpdated().awaitSingle()
            assertThat(adapter.transcript(7, "owned", 4000)).containsExactlyElementsOf(originalRows)
        }
        assertThat(adapter.appendTranscript(7, "owned", "late", VoiceTutorTranscriptRole.TUTOR, original, now,
            4000, 20, lessonRevision = 2, conversationSequence = 4, interrupted = true)).isFalse()
    }

    @Test
    fun `interrupted archive cannot carry learning flags skip source ordering or escape session ownership`() = runBlocking<Unit> {
        assertThat(adapter.appendTranscript(8, "owned", "wrong-owner", VoiceTutorTranscriptRole.TUTOR, "내용", now,
            4000, 20, conversationSequence = 1, interrupted = true)).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "wrong-role", VoiceTutorTranscriptRole.USER, "내용", now,
            4000, 20, conversationSequence = 1, interrupted = true)).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "post-call", VoiceTutorTranscriptRole.TUTOR, "내용", now,
            4000, 20, conversationSequence = 1, postCallEvidence = true, interrupted = true)).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "question", VoiceTutorTranscriptRole.TUTOR, "내용", now,
            4000, 20, conversationSequence = 1, isStudyQuestion = true, interrupted = true)).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "feedback", VoiceTutorTranscriptRole.TUTOR, "내용", now,
            4000, 20, conversationSequence = 1, studyAnswerProviderItemId = "answer", interrupted = true)).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "unordered", VoiceTutorTranscriptRole.TUTOR, "내용", now,
            4000, 20, interrupted = true)).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "expired", VoiceTutorTranscriptRole.TUTOR, "내용", now.plusSeconds(3601),
            4000, 20, conversationSequence = 1, interrupted = true)).isFalse()
        assertThat(adapter.transcript(7, "owned", 4000)).isEmpty()
    }

    @Test
    fun `interrupted tutor stays an ordering barrier and cannot anchor canonical or legacy learning`() = runBlocking<Unit> {
        execute("insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at) values ('owned', 2, 42, current_timestamp)")
        assertThat(append("completed-question", VoiceTutorTranscriptRole.TUTOR, "완료된 문제", 2, true)).isTrue()
        assertThat(adapter.appendTranscript(7, "owned", "partial", VoiceTutorTranscriptRole.TUTOR, "중단된 설명", now,
            4000, 20, lessonRevision = 2, conversationSequence = 2, interrupted = true)).isTrue()
        native("answer", VoiceTutorTranscriptRole.USER, 3)
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "answer", "partial", 2)).isEmpty()
        assertThat(adapter.canonicalAnswerTurns(7, "owned", "answer", "completed-question", 2)).isEmpty()
        assertThat(adapter.excludeCanonicalQuestionTurns(7, "owned", listOf("partial", "answer"))).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "promoted", VoiceTutorTranscriptRole.USER, "답변", now,
            4000, 20, lessonRevision = 2, studyQuestionProviderItemId = "completed-question",
            studyAnswerProviderItemIds = listOf("promoted"))).isFalse()
        // Even legacy or faulty semantic flags cannot upgrade an interrupted row.
        execute("update voice_tutor_transcript_turns set is_study_question = true, post_call_evidence = true where provider_item_id = 'partial'")
        assertThat(adapter.hasPostCallLearningCandidates(7, "owned")).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "promoted", VoiceTutorTranscriptRole.USER, "답변", now,
            4000, 20, lessonRevision = 2, studyQuestionProviderItemId = "partial",
            studyAnswerProviderItemIds = listOf("promoted"))).isFalse()
        execute("update voice_tutor_transcript_turns set study_question_turn_id = (select id from voice_tutor_transcript_turns where provider_item_id = 'partial') where provider_item_id = 'answer'")
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()
        assertThat(adapter.appendTranscript(7, "owned", "feedback", VoiceTutorTranscriptRole.TUTOR, "평가", now,
            4000, 20, lessonRevision = 2, studyAnswerProviderItemId = "answer")).isTrue()
        // Invalid feedback provenance leaves the original private chat visible without promoting it.
        val feedback = adapter.transcript(7, "owned", 4000).single { it.providerItemId == "feedback" }
        assertThat(feedback.transcript).isEqualTo("평가")
        assertThat(feedback.studyAnswerTurnId).isNull()
        assertThat(feedback.isStudyQuestion).isFalse()
        assertThat(adapter.hasVerifiedLearningExchange(7, "owned")).isFalse()
    }

    private suspend fun native(
        id: String,
        role: VoiceTutorTranscriptRole,
        sequence: Long,
        revision: Long = 2,
        text: String = "원문 $id",
    ) {
        assertThat(adapter.appendTranscript(7, "owned", id, role, text, now, 1_000_000, 2000,
            lessonRevision = revision, postCallEvidence = true, conversationSequence = sequence)).isTrue()
    }

    private suspend fun append(
        id: String,
        role: VoiceTutorTranscriptRole,
        text: String,
        revision: Long,
        isStudyQuestion: Boolean = false,
    ): Boolean = adapter.appendTranscript(
        7, "owned", id, role, text, now, 4_000, 20,
        lessonRevision = revision,
        isStudyQuestion = isStudyQuestion,
    )

    private suspend fun seedSession(id: String, userId: Long, studyId: Long) {
        database.sql("""
            insert into voice_tutor_sessions
                (id, user_id, study_id, accepted_study_id, period_started_at, period_ends_at, hard_ends_at, created_at, updated_at)
            values (:id, :user, :study, :study, :now, :until, :until, :now, :now)
        """.trimIndent()).bind("id", id).bind("user", userId).bind("study", studyId)
            .bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
            .bind("until", LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }

    private suspend fun count(sql: String): Long = database.sql(sql)
        .map { row, _ -> row.get(0, java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
}
