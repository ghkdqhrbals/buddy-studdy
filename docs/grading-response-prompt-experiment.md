# Grading Response Prompt Experiment

## Goal

Keep the numerical judgement unchanged while making learner-facing `feedback` and `explanation`
shorter, easier to scan, and consistent in Korean and English.

## Variants

| ID | Feedback | Explanation | Best for |
| --- | --- | --- | --- |
| `compact-summary-v1` | One sentence | One sentence | Small cards and fast review |
| `structured-brief-v1` | One conversational sentence | Correct point, meaningful correction, and optional better-answer guidance as natural prose | Balanced default |
| `action-coach-v1` | One conversational sentence | Correct point and optional concrete next action as natural prose | Concrete next-step coaching |

The configured style is read from `OPENAI_GRADING_RESPONSE_STYLE`. The default is
`structured-brief-v1`. The style ID is appended to the stored grading policy version so existing
records remain auditable.

## Comparison

The development profile exposes a protected comparison endpoint:

```http
POST /api/v1/admin/grading-prompts/preview
Authorization: Bearer <admin-token>
Content-Type: application/json
```

```json
{
  "question": "Redis Sentinel과 Redis Cluster의 차이를 설명하세요.",
  "answer": "Sentinel은 장애 조치, Cluster는 샤딩을 담당합니다.",
  "topic": "Redis",
  "level": 7,
  "language": "ko"
}
```

The endpoint creates the rubric, evidence, critique, and final judgement once. The server then
renders that one judgement in all three presentation styles. Style selection therefore cannot
change the score, verdict, or confidence. The endpoint is available only with the `dev` Spring
profile and administrator authentication.

## Verified Sample

The comparison endpoint was executed locally on 2026-07-28 with a Redis Sentinel versus Redis
Cluster answer. All variants returned the same score (`92`), verdict (`CORRECT`), and confidence
(`0.95`).

| Style | Feedback | Explanation shape |
| --- | --- | --- |
| `compact-summary-v1` | 역할 차이와 적합한 상황을 핵심적으로 잘 설명했어요. | One improvement sentence |
| `structured-brief-v1` | 역할 차이와 적합한 상황을 핵심적으로 잘 설명했어요. | 잘 짚은 내용, 보완점, 유용한 답변 예시를 자연스러운 문장으로 연결 |
| `action-coach-v1` | 역할 차이와 적합한 상황을 핵심적으로 잘 설명했어요. | 구체적인 다음 답변 예시를 우선 안내 |

The completed-answer check returned `98 / CORRECT / 0.99`. The selected structured response was:

> 두 기술의 핵심 차이와 적합한 상황을 정확히 설명했어요.
>
> Sentinel은 HA, Cluster는 샤딩·확장성 중심이라고 잘 구분했어요.

Because the answer was sufficiently complete, no correction or model-answer suggestion was appended.

## Selection Criteria

1. The verdict and score remain defensible from the rubric.
2. `feedback` does not repeat `explanation`.
3. The most important correct point and improvement are visible without expanding the UI.
4. Korean and English outputs follow the same information hierarchy.
5. Better-answer guidance appears only when it adds material learning value.
6. Learner-facing text does not expose Markdown headings, labels, or bullet lists.
7. A complete correct answer scoring at least 95 omits improvement and next-action coaching.
