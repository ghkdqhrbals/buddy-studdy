# Hint generation guidance verification — 2026-09-28

## Scope

The backend question generator now receives system-level instructions to keep
learner-visible hints to one or two thinking cues, without giving the answer,
answer-equivalent names, correct option, numeric result, decisive solution step,
or elimination that leaves only the answer. It must reconsider the hint against
the question and rubric, and return JSON null when no useful non-revealing cue
is possible. The complete internal grading rubric remains unaffected.

This change was based on remote main `f5cf8baa12145b23fc0cc839e7c2fb21fd69cfde`
in an isolated worktree, preserving the newer practical-question, difficulty,
and rubric policies. Unrelated local iOS, IDE, and screenshot changes were not
included.

## Verification

The existing question and follow-up prompt suites passed: 16 tests, zero
failures or errors. From `backend`:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-jdk-25.0.2+10.1/Contents/Home \
./gradlew :application:test \
  --tests com.buddystudy.backend.study.QuestionPromptProviderTest \
  --tests com.buddystudy.backend.study.FollowUpQuestionPromptTest \
  --console=plain
```

Read-only review traced manual and scheduled generation through the shared
question prompt provider, and follow-up generation through the same base
system prompt. The existing response parser preserves JSON null as a nullable
hint, and the iOS hint view hides absent hints. No client, schema, grading
behavior, deployment workflow, or runtime probe was changed.

These checks verify prompt construction and compatibility, not generated
content quality. No live model evaluation or factual-accuracy assessment was
performed. This is a prompt instruction, not a separate answer-leak detector;
it affects newly generated questions and does not rewrite stored hints. An iOS
build or device test was not run because this change is confined to backend
prompt text and documentation.
