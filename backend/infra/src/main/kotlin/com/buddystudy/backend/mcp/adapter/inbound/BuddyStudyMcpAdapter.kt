package com.buddystudy.backend.mcp.adapter.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.buddystudy.backend.learningcontext.application.model.LearningContextPatchCommand
import com.buddystudy.backend.mcp.application.port.inbound.BuddyStudyMcpUseCase
import com.buddystudy.backend.study.application.port.inbound.CreateRootStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicsCommand
import com.buddystudy.backend.study.application.port.inbound.ExpectedStudyMetadata
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyCommand
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration
import java.time.Instant

@Component
class BuddyStudyMcpAdapter(
    private val buddyStudy: BuddyStudyMcpUseCase,
    private val objectMapper: ObjectMapper,
    private val exchangeLogger: McpExchangeLogger = McpExchangeLogger(objectMapper),
) : BuddyStudyMcpPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val toolSpecifications by lazy {
        listOf(
            tool(
                name = "get_my_context",
                title = "Get my BuddyStudy context",
                description = "Return the authenticated user's private profile, resume Markdown, and interests.",
                schema = objectSchema(),
                readOnly = true,
            ) { principal, _ ->
                buddyStudy.getMyContext(principal)
            },
            tool(
                name = "update_my_learning_context",
                title = "Update my resume and interests",
                description = "Patch the authenticated user's private learning context. Omit a field to preserve it; use an empty string or empty list to clear it.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "resume_markdown" to stringProperty(
                            description = "Resume or career context in Markdown. Empty text clears it.",
                            maxLength = 50_000,
                        ),
                        "interests" to arrayProperty(
                            description = "Learning or career interests. Empty list clears them.",
                            item = stringProperty(maxLength = 100),
                            maxItems = 50,
                        ),
                    ),
                ),
                readOnly = false,
                destructive = true,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.updateMyLearningContext(
                    principal,
                    LearningContextPatchCommand(
                        resumeMarkdown = args.optionalString("resume_markdown"),
                        interests = args.optionalStringList("interests"),
                    ),
                )
            },
            tool(
                name = "list_studies",
                title = "List my studies",
                description = "Return a bounded page of the authenticated user's study tree nodes and pending questions. Set parent_study_id to list only the direct saved children of an owned study, ordered by sibling position and ID; omit it to search all owned nodes.",
                schema = pagedSchema(
                    additional = linkedMapOf(
                        "parent_study_id" to idProperty("Optional owned parent study ID. Returns direct children only, not the parent or deeper descendants."),
                        "query" to stringProperty("Optional topic search.", maxLength = 200),
                        "language" to languageProperty(),
                    ),
                    maximum = 500,
                    defaultLimit = 100,
                ),
                readOnly = true,
            ) { principal, args ->
                val parentStudyId = args.optionalLong("parent_study_id")
                if (parentStudyId == null) {
                    buddyStudy.listStudies(
                        principal,
                        args.int("limit", 100),
                        args.int("offset", 0),
                        args.optionalString("query"),
                        args.string("language", "ko"),
                    )
                } else {
                    buddyStudy.listStudies(
                        principal,
                        args.int("limit", 100),
                        args.int("offset", 0),
                        args.optionalString("query"),
                        args.string("language", "ko"),
                        parentStudyId,
                    )
                }
            },
            tool(
                name = "get_study",
                title = "Get one study",
                description = "Return one owned study tree node with its current pending and latest completed question.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "study_id" to idProperty("Owned study node ID."),
                        "language" to languageProperty(),
                    ),
                    required = listOf("study_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getStudy(principal, args.long("study_id"), args.string("language", "ko"))
            },
            tool(
                name = "update_study",
                title = "Update a study name or level",
                description = "Patch only the name and/or configured difficulty of one existing owned study node after the user asks to change it. Include at least one of topic or difficulty_level; omitted fields stay unchanged and null is not accepted. The node ID, parent, schedule, activation, model, notification preferences and existing question/answer or voice history are never replaced. This does not create a study, generate a question, grade an answer or consume question quota.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "study_id" to idProperty("Exact owned study node ID to update; never identify a target by name alone."),
                        "topic" to stringProperty("New study topic. Omit to preserve the current name.", minLength = 1, maxLength = 255),
                        "difficulty_level" to integerProperty("New configured difficulty from 1 to 10. Omit to preserve the current level.", 1, 10),
                    ),
                    required = listOf("study_id"),
                ).toMutableMap().apply { put("minProperties", 2) },
                readOnly = false,
                destructive = true,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.updateStudy(
                    principal,
                    args.long("study_id"),
                    UpdateStudyCommand(
                        topic = args.optionalString("topic"),
                        difficultyLevel = args.optionalInt("difficulty_level"),
                        expectedCurrent = args.voiceStudyMetadataExpectation(),
                    ),
                )
            },
            tool(
                name = "create_root_study",
                title = "Create a new root study",
                description = "Create a root study without replacing any existing study settings. A normalized matching root is returned unchanged with created=false; a matching child topic is a conflict. The new root uses the normal enabled 15-minute schedule, creates no question, and consumes no question quota.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "topic" to stringProperty("Root study topic.", minLength = 1, maxLength = 255),
                        "difficulty_level" to integerProperty("Difficulty from 1 to 10.", 1, 10, 5),
                    ),
                    required = listOf("topic"),
                ),
                readOnly = false,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.createRootStudy(
                    principal,
                    CreateRootStudyCommand(
                        topic = args.string("topic"),
                        difficultyLevel = args.int("difficulty_level", 5),
                    ),
                )
            },
            tool(
                name = "create_study_topic",
                title = "Create a child study topic",
                description = "Add one selected child topic under an owned study node through descendant depth 4 (root depth zero). Existing deeper topics are preserved. This is separate from root-study creation and question generation, and consumes no question quota.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "parent_study_id" to idProperty("Parent study node ID."),
                        "topic" to stringProperty("Child topic.", minLength = 1, maxLength = 255),
                        "sort_order" to integerProperty("Sibling sort order.", 0, 10_000, 0),
                        "difficulty_level" to integerProperty("Difficulty from 1 to 10.", 1, 10, 5),
                        "active_for_questions" to booleanProperty("Whether this topic participates in question generation.", true),
                    ),
                    required = listOf("parent_study_id", "topic"),
                ),
                readOnly = false,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.createStudyTopic(
                    principal,
                    args.long("parent_study_id"),
                    CreateStudyTopicCommand(
                        topic = args.string("topic"),
                        sortOrder = args.int("sort_order", 0),
                        difficultyLevel = args.int("difficulty_level", 5),
                        activeForQuestions = args.boolean("active_for_questions", true),
                        inheritRootDifficulty = args.boolean(BuddyStudyMcpPort.VOICE_INHERIT_ROOT_DIFFICULTY_ARGUMENT, false),
                    ),
                )
            },
            tool(
                name = "suggest_study_topics",
                title = "Recommend child study topics",
                description = "Recommend a bounded list of child topics under one owned study node, using the shared catalog before generating missing suggestions. Supports four descendant levels below root depth zero and returns depth/maxDepth metadata. This does not create user studies, select the lesson focus, generate questions or consume question quota. Offer the recommendations for explicit learner selection, create only selected topics, then lazily request suggestions for a selected saved child if the learner wants to go deeper.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "parent_study_id" to idProperty("Owned parent study node ID whose direct children are being planned."),
                        "count" to integerProperty("Maximum number of topic suggestions.", 1, 10, 5),
                    ),
                    required = listOf("parent_study_id"),
                ),
                // A missing catalog branch may be generated and cached, while
                // the learner's saved tree and question quota stay untouched.
                readOnly = false,
                destructive = false,
                idempotent = false,
            ) { principal, args ->
                val count = args.int("count", 5)
                if (count !in 1..10) throw McpArgumentException("count must be between 1 and 10.")
                buddyStudy.suggestStudyTopics(principal, args.long("parent_study_id"), count)
            },
            tool(
                name = "create_study_topics",
                title = "Create selected child study topics",
                description = "Atomically save only the explicitly selected direct child topics under one owned parent. Accepts 1 to 10 distinct topics through descendant depth 4 (root depth zero), rejects the entire selection if any topic conflicts, and returns existing same-parent topics unchanged. Creates no deeper descendants or questions and consumes no question quota.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "parent_study_id" to idProperty("Owned parent study node ID."),
                        "topics" to arrayProperty(
                            "Explicitly selected direct child topics.",
                            stringProperty(minLength = 1, maxLength = 255),
                            maxItems = 10,
                        ).toMutableMap().apply {
                            put("minItems", 1)
                            put("uniqueItems", true)
                        },
                        "difficulty_level" to integerProperty("Difficulty from 1 to 10.", 1, 10, 5),
                    ),
                    required = listOf("parent_study_id", "topics"),
                ),
                readOnly = false,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.createStudyTopics(
                    principal,
                    args.long("parent_study_id"),
                    CreateStudyTopicsCommand(
                        topics = args.optionalStringList("topics") ?: throw McpArgumentException("topics is required."),
                        difficultyLevel = args.int("difficulty_level", 5),
                        expectedParent = args.voiceStudyMetadataExpectation(),
                        inheritRootDifficulty = args.boolean(BuddyStudyMcpPort.VOICE_INHERIT_ROOT_DIFFICULTY_ARGUMENT, false),
                        curriculumTerminalByTopic = args.curriculumTerminals(),
                    ),
                )
            },
            tool(
                name = "delete_study",
                title = "Delete a study subtree",
                description = "Permanently delete an owned study and every descendant topic. Existing question records are retained without a study link and original voice learning history is retained. Set confirm=true only after explicit user confirmation. Optionally pass the exact confirmed subtree IDs in expected_study_ids; if the subtree has changed, nothing is deleted and fresh confirmation is required.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "study_id" to idProperty("Root of the subtree to delete."),
                        "confirm" to booleanProperty("Must be true after explicit user confirmation."),
                        "expected_study_ids" to arrayProperty(
                            "Exact confirmed subtree IDs including study_id. A mismatch prevents deletion.",
                            idProperty("Confirmed study node ID."),
                            maxItems = 128,
                        ).toMutableMap().apply {
                            put("minItems", 1)
                            put("uniqueItems", true)
                        },
                    ),
                    required = listOf("study_id", "confirm"),
                ),
                readOnly = false,
                destructive = true,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.deleteStudy(principal, args.long("study_id"), args.boolean("confirm"), args.optionalLongList("expected_study_ids"))
            },
            tool(
                name = "list_pending_questions",
                title = "List pending questions",
                description = "Return a bounded page of the authenticated user's active unanswered or grading questions. Set study_id to return only that exact owned topic, not its descendants. Reuse an existing unanswered question before requesting a new one; never replace an answer draft.",
                schema = pagedSchema(
                    additional = linkedMapOf("study_id" to idProperty("Optional exact owned study topic filter, excluding descendants.")),
                    maximum = 100, defaultLimit = 30,
                ),
                readOnly = true,
            ) { principal, args ->
                val studyId = args.optionalLong("study_id")
                if (studyId == null) buddyStudy.listPendingQuestions(principal, args.int("limit", 30), args.int("offset", 0))
                else buddyStudy.listPendingQuestions(principal, args.int("limit", 30), args.int("offset", 0), studyId)
            },
            tool(
                name = "skip_question",
                title = "Skip an unanswered question",
                description = "Skip the exact owned unanswered question only when the user asks to skip it. Does not generate another question, consume question quota, or erase an answer draft. Submitted, grading and completed questions cannot be skipped. Repeating a successful skip is safe; request_question is a separate explicit action.",
                schema = objectSchema(
                    properties = linkedMapOf("record_id" to idProperty("Exact owned unanswered question record ID to skip.")),
                    required = listOf("record_id"),
                ),
                readOnly = false,
                destructive = true,
                idempotent = true,
            ) { principal, args ->
                buddyStudy.skipQuestion(principal, args.long("record_id"))
            },
            tool(
                name = "request_question",
                title = "Request a study question",
                description = "Queue question generation for one owned study topic only when the user requests a new question. Read list_pending_questions for that exact study_id first and reuse its unanswered question unless the user explicitly skips it. Uses the existing question quota and never replaces an answer draft. Returns a correlation ID immediately; poll get_question_process until terminal=true.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "study_id" to idProperty("Study topic ID."),
                        "idempotency_key" to stringProperty(
                            "Stable caller-generated key reused when retrying the same request.",
                            minLength = 1,
                            maxLength = 100,
                        ),
                    ),
                    required = listOf("study_id", "idempotency_key"),
                ),
                readOnly = false,
                idempotent = true,
                openWorld = true,
            ) { principal, args ->
                buddyStudy.requestQuestion(principal, args.long("study_id"), args.string("idempotency_key"))
            },
            tool(
                name = "get_question_process",
                title = "Get question generation status",
                description = "Get the authenticated user's asynchronous question-generation state and generated question when complete.",
                schema = correlationSchema(),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getQuestionProcess(principal, args.string("correlation_id"))
            },
            tool(
                name = "submit_answer",
                title = "Submit an answer for grading",
                description = "Persist an answer and queue asynchronous grading. Returns a grading correlation ID; poll get_grading_process and then get_record for score and feedback.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "record_id" to idProperty("Pending question record ID."),
                        "answer" to stringProperty("The user's answer. Do not invent or rewrite it.", minLength = 1, maxLength = 50_000),
                        "source_language" to languageProperty("Optional language of the answer."),
                    ),
                    required = listOf("record_id", "answer"),
                ),
                readOnly = false,
                destructive = true,
                idempotent = false,
                openWorld = true,
            ) { principal, args ->
                buddyStudy.submitAnswer(
                    principal,
                    args.long("record_id"),
                    args.string("answer"),
                    args.optionalString("source_language"),
                )
            },
            tool(
                name = "get_grading_process",
                title = "Get answer grading status",
                description = "Return grading state and durable progress events. Poll after pollAfterMs until terminal=true.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "correlation_id" to stringProperty("Grading correlation ID.", minLength = 1, maxLength = 100),
                        "after_event_id" to integerProperty("Return events after this cursor.", 0, Long.MAX_VALUE, 0),
                    ),
                    required = listOf("correlation_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getGradingProcess(
                    principal,
                    args.string("correlation_id"),
                    args.long("after_event_id", 0),
                )
            },
            tool(
                name = "list_records",
                title = "List study records",
                description = "Return a bounded page of completed study records. recordType distinguishes QUESTION from VOICE_TUTOR; both use the same canonical record ID as the Records tab and public comments. QUESTION may include gradingResult. VOICE_TUTOR uses voiceRecord for the optional spoken score and learning feedback, never a fabricated grading result. Reading history is not a new answer or permission to act.",
                schema = pagedSchema(
                    additional = linkedMapOf(
                        "query" to stringProperty("Optional question, answer, or topic search.", maxLength = 200),
                        "study_id" to idProperty("Optional study topic filter."),
                        "language" to languageProperty(),
                        "view" to viewProperty(),
                    ),
                    maximum = 100,
                    defaultLimit = 30,
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.listRecords(
                    principal,
                    args.int("limit", 30),
                    args.int("offset", 0),
                    args.optionalString("query"),
                    args.optionalLong("study_id"),
                    args.string("language", "ko"),
                    args.string("view", "localized"),
                )
            },
            tool(
                name = "get_record",
                title = "Get a study record",
                description = "Return one owned canonical record. recordType is QUESTION or VOICE_TUTOR. Questions may include gradingResult and rubric details; voiceRecord contains the original exchange's supported spoken assessment and exploration, with nullable score. Use the common record.id or voiceRecord.recordId from node history, not the legacy voice evidence ID. This read never submits an answer or changes the record.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "record_id" to idProperty("Owned canonical record ID for either record type."),
                        "language" to languageProperty(),
                        "view" to viewProperty(),
                    ),
                    required = listOf("record_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getRecord(
                    principal,
                    args.long("record_id"),
                    args.string("language", "ko"),
                    args.string("view", "localized"),
                )
            },
            tool(
                name = "list_study_learning_records",
                title = "List a study node's learning history",
                description = "Return a cursor page of completed QUESTION and VOICE_TUTOR records for an owned study node; subtree explicitly includes its saved descendants. record is the same canonical record as the Records tab, with a common ID. Legacy voiceRecord also includes owner-only session and turn evidence; its recordId points to the common record, while id is the legacy evidence ID. This is past learning evidence, not a new answer or permission to act. Start with a small page; use nextCursor with the same study_id and scope while hasMore is true.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "study_id" to idProperty("Owned study node ID whose learning history to read."),
                        "scope" to stringProperty("node reads only this node; subtree includes saved descendants.", values = listOf("node", "subtree"), default = "node"),
                        "limit" to integerProperty("Maximum learning records to return. Prefer a small page for voice calls.", 1, 30, 5),
                        "cursor" to stringProperty("Opaque nextCursor from the same study and scope; omit on the first page.", minLength = 1, maxLength = 512),
                        "language" to languageProperty(),
                        "view" to viewProperty(default = "original"),
                    ),
                    required = listOf("study_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.listStudyLearningRecords(
                    principal,
                    args.long("study_id"),
                    args.string("scope", "node"),
                    args.int("limit", 5),
                    args.optionalString("cursor"),
                    args.string("language", "ko"),
                    args.string("view", "original"),
                )
            },
            tool(
                name = "get_voice_learning_record",
                title = "Get one private voice learning exchange",
                description = "Return the owner-only evidence for one persisted voice learning exchange, including frozen study level and exact session/turn evidence. Use the legacy numeric voiceRecord.id from list_study_learning_records, not its canonical recordId or the prefixed envelope id. For the shared Records-tab representation use get_record with recordId. This compatibility read never grades, publishes or changes an answer.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "record_id" to idProperty("Owned voiceRecord.id, not a question record ID or the voice: prefixed envelope ID."),
                        "language" to languageProperty(),
                        "view" to viewProperty(default = "original"),
                    ),
                    required = listOf("record_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getVoiceLearningRecord(
                    principal,
                    args.long("record_id"),
                    args.string("language", "ko"),
                    args.string("view", "original"),
                )
            },
            tool(
                name = "get_topic_stats",
                title = "Get topic-level statistics",
                description = "Return paginated topic-first score, correctness, and level-range statistics. Do not infer a global average across unrelated topics.",
                schema = pagedSchema(
                    additional = linkedMapOf(
                        "query" to stringProperty("Optional topic search.", maxLength = 200),
                        "period" to stringProperty(
                            "Optional preset period.",
                            values = listOf("all", "today", "last7", "last30", "last90"),
                        ),
                        "start_at" to instantProperty("Optional inclusive UTC start timestamp."),
                        "end_at" to instantProperty("Optional exclusive UTC end timestamp."),
                    ),
                    maximum = 50,
                    defaultLimit = 20,
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getTopicStats(
                    principal,
                    args.int("limit", 20),
                    args.int("offset", 0),
                    args.optionalString("query"),
                    args.optionalString("period"),
                    args.optionalInstant("start_at"),
                    args.optionalInstant("end_at"),
                )
            },
            tool(
                name = "get_study_growth",
                title = "Get study-tree growth",
                description = "Return root and topic-node growth, trends, and learning profile values for an optional UTC interval.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "start_at" to instantProperty("Optional inclusive UTC start timestamp."),
                        "end_at" to instantProperty("Optional exclusive UTC end timestamp."),
                    ),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getStudyGrowth(principal, args.optionalInstant("start_at"), args.optionalInstant("end_at"))
            },
            tool(
                name = "list_voice_tutor_sessions",
                title = "List my Voice Tutor sessions",
                description = "Return a cursor-paginated list of the authenticated user's persisted Voice Tutor learning sessions.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "limit" to integerProperty("Maximum sessions to return.", 1, 100, 30),
                        "cursor" to stringProperty("Opaque cursor returned by the previous page.", maxLength = 512),
                    ),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.listVoiceTutorSessions(
                    principal,
                    args.int("limit", 30),
                    args.optionalString("cursor"),
                )
            },
            tool(
                name = "get_voice_tutor_session",
                title = "Get one Voice Tutor learning result",
                description = "Return one owned Voice Tutor session with transcript turns, metered duration, and its generated learning summary.",
                schema = objectSchema(
                    properties = linkedMapOf(
                        "session_id" to stringProperty("Owned Voice Tutor session UUID.", minLength = 36, maxLength = 36),
                    ),
                    required = listOf("session_id"),
                ),
                readOnly = true,
            ) { principal, args ->
                buddyStudy.getVoiceTutorSession(principal, args.string("session_id"))
            },
            tool(
                name = "get_voice_tutor_quota",
                title = "Get my Voice Tutor quota",
                description = "Return server-authoritative Voice Tutor eligibility, monthly seconds, reset time, and any active session.",
                schema = objectSchema(),
                readOnly = true,
            ) { principal, _ ->
                buddyStudy.getVoiceTutorQuota(principal)
            },
        )
    }

    private val resourceSpecifications by lazy {
        listOf(
            resource(
                uri = "buddystudy://me/context",
                name = "my-buddystudy-context",
                title = "My BuddyStudy context",
                description = "Private profile, resume Markdown, and interests for the authenticated user.",
            ) { principal -> buddyStudy.getMyContext(principal) },
            resource(
                uri = "buddystudy://studies",
                name = "my-studies",
                title = "My study tree",
                description = "The first 200 owned study tree nodes and their current question state.",
            ) { principal -> buddyStudy.listStudies(principal, 200, 0, null, "ko") },
            resource(
                uri = "buddystudy://records/recent",
                name = "my-recent-records",
                title = "My recent study records",
                description = "The 30 most recent completed records with grading feedback and scores.",
            ) { principal -> buddyStudy.listRecords(principal, 30, 0, null, null, "ko", "localized") },
            resource(
                uri = "buddystudy://voice-tutor/sessions/recent",
                name = "my-recent-voice-tutor-sessions",
                title = "My recent Voice Tutor sessions",
                description = "The 30 most recent persisted Voice Tutor sessions and their result status.",
            ) { principal -> buddyStudy.listVoiceTutorSessions(principal, 30, null) },
            resource(
                uri = "buddystudy://voice-tutor/quota",
                name = "my-voice-tutor-quota",
                title = "My Voice Tutor quota",
                description = "Server-authoritative Voice Tutor eligibility and current monthly usage.",
            ) { principal -> buddyStudy.getVoiceTutorQuota(principal) },
        )
    }

    override fun tools(): List<McpStatelessServerFeatures.AsyncToolSpecification> = toolSpecifications

    override fun resources(): List<McpStatelessServerFeatures.AsyncResourceSpecification> = resourceSpecifications

    private fun tool(
        name: String,
        title: String,
        description: String,
        schema: Map<String, Any>,
        readOnly: Boolean,
        destructive: Boolean = false,
        idempotent: Boolean = readOnly,
        openWorld: Boolean = false,
        handler: suspend (Principal, Arguments) -> Any,
    ): McpStatelessServerFeatures.AsyncToolSpecification {
        val definition = McpSchema.Tool.builder(name, schema)
            .title(title)
            .description(description)
            .annotations(
                McpSchema.ToolAnnotations.builder()
                    .title(title)
                    .readOnlyHint(readOnly)
                    .destructiveHint(destructive)
                    .idempotentHint(idempotent)
                    .openWorldHint(openWorld)
                    .build(),
            )
            .build()
        return McpStatelessServerFeatures.AsyncToolSpecification(definition) { context, request ->
            val operation = mono { handler(principal(context), Arguments(request.arguments().orEmpty())) }
            val result = if (readOnly) {
                operation.retryWhen(
                    Retry.fixedDelay(1, Duration.ofMillis(250))
                        .filter(::canRetryRead)
                        .doBeforeRetry { failure -> logFailure(name, failure.failure(), retrying = true) }
                        .onRetryExhaustedThrow { _, failure -> failure.failure() },
                ).timeout(READ_TIMEOUT, Mono.defer {
                    // The complete read, including its single retry, must finish
                    // before the native tool's 15-second boundary. A blocked DB
                    // queue must become a real error result, not a pending turn.
                    log.warn("mcp_read_timed_out operation={} timeoutSeconds={}", name, READ_TIMEOUT.seconds)
                    Mono.error(ApiRuntimeException(ApiErrorCode.SERVER_BUSY))
                })
            } else operation
            val completed = result
                .map(::successResult)
                .onErrorResume { error ->
                    if (isCancellation(error)) Mono.error(error)
                    else Mono.just(errorResult(name, error))
                }
            if (context.get(BuddyStudyMcpPort.SUPPRESS_EXCHANGE_LOG_CONTEXT_KEY) == true) completed
            else exchangeLogger.observeMono(
                logContext(context), "tools/call", name,
                mapOf("name" to name, "arguments" to request.arguments().orEmpty()),
                response = { value -> McpExchangeResponse(value.structuredContent() ?: mapOf("content" to value.content()), value.isError() == true) },
            ) { completed }
        }
    }

    private fun resource(
        uri: String,
        name: String,
        title: String,
        description: String,
        handler: suspend (Principal) -> Any,
    ): McpStatelessServerFeatures.AsyncResourceSpecification {
        val definition = McpSchema.Resource.builder(uri, name)
            .title(title)
            .description(description)
            .mimeType(APPLICATION_JSON)
            .build()
        return McpStatelessServerFeatures.AsyncResourceSpecification(definition) { context, _ ->
            val completed = mono { handler(principal(context)) }
                .map { value -> resourceResult(uri, value) }
                .onErrorResume { error ->
                    if (isCancellation(error)) Mono.error(error)
                    else Mono.just(resourceErrorResult(uri, error))
                }
            if (context.get(BuddyStudyMcpPort.SUPPRESS_EXCHANGE_LOG_CONTEXT_KEY) == true) completed
            else exchangeLogger.observeMono(
                logContext(context), "resources/read", name, mapOf("uri" to uri),
                response = { value ->
                    val body = (value.contents().firstOrNull() as? McpSchema.TextResourceContents)?.text()
                    McpExchangeResponse(body ?: mapOf("contents" to value.contents()))
                },
            ) { completed }
        }
    }

    private fun logContext(context: McpTransportContext) = McpExchangeContext(
        userId = (context.get(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY) as? Principal)?.userId,
        transport = "http",
        parentRequestId = context.get(BuddyStudyMcpPort.PARENT_REQUEST_ID_CONTEXT_KEY) as? String,
    )

    private fun principal(context: McpTransportContext): Principal =
        context.get(BuddyStudyMcpPort.PRINCIPAL_CONTEXT_KEY) as? Principal
            ?: throw AccessDeniedException("Authenticated MCP principal is missing.")

    private fun successResult(value: Any): McpSchema.CallToolResult {
        val structured = objectMapper.convertValue(value, Any::class.java)
        return McpSchema.CallToolResult.builder()
            .addTextContent(objectMapper.writeValueAsString(value))
            .structuredContent(structured)
            .isError(false)
            .build()
    }

    private fun errorResult(toolName: String, error: Throwable): McpSchema.CallToolResult {
        val payload = errorPayload(toolName, error)
        return McpSchema.CallToolResult.builder()
            .addTextContent(objectMapper.writeValueAsString(payload))
            .structuredContent(payload)
            .isError(true)
            .build()
    }

    private fun resourceResult(uri: String, value: Any): McpSchema.ReadResourceResult =
        textResource(uri, objectMapper.writeValueAsString(value))

    private fun resourceErrorResult(uri: String, error: Throwable): McpSchema.ReadResourceResult =
        textResource(uri, objectMapper.writeValueAsString(errorPayload("resources/read", error)))

    private fun textResource(uri: String, text: String): McpSchema.ReadResourceResult =
        McpSchema.ReadResourceResult.builder(
            listOf(
                McpSchema.TextResourceContents.builder(uri, text)
                    .mimeType(APPLICATION_JSON)
                    .build(),
            ),
        ).build()

    private fun errorPayload(operation: String, error: Throwable): Map<String, Any> {
        val details = when (error) {
            is ApiRuntimeException -> Triple(error.errorCode.name, error.status.value(), error.message)
            is McpArgumentException -> Triple("VALIDATION_ERROR", 422, error.message ?: "Invalid tool arguments.")
            is AccessDeniedException -> Triple("PERMISSION_DENIED", 403, "Permission is denied.")
            else -> {
                logFailure(operation, error, retrying = false)
                Triple("INTERNAL_SERVER_ERROR", 500, "The MCP operation could not be completed.")
            }
        }
        return linkedMapOf(
            "error" to linkedMapOf(
                "code" to details.first,
                "status" to details.second,
                "message" to details.third,
            ),
        )
    }

    private class Arguments(private val values: Map<String, Any>) {
        fun curriculumTerminals(): Map<String, Boolean> {
            val raw = values[BuddyStudyMcpPort.VOICE_CURRICULUM_TERMINALS_ARGUMENT] ?: return emptyMap()
            val entries = raw as? Map<*, *> ?: throw McpArgumentException("Invalid internal curriculum metadata.")
            if (entries.size > 10 || entries.any { (key, value) -> key !is String || key.isBlank() || key.length > 255 || value !is Boolean })
                throw McpArgumentException("Invalid internal curriculum metadata.")
            return entries.entries.associate { it.key as String to it.value as Boolean }
        }

        fun voiceStudyMetadataExpectation(): ExpectedStudyMetadata? {
            val names = setOf(
                BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT,
                BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT,
                BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT,
            )
            val supplied = names.count(values::containsKey)
            if (supplied == 0) return null
            if (supplied != names.size) {
                throw McpArgumentException("The internal voice study metadata fence is incomplete.")
            }
            val topic = string(BuddyStudyMcpPort.VOICE_EXPECTED_TOPIC_ARGUMENT)
            val difficulty = optionalInt(BuddyStudyMcpPort.VOICE_EXPECTED_DIFFICULTY_ARGUMENT)
                ?: throw McpArgumentException("The internal expected study level is required.")
            val parentMarker = optionalLong(BuddyStudyMcpPort.VOICE_EXPECTED_PARENT_ARGUMENT)
                ?: throw McpArgumentException("The internal expected study parent is required.")
            if (topic.isBlank() || topic != topic.trim() || topic.length > 255 ||
                difficulty !in 1..10 || parentMarker < 0
            ) {
                throw McpArgumentException("The internal voice study metadata fence is invalid.")
            }
            return ExpectedStudyMetadata(
                parentStudyId = parentMarker.takeIf { it > 0 },
                topic = topic,
                difficultyLevel = difficulty,
            )
        }

        fun string(name: String): String =
            optionalString(name) ?: throw McpArgumentException("$name is required.")

        fun string(name: String, default: String): String = optionalString(name) ?: default

        fun optionalString(name: String): String? = when (val value = values[name]) {
            null -> null
            is String -> value
            else -> throw McpArgumentException("$name must be a string.")
        }

        fun int(name: String, default: Int): Int = optionalInt(name) ?: default

        fun optionalInt(name: String): Int? = optionalNumber(name)?.let { value ->
            try {
                java.math.BigDecimal(value.toString()).intValueExact()
            } catch (_: IllegalArgumentException) {
                throw McpArgumentException("$name must be an integer.")
            } catch (_: ArithmeticException) {
                throw McpArgumentException("$name must be an integer.")
            }
        }

        fun long(name: String): Long =
            optionalLong(name) ?: throw McpArgumentException("$name is required.")

        fun long(name: String, default: Long): Long = optionalLong(name) ?: default

        fun optionalLong(name: String): Long? = optionalNumber(name)?.let { value ->
            try {
                java.math.BigDecimal(value.toString()).longValueExact()
            } catch (_: IllegalArgumentException) {
                throw McpArgumentException("$name must be an integer.")
            } catch (_: ArithmeticException) {
                throw McpArgumentException("$name must be an integer.")
            }
        }

        fun boolean(name: String): Boolean =
            optionalBoolean(name) ?: throw McpArgumentException("$name is required.")

        fun boolean(name: String, default: Boolean): Boolean = optionalBoolean(name) ?: default

        fun optionalStringList(name: String): List<String>? = when (val value = values[name]) {
            null -> null
            is List<*> -> value.mapIndexed { index, item ->
                item as? String ?: throw McpArgumentException("$name[$index] must be a string.")
            }
            else -> throw McpArgumentException("$name must be an array of strings.")
        }

        fun optionalLongList(name: String): List<Long>? = when (val value = values[name]) {
            null -> if (values.containsKey(name)) throw McpArgumentException("$name must be an array of integers.") else null
            is List<*> -> value.mapIndexed { index, item ->
                val number = item as? Number ?: throw McpArgumentException("$name[$index] must be an integer.")
                try {
                    java.math.BigDecimal(number.toString()).longValueExact()
                } catch (_: IllegalArgumentException) {
                    throw McpArgumentException("$name[$index] must be an integer.")
                } catch (_: ArithmeticException) {
                    throw McpArgumentException("$name[$index] must be an integer.")
                }
            }
            else -> throw McpArgumentException("$name must be an array of integers.")
        }

        fun optionalInstant(name: String): Instant? = optionalString(name)?.let { value ->
            runCatching { Instant.parse(value) }
                .getOrElse { throw McpArgumentException("$name must be an ISO-8601 UTC timestamp.") }
        }

        private fun optionalNumber(name: String): Number? = when (val value = values[name]) {
            null -> null
            is Number -> value
            else -> throw McpArgumentException("$name must be an integer.")
        }

        private fun optionalBoolean(name: String): Boolean? = when (val value = values[name]) {
            null -> null
            is Boolean -> value
            else -> throw McpArgumentException("$name must be a boolean.")
        }
    }

    private fun canRetryRead(error: Throwable): Boolean {
        if (isCancellation(error)) return false
        return when (error) {
            is McpArgumentException, is AccessDeniedException -> false
            is ApiRuntimeException -> error.status.is5xxServerError
            else -> true
        }
    }

    private fun isCancellation(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is CancellationException || Exceptions.isCancel(it) }

    private fun logFailure(operation: String, error: Throwable, retrying: Boolean) {
        val cause = generateSequence(error) { it.cause }.take(8).last()
        // Exception messages may contain SQL values or learner text. Keep only
        // types and source frames, which identify the failing code without data.
        val frames = cause.stackTrace.take(8).joinToString(" <- ") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        log.warn(
            "mcp_operation_failed operation={} errorType={} causeType={} retrying={} origin={}",
            operation, error.javaClass.name, cause.javaClass.name, retrying, frames,
        )
    }

    private class McpArgumentException(message: String) : RuntimeException(message)

    private companion object {
        const val APPLICATION_JSON = "application/json"
        val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

        fun objectSchema(
            properties: Map<String, Map<String, Any>> = emptyMap(),
            required: List<String> = emptyList(),
        ): Map<String, Any> = linkedMapOf<String, Any>(
            "type" to "object",
            "properties" to properties,
            "additionalProperties" to false,
        ).apply {
            if (required.isNotEmpty()) put("required", required)
        }

        fun pagedSchema(
            additional: Map<String, Map<String, Any>> = emptyMap(),
            maximum: Int,
            defaultLimit: Int,
        ): Map<String, Any> = objectSchema(
            properties = linkedMapOf(
                "limit" to integerProperty("Maximum items to return.", 1, maximum.toLong(), defaultLimit),
                "offset" to integerProperty("Zero-based pagination offset.", 0, Int.MAX_VALUE.toLong(), 0),
            ).apply { putAll(additional) },
        )

        fun correlationSchema(): Map<String, Any> = objectSchema(
            properties = linkedMapOf(
                "correlation_id" to stringProperty("Asynchronous operation correlation ID.", minLength = 1, maxLength = 100),
            ),
            required = listOf("correlation_id"),
        )

        fun idProperty(description: String): Map<String, Any> =
            integerProperty(description, minimum = 1, maximum = Long.MAX_VALUE)

        fun languageProperty(description: String = "Response language code."): Map<String, Any> =
            stringProperty(description, values = listOf("ko", "en", "ja"), default = "ko")

        fun viewProperty(default: String = "localized"): Map<String, Any> =
            stringProperty("Localized or author-original content view.", values = listOf("localized", "original"), default = default)

        fun instantProperty(description: String): Map<String, Any> =
            stringProperty(description).toMutableMap().apply { put("format", "date-time") }

        fun stringProperty(
            description: String? = null,
            minLength: Int? = null,
            maxLength: Int? = null,
            values: List<String>? = null,
            default: String? = null,
        ): Map<String, Any> = linkedMapOf<String, Any>("type" to "string").apply {
            description?.let { put("description", it) }
            minLength?.let { put("minLength", it) }
            maxLength?.let { put("maxLength", it) }
            values?.let { put("enum", it) }
            default?.let { put("default", it) }
        }

        fun integerProperty(
            description: String,
            minimum: Long,
            maximum: Long,
            default: Int? = null,
        ): Map<String, Any> = linkedMapOf<String, Any>(
            "type" to "integer",
            "description" to description,
            "minimum" to minimum,
            "maximum" to maximum,
        ).apply { default?.let { put("default", it) } }

        fun booleanProperty(description: String, default: Boolean? = null): Map<String, Any> =
            linkedMapOf<String, Any>(
                "type" to "boolean",
                "description" to description,
            ).apply { default?.let { put("default", it) } }

        fun arrayProperty(
            description: String,
            item: Map<String, Any>,
            maxItems: Int,
        ): Map<String, Any> = linkedMapOf(
            "type" to "array",
            "description" to description,
            "items" to item,
            "maxItems" to maxItems,
        )
    }
}
