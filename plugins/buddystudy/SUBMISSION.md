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
| Dedicated reviewer access | Resolved: the owner supplied the actual reviewer credential; email/password login and existing learning consent succeeded. It is stored only in macOS Keychain (`BuddyStudy reviewer login`), not in this repository or ZIP. No password reset was performed. |
| Current five positive cases | A current private-host rehearsal displayed the saved tree, Korean question, exact Korean answer/97-point grading, and a linked follow-up. Quota delta, raw tool arguments/feedback fidelity and profile consent remain unverified; run all cases against the final saved public version. |
| Development profile scope | The legacy development connection opens the learning-management disclosure, rather than the profile disclosure. Verify its requested scopes before the profile case; a successful legacy learning login does not prove profile access. The public author `mcp.json` explicitly requests `profile:read`. |
| Three negative cases | Current-host secret and other-owner requests were refused. The broad-delete case was interrupted and records were subsequently found intact; it is not a passing refusal case. Repeat safely in the final checks. |
| Korean answer language | A fresh Korean exercise returned 97/CORRECT and the saved original Korean answer. It did not reproduce the English-answer penalty, but historical translated-question/rubric behavior is not established as fixed. |
| Current demo | Complete: [new real ChatGPT recording](https://ghkdqhrbals.github.io/buddy-studdy/chatgpt-demo-2026-11-01.html), 155.8 seconds, H.264. [Pages deployment 41243415975](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/41243415975) succeeded from `e0574ffb`. Public Chrome playback advanced through 104 seconds with no media error, including a seek to the saved-result segment. The page truthfully identifies the legacy connection's missing profile scope. The actual URL is now in `plugin.json`. |
| Public portal draft | Uploaded 0.2.1 in the intended Personal organization with Individual — GYUMIN HWANGBO. Public draft `plugin_asdk_app_6ae65ced3f908191ba7ddf9ba790bd25` preserves the separate private installation. Publisher, four URLs, all countries, KO/JA translations, 5/3 cases and new demo URL were read back. A listing-price finding was corrected and cleared by reupload. Remaining privacy-assessment finding is explicitly nonblocking. |
| Domain/auth/scans | The public MCP dialog imported all five intended scopes. The signed-in reviewer reached the actual profile/resume/interests consent screen; new access awaits owner approval. The real domain challenge returned HTTP 404; its public static resource is prepared and passed resource packaging. Track backend deployment, returned verification and exact-version cases separately. |
| Reviewer credentials | Login works. Private portal fields remain empty pending the owner's explicit approval to provide the dedicated credential to OpenAI reviewers. Never put it in the ZIP or listing. |
| Attestations | The authorized developer must complete the current legal/policy attestations. Do not infer or accept them on the developer's behalf. |
| Review and publication | Not submitted, approved or published. Verify each returned state separately; publish only the approved release and confirm its directory listing. |

## Resume order

1. Finish the prepared domain-proof deployment and verify the portal's result.
2. With the owner's reply, complete the prepared profile grant and private
   reviewer-credential fields. Preserve credentials off-screen.
3. Run the remaining exact-version cases with sample data, including profile,
   quota evidence and the interrupted delete refusal. Package validation and
   the development rehearsal do not replace these final checks.
4. Resolve required scans and obtain the developer's attestations, submit for
   review, track its real status, and publish after approval.

References: [submission guide](https://developers.openai.com/plugins/deploy/submission),
[review requirements](https://developers.openai.com/plugins/deploy/app-review).
