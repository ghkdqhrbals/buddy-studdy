# Grading Response Prompt Experiment

## Goal

Keep the numerical judgement unchanged while making learner-facing `feedback` and `explanation`
shorter, easier to scan, and consistent in Korean and English.

## Variants

| ID | Feedback | Explanation | Best for |
| --- | --- | --- | --- |
| `compact-summary-v1` | One sentence | One sentence | Small cards and fast review |
| `structured-brief-v1` | One sentence | `잘한 점` and `보완할 점` bullets | Balanced default |
| `action-coach-v1` | One sentence | `판단 근거` and `다음 답변` bullets | Concrete next-step coaching |

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
Cluster answer. All variants returned the same score (`88`), verdict (`CORRECT`), and confidence
(`0.91`).

| Style | Feedback | Explanation shape |
| --- | --- | --- |
| `compact-summary-v1` | 정답, 역할 차이와 적합 상황을 맞게 설명함 | One improvement sentence |
| `structured-brief-v1` | 정답, 역할 차이와 적합 상황을 맞게 설명함 | `잘한 점`, `보완할 점` |
| `action-coach-v1` | 정답, 역할 차이와 적합 상황을 맞게 설명함 | `판단 근거`, `다음 답변` |

## Selection Criteria

1. The verdict and score remain defensible from the rubric.
2. `feedback` does not repeat `explanation`.
3. The most important correct point and improvement are visible without expanding the UI.
4. Korean and English outputs follow the same information hierarchy.
