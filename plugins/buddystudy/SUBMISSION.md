# Public submission checkpoint — 2026-11-01

The requested outcome is a published BuddyStudy plugin in the ChatGPT directory.
A deploy, private installation or locally valid archive does not meet that outcome.

## Confirmed preparation

- Public author source is `plugins/buddystudy`; the existing private development
  plugin remains `plugin_asdk_app_6ad7f06754188191aeff97c815be527e`.
- Publisher is the previously selected GYUMIN HWANGBO. Worldwide targeting,
  Education & Research category, English listing and Korean/Japanese translations
  remain in the manifest. The plugin does not initiate purchases.
- The deployed MCP endpoint and explicit learning/profile OAuth scopes match
  `mcp.json`. Tool annotations have explicit Boolean read-only, destructive and
  open-world hints in the shared builder.
- The author ZIP is built separately with
  `python3 scripts/package-buddystudy-plugin.py`. It includes exactly
  `plugin.json`, `mcp.json` and `assets/icon.png`; no private app binding,
  reviewer credential, lifecycle hook or local server is included.
- Local checks cover listing lengths, category, four HTTPS URLs, starter prompts,
  five positive/three negative cases, metadata, 1024px PNG dimensions, endpoint,
  OAuth scopes and the actual ZIP inventory. Portal validation remains separate.
- The homepage now identifies the publisher and ChatGPT workflow in KO/EN/JA.
  Support describes Apple/Google/email sign-in and fresh profile consent. Local
  desktop and 390px mobile checks found no browser errors or horizontal overflow;
  homepage → support → connection notice navigation and local links passed.
- [Pages deployment 41220555706](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/41220555706)
  succeeded. Live KO/EN/JA homepages and support show the new content; browser
  navigation followed the privacy/terms aliases to their actual policy documents.
  The portal offers the saved Individual — GYUMIN HWANGBO publisher identity.
- Further source validation reproduced the per-grant discovery mismatch (28 tools
  listed for a 15-tool read-only grant) and corrected it without widening access.
  All 72 targeted transport/security/adapter/logging tests passed; direct calls
  outside the grant remain denied. Backend rollout is tracked separately.
- Per-grant discovery is deployed from `c2f3280e` (feature `1f2b8f3e`).
  [Build/dispatch/watch 41221213007](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/41221213007)
  and [backend deployment 41221749954](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/41221749954)
  both succeeded. Manual metadata checks still show both social providers and all
  intended OAuth scopes. A later authenticated development rehearsal is recorded
  in [live review evidence](../../docs/plugin-live-review-2026-11-01.md); final
  public-version checks remain unverified.
- Actual browser-target video recording was tested with public-page navigation:
  a 24-second AV1 recording was saved outside the repository. This confirms the
  recording mechanism, not the missing current-version review demo.

## Live review evidence still required

| Requirement | Current evidence / next action |
| --- | --- |
| Dedicated reviewer access | Resolved: the owner supplied the actual reviewer credential; email/password login and existing learning consent succeeded. It is retained in macOS Keychain (`BuddyStudy reviewer login`) and the explicitly authorized private portal reviewer fields, outside this repository and ZIP. No password reset was performed. |
| Current five positive cases | Passed in the approved development host using the saved candidate's endpoint/client/scopes and deployed definitions: creation/reuse, quota-free fresh creation, actual question delivery, exact answer/grade readback, linked follow-up and profile/context. The post-deploy answer case saved 0/INCORRECT for the generic test sentence on an option-choice question; persistence/fidelity passed, not general grading accuracy. Direct invocation of the unpublished public App remains unavailable; see the explicit binding and raw-log limits in the live evidence. |
| Development profile scope | The owner explicitly approved an additional `BuddyStudy Review 0.2.1` development connection, App `asdk_app_6ae6fd63f4e481919a680c4bee9cacb1`, as an exception to the duplicate-private-plugin restriction. It uses the public candidate's endpoint/client/resource/five scopes and exposes 16 read + 12 write tools. The legacy connection is preserved. |
| Three negative cases | Passed in the fresh post-deploy host run. Broad deletion now asks for exact targets/confirmation; secrets and other-owner private data are refused. Records 197/198/204 and follow-up 205 remained present. The earlier failed deletion attempt and manual host denial are preserved separately in the evidence. |
| Korean answer language | A fresh Korean exercise returned 97/CORRECT and the saved original Korean answer. It did not reproduce the English-answer penalty, but historical translated-question/rubric behavior is not established as fixed. |
| Current demo | Complete: [new real ChatGPT recording](https://ghkdqhrbals.github.io/buddy-studdy/chatgpt-demo-2026-11-01.html), 155.8 seconds, H.264. [Pages deployment 41243415975](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/41243415975) succeeded from `e0574ffb`. Public Chrome playback advanced through 104 seconds with no media error, including a seek to the saved-result segment. The page truthfully identifies the legacy connection's missing profile scope. The actual URL is now in `plugin.json`. |
| Public portal draft | Uploaded 0.2.1 in the intended Personal organization with Individual — GYUMIN HWANGBO. Public draft `plugin_asdk_app_6ae65ced3f908191ba7ddf9ba790bd25` preserves the separate private installation. Publisher, four URLs, all countries, KO/JA translations, 5/3 cases and new demo URL were read back. A listing-price finding was corrected and cleared by reupload. Remaining privacy-assessment finding is explicitly nonblocking. |
| Domain/auth/scans | Complete: **Configured / Authorized / Domain verified**. The post-deployment public scan found all 28 tools with zero findings, and scanned definitions contain the new polling/deletion instructions. Reupload preserved authorization. Scan results and host execution evidence remain distinct. |
| Social web setup | Apple Services ID and Google web client/callback/secret wiring are configured. The final audit changed Google's remaining Testing audience to In production and verified the result, without scope/native-client changes. Real Apple/Google account callback completion is not claimed; reviewer email/password sign-in is verified. |
| Reviewer credentials | The owner approved disclosure to OpenAI reviewers. The actual credential and sign-in steps were saved only in private reviewer fields, then reopened and checked; they remain populated after reupload. The inspected ZIP contains no reviewer password or private App binding. |
| Attestations | The authorized developer must complete the current legal/policy attestations. Do not infer or accept them on the developer's behalf. |
| Review and publication | Not submitted, approved or published. Verify each returned state separately; publish only the approved release and confirm its directory listing. |

## Resume order

1. [Build/dispatch/watch 41291649367](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/41291649367)
   and [backend rollout 41292174834](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/41292174834)
   succeeded from `f3d41172`. The 58-test MCP/OAuth set, tutor compile, metadata
   refresh, public rescan and the approved host case run are recorded in the
   [live evidence](../../docs/plugin-live-review-2026-11-01.md).
2. Have the authorized developer complete the current legal/policy attestations
   on the prepared submission. Do not accept these facts on their behalf.
3. Submit for review within the user's authorization, track its returned state,
   and publish only after approval. Verify the actual directory release.

References: [submission guide](https://developers.openai.com/plugins/deploy/submission),
[review requirements](https://developers.openai.com/plugins/deploy/app-review).
