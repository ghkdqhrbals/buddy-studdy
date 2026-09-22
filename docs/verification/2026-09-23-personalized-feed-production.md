# Personalized feed backend production verification — 2026-09-23

The backend image build and module-scoped deployment completed successfully.
Local, anonymous, read-only requests then verified the new production HTTP behavior
at `https://api.ghkdqhrbals.org`. This report does not establish App Store publication
or iPhone Universal Link behavior.

## Release identity and deployment evidence

| Item | Verified value |
| --- | --- |
| Source commit | `ee9f96cf9644acbcc2bab8535942d6697277d6a2` |
| Source branch | `codex/app-store-growth-integration` |
| Image build | [35754929724](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/35754929724), success |
| Image runtime/platform | JVM / `linux/arm64`, built on the GitHub-hosted runner |
| Immutable image | `ghcr.io/ghkdqhrbals/buddystudy-backend@sha256:636d835f6b1eaaa70ea8b0f465b627b4c9946987e2177c858729234aeb8665c9` |
| Deploy workflow | [35755734992](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/35755734992), success |
| Deploy branch/commit | `codex/personalized-feed-rollout` / `1e5cdb56ceca607fe5f4c3c2d6a2a167394d1915` |
| Manual deployment inputs | Exact digest above, `backend_runtime=jvm`, `promote_swarm=false`, `notify_slack=false` |

Source build job `106838183351` logged the SHA-jvm tag's completed manifest push at
`2026-09-22T16:39:19Z` and the same `containerimage.digest` at `16:40:02Z`.
`Notify Deploy Repo` and `Watch Deploy Result` were both skipped because the build
used `dispatch_deploy=false`.

Deploy job `106840906042` pulled the exact digest and logged its submission to
`buddystudy_backend` at `2026-09-22T16:42:25Z`. The workflow's equality check between
the requested digest and `docker service inspect`'s
`.Spec.TaskTemplate.ContainerSpec.Image` passed. The task listing immediately after
submission still showed the new task as Pending; workflow success alone was not
treated as proof that the application was serving traffic.

`Publish deployment started` and `Publish deployment result` succeeded.
`Notify Slack` was **skipped**, as verified in the completed job's step results.
No Monitoring workflow was deployed. No direct SSH connection was used. The
workflow did not run application/container runtime health or smoke checks; the
HTTP checks below ran separately on the local workstation after it completed.

## Local production HTTP checks

The recorded matrix ran at `2026-09-22T16:45:16Z–16:45:18Z`
(`2026-09-23 01:45 KST`). It made **23 GET requests with 65 passing assertions**:
16 responses were 200, one was the expected 401, four were expected 404s, and two
were expected 422s. One preceding latest-feed GET also returned 200. Requests used
no authorization or cookies. Feed requests used `view=original&tl=ko` to avoid
translation enqueueing. Neither the question-detail API nor any write endpoint
was called.

| Check | Observed result |
| --- | --- |
| `/api/v2/public/questions`, `scope=all`, each of `recommended`, `latest`, `views`, `likes` | HTTP 200; 15 public records; bounded metadata and no duplicate IDs. |
| Latest/views/likes ordering | Matched the documented descending primary metric, secondary metric where applicable, creation time, and ID order. |
| Exact pagination for all four sorts | `limit=5&offset=7` returned exactly IDs 8–12 from the corresponding first page. |
| Default sort | Omitting `sort` with `scope=all` returned the same ordered IDs as explicit `recommended`. |
| Maximum page size | Requesting `limit=101` returned `limit=100` and 15 records. The small live dataset did not exercise 100 returned rows. |
| Anonymous `scope=following` | HTTP 200, empty `questions`, `totalCount=0`. |
| Invalid `sort` and invalid `scope` | Each returned HTTP 422. |
| Unauthenticated `/api/v1/me/topic-subscriptions` | HTTP 401. |
| `/.well-known/apple-app-site-association` | HTTP 200; both `/questions/*` and `/referrals/*` retained. |
| `/questions/156?tl=ko`, `en`, `ja` | Each returned HTTP 200, the requested HTML locale, question preview, canonical URL, App Store URL, and matching app deep link. |
| Share security and privacy headers | Exact restrictive CSP, `Cache-Control: no-store, max-age=0`, `Referrer-Policy: no-referrer`, `X-Robots-Tag: noindex, noarchive`, `X-Frame-Options: DENY`, and `nosniff` present in all three languages. |
| Share response projection | The known answer, author display value, and two grading text values from the public feed were absent from all three HTML pages; no author metadata, scripts, audio, video, or iframes. No answer/author text was retained in this report. |
| Invalid share IDs `0`, `-1`, `abc`, and `9223372036854775808` | Identical generic HTTP 404 bodies, no question-specific canonical/social metadata, with no-store/CSP retained. |
| View counts | All 15 public record counters were identical before and after the matrix. |

Record `156` was selected from the live public list, not assumed to be public from
an earlier deployment. The bounded `limit=100` scan returned all 15 current public
records, all `QUESTION` / `graded`; there was no `VOICE_TUTOR` sample for a live
share check. The three HTML locales verify UI language and available previews,
not that every question has a newly generated translation.

Commands used to read workflow status and logs included:

```sh
gh run view 35754929724 --repo ghkdqhrbals/buddy-studdy --json status,conclusion,headSha,jobs
gh run view 35754929724 --repo ghkdqhrbals/buddy-studdy --job 106838183351 --log
gh run view 35755734992 --repo ghkdqhrbals/personal-deploy --json status,conclusion,headSha,jobs
gh run view 35755734992 --repo ghkdqhrbals/personal-deploy --job 106840906042 --log
```

The local Python `urllib` assertion runner used only the listed GET routes and
parameters. Its temporary script and sanitized results are
`/tmp/buddystudy-production-readonly-check.py` and
`/tmp/buddystudy-production-readonly-results.json`; these are workstation evidence,
not repository fixtures or an Actions runtime gate. Logs were filtered before
display, and no credential values were retained.

## Migration evidence and remaining limits

- The image source includes additive
  `V120__community_topic_subscriptions.sql`; prior migration files were preserved.
  Real local MySQL applied through V120 in the earlier
  [backend integration verification](2026-09-23-personalized-feed-backend-integration.md).
  Deployment configuration enables Flyway and the image's filesystem migration
  directory. The deploy workflow does not collect application startup logs, and
  no V120 application message was available in its logs. **Production Flyway
  history was not independently read or verified.** Successful anonymous feed
  requests do not prove that the subscription table was queried.
- No production account, subscriptions, visibility, likes, answers, or voice data
  were created or changed. Authenticated subscription persistence, personalized
  ranking, blocked/private/deleted-state fixtures, account withdrawal, and voice
  previews retain the earlier local integration evidence; they were not reproduced
  with production mutations. No public voice sample existed in the bounded scan.
- This is a short production snapshot, not a load test or a sustained availability
  claim. Existing Grafana runtime monitoring remains responsible for subsequent
  failures. The skip result verifies this deployment's Slack step; it does not
  disable independent monitoring alerts.

The prior production image remains the recorded rollback target:
`ghcr.io/ghkdqhrbals/buddystudy-backend@sha256:b52bdc96caa59342429ff6a7176ec92cbcbd43e42dcdb5e6d8389bf86ced1df7`.
Use the same deploy branch with `backend_runtime=jvm`, `promote_swarm=false`, and
`notify_slack=false` if rollback is authorized. No rollback was performed, and the
additive V120 table should not be dropped as part of an application rollback.
