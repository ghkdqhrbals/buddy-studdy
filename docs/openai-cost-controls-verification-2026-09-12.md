# OpenAI cost controls, steps 1–4

## Scope

Implemented the approved usage telemetry, paid-work retry controls,
server-owned lesson continuation and MCP payload compaction described in
[OPENAI_COST_CONTROLS.md](OPENAI_COST_CONTROLS.md).

Configured models, grading stages, output/context budgets, membership quotas
and iOS code were not changed. This verification did not make paid OpenAI calls.

## Automated verification

Final Gradle run: `BUILD SUCCESSFUL`. JUnit XML results:

| Scope | Passed | Skipped | Failed |
| --- | ---: | ---: | ---: |
| Application: question generation, write recovery, retry policy, voice service | 95 | 0 | 0 |
| Infrastructure: OpenAI, history/usage, MCP, native voice, user-input contract, atomic failure guard | 1,140 | 6 | 0 |
| Offline usage report | 8 | 0 | 0 |

The six skipped cases are existing opt-in live input-assessment/summary tests.
Synthetic HTTP exchanges and SDK interceptors cover response usage, transport
retries, quota/auth errors, cancellation and absent usage without provider spend.
The failure-guard tests execute SQL against an isolated H2 database.

Meaningful regression coverage includes:

- Failed/cancelled Realtime responses retain reported usage; repeated receipts
  and reconnect receipts count once. Unreported usage remains unknown.
- Missing SDK error fields and broken telemetry/history sinks preserve the
  original request outcome. Tokens and physical retry attempts are separate.
- A failed second summary stage reuses its verified first-stage evidence only
  within the same immutable-source retry scope.
- Transient writes and recovery reads reuse a generated question. An ambiguous
  committed write recovers it without provider replay, duplicate outboxes or
  quota changes. A stale generation failure cannot undo translation/completion;
  actual generation/translation failure rollback still works.
- Accepted topic selection continues only after its exact output ACK and remains
  fenced by learner turn, revision and cancellation. The question/answer capture
  contracts and curriculum parent/leaf behavior remain covered.
- Repeated-read receipts require fresh authorization/read, an identical result
  and an acknowledged original output. Errors, writes and canonical learning
  status reads remain excluded.

Reproduction from `backend/` (Java 25):

```sh
./gradlew :application:test \
  --tests 'com.buddystudy.backend.study.QuestionGenerationProcessorTest' \
  --tests 'com.buddystudy.backend.study.QuestionGenerationExecutionWriteServiceTest' \
  --tests 'com.buddystudy.backend.study.OpenAIRequestRetryPolicyTest' \
  --tests 'com.buddystudy.backend.voice.VoiceTutorServiceTest' \
  :infra:test \
  --tests 'com.buddystudy.backend.externalapi.adapter.outbound.*' \
  --tests 'com.buddystudy.backend.study.adapter.outbound.openai.*' \
  --tests 'com.buddystudy.backend.voice.adapter.outbound.mcp.*' \
  --tests 'com.buddystudy.backend.voice.adapter.outbound.openai.*' \
  --tests 'com.buddystudy.backend.voice.VoiceTutorUserInputContractTest' \
  --tests 'com.buddystudy.backend.study.adapter.outbound.persistence.QuestionGenerationSagaFailureGuardTest' \
  :tutor:bootJar -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=2g' \
  --max-workers=2 --no-parallel --continue --console=plain
```

From repository root:

```sh
python3 -m unittest discover -s backend/scripts/diagnostics -p test_openai_usage_report.py
git diff --check
```

## Local development runtime

The existing development database reported zero active voice sessions immediately
before replacement. The verified JAR was copied atomically into the existing
`buddystudy-feature20-dev-app` Docker volume, preserving the old JAR, and only
`backend-backend-1` was restarted. No Docker image was built or pulled; no
production host or deployment workflow was used.

The running container's JAR SHA-256 matches the host build:
`a030a0d0ea6e7a40e8471e210aaae1a36d043721862b39c7fb4973ad6910c80d`.

Backup: `buddystudy-backend.pre-cost-controls-20260912.jar`, SHA-256
`d1ac3516a37c35ac1a112e6e120aaee712b64a4fbd265e15f5965f94f14a0cb6`.

Netty started on port 8080, and local `/actuator/health` returned `UP` after
restart. These checks verify startup and local integration, not paid provider
availability, real-device audio behavior or measured invoice savings. No iOS
build or device installation was needed for this backend-only change. A matched
before/after usage comparison remains necessary to quantify actual savings.
