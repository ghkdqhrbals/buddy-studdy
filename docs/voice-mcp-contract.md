# Native conversation MCP contract

The native Voice Tutor uses the authenticated MCP handlers through
`McpVoiceTutorToolAdapter.realtimeDefinitions()`. Its provider catalog is a
voice-specific projection, not a second persistence or permission system.
The legacy sideband catalog and external HTTP MCP remain separate contracts.

## Curriculum identity and progression

The main topic is the original owned root (depth 0). The selected topic is the
exact saved node the learner chose, which can be any descendant. Actual parent
IDs determine that relationship; names and an empty child list do not.
`curriculumTerminal=true` or descendant depth 4 identifies a learning unit.

For example, main MSA (level 8) → selected Communication (level 2) without
children prepares children **under Communication** at level **8**. Existing
children keep their saved levels. A terminal selected node immediately becomes
the final lesson focus without generating more children. Topic creation consumes
no question allowance.

| Tool or channel | Contract and next step |
| --- | --- |
| `list_studies` / `get_study` | Discover exact owned IDs and real parent relationships. Native list results include terminal metadata, not embedded questions, answers, or prompts. The list defaults to 10 rows at offset 0; explicit bounded pages and `totalCount` determine completeness. Reads do not select a topic. |
| `select_voice_study` | Prepare the selected node's curriculum. Nonterminal nodes reuse saved direct children or create one missing direct level and open a server-owned choice card. The tool remains held while the card is pending. A terminal selection returns `voiceLessonFocus`; only this final ID authorizes question lookup. |
| `advance_voice_study` | Apply the same curriculum gate to one actual direct child of the current focus, after the learner chooses it. |
| `request_user_input` | Collect ordinary preferences in one form, or prepare an immutable batch-topic proposal. Submit and Cancel arrive through authenticated app events. Never infer a click from silence or publish a second form while one is pending. |
| `suggest_study_topics` + `request_user_input.studyTopicProposal` | Explicitly add more topics to an exact parent. Proposal preparation writes nothing; Submit creates only selected candidates. The server inherits the original root level. `difficultyLevel` is an optional compatibility hint, not authority to override the root. |
| `prepare_voice_study_mutation` + `confirm_voice_study_mutation` | Explicit root/child creation, rename, level update, or subtree deletion. Prepare freezes a proposal; one fresh natural confirmation applies it. Automatic curriculum preparation and an exact submitted batch-topic card already have their own authorization paths. |
| `list_pending_questions` | Read the final focus's saved unanswered question before teaching. A nonterminal focus goes through the curriculum gate first. No invented readback or grading question opens answer capture. |
| `request_question` | Reuse a pending question, or request a new one once. Question allowance is separate from curriculum creation. Argument validation occurs before any curriculum read or write. |
| App answer controls | Finish → review/edit → Submit schedules the server-owned `submit_answer` call. It is excluded from the provider's catalog; the authenticated app submission path still uses canonical grading. |
| Progress events | Accepted question/grading processes are observed through server events over the existing authenticated conversation WebSocket. `get_question_process` and `get_grading_process` are single explicit status/recovery reads, not a model polling loop. External HTTP MCP retains its own process-read contract. |

A cancelled choice makes no selection and grants no mutation permission. It does
not delete automatically prepared saved children. Cancelling a learning direction
stops its automatic follow-up while preserving drafts and accepted jobs. A failed
read means the saved state is unknown; it never authorizes replacement creation.

## Input and output boundaries

`request_user_input` accepts exactly one of these argument shapes:

```json
{"title":"학습 방향","questions":[{"id":"direction","prompt":"어떤 방식으로 시작할까요?","selectionMode":"single","options":[{"id":"examples","label":"예제로 시작"},{"id":"concepts","label":"개념부터 정리"}],"allowFreeText":true}]}
```

```json
{"studyTopicProposal":{"parentStudyId":102,"topics":["메시지 전달 보장","동기 호출의 실패 처리"]}}
```

Single and multiple choice questions require 1–8 options. Text-only questions
require `options: []` and `allowFreeText: true`. IDs use ASCII letters, digits,
underscores or hyphens (1–80 characters), with uniqueness checked within their
scope. One form contains 1–5 questions. The schema advertises mode-specific
constraints; the server also rejects empty or mixed top-level shapes, unknown
fields, duplicate IDs, whitespace-only content and invalid lengths before a
form opens or a proposal is prepared. The root schema stays an object without a
root union for provider compatibility; exclusivity is enforced at execution.
Transport envelopes retain their authenticated correlation fields separately.

Operation and choice display IDs are not mutation authority. The server retains
the originating call while waiting, persists exact GUI answers before applying
changes, and acknowledges the final output before continuing. The app keeps
completed/cancelled choices and tool captions under the originating dialogue.

## Verification (2026-09-11)

This change is backend-only. Targeted checks cover native tool descriptions and
pagination, malformed question arguments before curriculum side effects,
root-level inheritance despite a different selected-parent level, schema/parser
constraints, invalid/mixed forms through the relay, exact GUI persistence,
app-owned answer submission, and native conversation/MCP continuation.

`build/voice-mcp-contract-verified.log`: BUILD SUCCESSFUL, including the backend
JAR task. Across the six selected classes, 453 tests passed with zero failures,
errors or skips (70 application tests, 383 infrastructure tests). Counts and
suite timestamps are retained in `build/voice-mcp-contract-test-counts.json`.
Initial checks found obsolete prompt assertions, an incomplete new canonical
response fixture and a relay test that assumed two asynchronous output channels
arrived simultaneously. The fixture now supplies the actual response contract;
the relay assertion awaits both durable submission outputs without assuming
their delivery order.

iOS source and its wire event envelope are unchanged in this contract update.
The tests exercise app-owned submission through the in-memory native relay;
this update does not claim an additional real-device spoken conversation test.

The local development backend uses the existing container image and mounted
JAR, with no Docker image build or production access. Before replacing the JAR
and again before restart, the live-session/result-processing gate returned zero.
The installed JAR SHA-256 is
`e036cea07761df6efb0164cbd1c0dbb3f2cd535916880a55a3747f329ae61bf0`;
its previous version is preserved as
`/app/buddystudy-backend.pre-mcp-contract-20260911.jar` (SHA-256
`5f6638df1ce7dab6716d31f97052b579a844f045ad69d02443b9fc56dc8af132`).
The provider catalog and instructions are captured on connection, so the change
applies to newly started conversations.
Both `http://localhost:8080/actuator/health` and the configured development
endpoint `https://lowfidev.cloud/actuator/health` returned `UP` after restart.
The running container's JAR checksum matches the verified artifact.
