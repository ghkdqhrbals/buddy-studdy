# Practical question quality — 2026-09-13

## Problem and behavior

The canonical question prompt asked for a short question and diversity, but supplied no concrete standard for practical tasks or level-specific reasoning. The rubric could expand into details the question never requested. Voice tutoring could also fall back to definition-style explanations.

New questions start from an applicable task, symptom or decision in the selected topic and coverage concept. They ask for one central action/diagnosis/prediction and its reason. Levels 1–3 emphasize ordinary usage, 4–6 constrained diagnosis, 7–8 interacting constraints and trade-offs, and 9–10 conflicting evidence and verification. The existing 400-character target, language, recent-question history, saved difficulty and coverage path remain.

The initial rubric and fallback rubric use the same scope policy: 2–4 non-overlapping criteria, only explicitly requested requirements, semantic alternatives and full credit for a concise sufficient answer. Hints stay separate from the question. Voice explanations use practical context but must still read canonical saved questions unchanged.

## Validation method

- Application tests exercise all difficulty-band boundaries, clamping, language/coverage preservation, nontechnical subjects and tutor instructions; existing generation and grading parser tests cover compatibility.
- Bounded live provider samples use **compiled application prompts** and `gpt-5.4` (the configured default question model), JSON output, a 3,000-token output ceiling and a 90-second deadline per call. No learner data, saved questions, app sessions or MCP writes are used. These are question-generation checks, not a real iPhone lesson or a statistical quality benchmark.
- Manual acceptance criteria: plausible context; correct cause/effect; enough stated facts; selected concept/level; one focused task within 400 characters; rubric scoped to the visible ask and valid weights. JSON success alone is insufficient.

The first six-call sample (five candidate prompts plus one previous-prompt control) exposed an invalid TTL causal story: it linked missing expiry to premature expiration. That sample was rejected during review. The final prompt explicitly avoids invented guarantees/causes and prefers a concrete usage task when the failure mechanism is uncertain. It also explicitly states the answer language for language-learning tasks.

Redis SET semantics were checked against the [official command documentation](https://redis.io/docs/latest/commands/set/). The review-plus-small-prompt approach follows the [OpenAI prompt engineering guide](https://developers.openai.com/api/docs/guides/prompt-engineering); this is not an external automatic fact-checker.

## Cost and application scope

No new model round-trip, retrieval step, grading stage or runtime quality-judge call was added. The generator has additional fixed instructions once per generation; the realtime tutor gets one short practical-explanation instruction. Model selection and existing retry behavior remain unchanged. Live samples are one-off development costs and do not use study quota.

Previously saved questions/rubrics and active drafts are not rewritten. The change takes effect for newly generated questions; new voice sessions receive the updated tutoring instructions. Semantic accuracy remains probabilistic, so samples demonstrate behavior rather than guarantee every future question.

## Final results

- 113 tests passed: 14 prompt contracts, 12 generation processor, 75 voice tutor and 12 OpenAI executor/parser tests. `:tutor:bootJar` passed. Temporary compiled-prompt export fixtures were removed after use. No iOS source or UI was changed.
- Final live sample: four completed JSON responses, all under 400 characters, 2–3 distinct criteria and weights totaling 100. Manual review accepted the four for scenario coherence and rubric scope. Two independent TTL samples exercise the initially failed causal case. This small sample is not a general accuracy rate.

### Redis TTL — level 2

쇼핑몰에서 로그인 인증번호를 Redis 키로 저장합니다. 10분 뒤 자동 삭제되어야 하는데, TTL을 설정하지 않아 다음 날에도 남아 있습니다. 이 상황에서 어떤 한 가지 설정을 추가해야 하며, 왜 문제가 줄어드나요? 답은 한국어로 짧게 말하세요.

### Redis TTL — level 2

로그인 인증번호를 Redis에 저장합니다. 사용자가 10분 안에 입력해야 하는데, TTL을 설정하지 않아 오래된 인증번호가 남아 재사용될 수 있습니다. 이때 Redis에서 무엇을 설정해야 하고, 왜 이 문제가 줄어드나요? 답은 한국어로 짧게 말하세요.

### 분산 트랜잭션과 Saga 패턴 — level 8

주문 서비스가 재고 차감 후 결제 승인까지는 성공했지만, 배송 생성 호출이 30초 타임아웃으로 결과를 모릅니다. 결제사는 승인 취소를 10분 안에만 보장하고, 배송 API는 생성/취소 모두 멱등 키를 지원하지 않습니다. 이 Saga에서 지금 어떤 조치를 택해야 하며, 핵심 트레이드오프는 무엇인가요? 한국어로 짧게 답하세요.

### 영어 고객 응대 — level 2

카페 고객이 주문한 음료가 10분 늦어져 불만을 말합니다. 직원으로서 영어로 먼저 뭐라고 말해야 할까요? 한 문장으로 답하고, 왜 그 말이 적절한지 짧게 말하세요. 답은 **영어 문장 1개 + 한국어 이유 1개**로 하세요.

For the matched Saga input fixture, reported prompt tokens increased from 540 to 942. These are generation prompts, not an additional repeated realtime tool catalog. Output length and cache behavior vary. Ten total synthetic requests were used for this review, including the rejected initial sample and previous-prompt control.

Local evidence (ignored build artifacts): `build/practical-question-final-verification.log`, `build/practical-question-eval/initial-results.json`, `build/practical-question-eval/results.json`, and the exported before/final prompts.

## Local rollout

After confirming zero active voice sessions, replaced only the existing local Docker app-volume JAR with the host-built artifact and restarted `backend-backend-1`. `/actuator/health` returned `UP`. No image build, production SSH or production rollout was performed.

- Running JAR SHA-256: `d2097ef83db2aafd059486d12c35d8dd071b7db953a7d79f6c997e44863a712c`
- Previous JAR retained at `/app/buddystudy-backend.pre-practical-questions-20260913.jar`.
