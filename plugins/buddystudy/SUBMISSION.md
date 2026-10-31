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

## Live review evidence still required

| Requirement | Current evidence / next action |
| --- | --- |
| Dedicated reviewer access | Blocked: one password login using the existing App Store Connect review fields was rejected. No guessing, account reset or credential changes were attempted. The owner was asked to sign in to the prepared BuddyStudy browser page. Never put credentials in this repository or ZIP. |
| Current five positive cases | Not run for 0.2.1. Old generation/grading evidence does not prove current profile consent or the full current cases. |
| Three negative cases | Not run for this candidate. Old refusal evidence is historical. |
| Korean answer language | Historical failure: a translated Korean exercise retained an English-answer penalty. Reproduce with the actual host and fix the responsible layer before calling this workflow verified. |
| Current demo | Existing hosted 72-second video predates profile consent; it is not sufficient evidence for the current candidate. Record real current-host interactions and verify public playback. |
| Public portal draft | None observed in the intended Personal organization/project's Plugins page. Do not create a duplicate private plugin or upload the incomplete candidate as ready. |
| Domain/auth/scans | Obtain the real challenge and connect/test the exact saved public draft after preparation. Required portal checks are not yet run. |
| Reviewer credentials | Enter verified dedicated credentials only into secure portal fields after access works. |
| Attestations | The authorized developer must complete the current legal/policy attestations. Do not infer or accept them on the developer's behalf. |
| Review and publication | Not submitted, approved or published. Verify each returned state separately; publish only the approved release and confirm its directory listing. |

## Resume order

1. Verify the dedicated account can sign in, reconnect the existing development
   app with the intended scopes, and preserve private credentials off-screen.
2. Run and record the five positive and three negative cases with sample data;
   investigate the Korean grading regression using the actual saved results.
3. Record and verify an updated demo, update its real URL in `plugin.json`, then
   rebuild and inspect the complete ZIP.
4. Upload the public draft, verify imported metadata/publisher/targeting, complete
   domain and OAuth setup, and run the cases against the exact saved version.
5. Resolve required scans and obtain the developer's attestations, submit for
   review, track its real status, and publish after approval.

References: [submission guide](https://developers.openai.com/plugins/deploy/submission),
[review requirements](https://developers.openai.com/plugins/deploy/app-review).
