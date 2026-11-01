# BuddyStudy for ChatGPT

End users connect their **own** BuddyStudy account to create studies and topics, request questions, submit answers for grading and manage learning records. Existing ownership checks and account quotas apply. Study/topic creation does not consume question quota. Deletion requires confirmation of the exact target. Account administration, API keys and profile edits remain excluded. Profile, saved resume and interests require the separately consented profile:read scope.

## Backend configuration

Deploy the migration and backend first through the existing Backend API workflow.
Publish the KO/EN/JA `chatgpt-profile-learning-2026-10-31.html` disclosures for
profile consent; retain the earlier learning-only disclosures for older grants.

```dotenv
MCP_SERVER_ENABLED=true
MCP_OAUTH_ENABLED=true
MCP_OAUTH_ISSUER=https://api.ghkdqhrbals.org
MCP_OAUTH_CLIENT_ID=buddystudy-chatgpt
MCP_OAUTH_REDIRECT_URIS=https://chatgpt.com/connector_platform_oauth_redirect
MCP_ALLOWED_HOSTS=api.ghkdqhrbals.org
MCP_ALLOWED_ORIGINS=
```

Behind the deployment template’s gateway, `MCP_OAUTH_TRUST_PROXY=true` uses
the gateway-overwritten `X-Real-IP` for network rate limits. Keep this false if
the backend can be reached directly.

Issuer and resource are canonical HTTPS values; do not derive them from incoming
Host/forwarded headers. Keep the existing SMTP and Redis credentials configured.
Apple Hide My Email accounts require delivery to their saved relay address; verify
Apple relay sender configuration before promising support. Ambiguous account
emails fail closed; no identities are automatically merged or accounts created.
An inactive account must be resolved in the app before connecting.

## Register the connection in ChatGPT

Use ChatGPT's **Add custom MCP server** flow (availability depends on the account
and workspace) with these values:

| Field | Value |
| --- | --- |
| Name | BuddyStudy |
| MCP URL | `https://api.ghkdqhrbals.org/api/v1/mcp` |
| Authentication | OAuth |
| Client registration | Provided / predefined client |
| Client ID | `buddystudy-chatgpt` |
| Token endpoint authentication | `none` (public client + PKCE S256) |
| Client secret | None |
| Scopes | `learning:read learning:write learning:delete profile:read offline_access` |
| Resource | `https://api.ghkdqhrbals.org/api/v1/mcp` |

Discovery supplies `/oauth/authorize` and `/oauth/token`. The server advertises
issuer identification and returns `iss` on the registered authorization callback.
Copy the **exact** redirect shown by the management page into
`MCP_OAUTH_REDIRECT_URIS` before using it if it differs from the stable callback.
Never add a wildcard or accept a redirect supplied by an end user. No CIMD or DCR
is advertised: select the provided public client explicitly.

The `plugin.json` and `mcp.json` in this folder provide a portable plugin package
with the same OAuth configuration. Use the remote HTTPS MCP submission path for
public directory publication. Package creation does not itself create a registered
ChatGPT server, publish a directory listing, or obtain OpenAI review approval.
If the portal returns a registered server ID, record the actual ID in its plugin
mapping; do not invent a `plugin_asdk_app...` identifier. The default callback is
for ChatGPT; local Codex callbacks are not enabled by this configuration.

## End-user flow

1. Connect BuddyStudy in ChatGPT.
2. Choose Apple or Google using the same provider account as the BuddyStudy app, or choose email and enter the saved profile email plus its 8-digit code or existing email-account password.
3. Review any pending required account terms and explicitly agree if desired, then separately allow the requested learning and profile permissions to ChatGPT.
4. Ask “Show my recent learning records” or “Which topics should I review?”
5. Manage or revoke connections at `https://api.ghkdqhrbals.org/oauth/manage`.

A login attempt lasts 10 minutes with five code attempts. Email and authorization
requests are rate-limited. Access tokens last at most 15 minutes. Refresh tokens
rotate and have an absolute 30-day lifetime. Reusing a redeemed refresh token
revokes the whole connection, including its current access token. Disconnecting
also revokes server access immediately, but does not delete previously shared
ChatGPT conversation content. Connection management requires fresh sign-in.

## Verification and remaining rollout work

See `docs/MCP_SERVER.md` for tests. Production activation, actual SMTP delivery,
MySQL migration, live ChatGPT authorization/callback/refresh and two-account
isolation must be exercised in a deployment environment before public listing.
GitHub Actions must not add runtime smoke or health gates. No production SSH.

Official references:
- https://developers.openai.com/plugins/build/auth
- https://developers.openai.com/plugins/build/plugins

## Public review preparation

The current completion audit and remaining evidence are in [SUBMISSION.md](SUBMISSION.md).
Build a separate candidate upload with `python3 scripts/package-buddystudy-plugin.py`.
Passing its local package checks does not mean the live cases or portal review passed.

The publication ZIP includes five positive and three negative review scenarios,
listing metadata, public support/privacy/terms URLs, and the remote OAuth MCP
configuration. It contains no reviewer credentials.

The verified publisher is **GYUMIN HWANGBO**. The current 0.2.1 author package
targets all supported countries. Its public portal draft is
`plugin_asdk_app_6ae65ced3f908191ba7ddf9ba790bd25`; upload does not establish review
submission or directory publication. The portal shows the configured OAuth
connection as **Authorized**, its domain as **Domain verified**, and a 28-tool
scan with no findings. Reviewer credentials were saved and checked only in the
private portal fields after the owner's explicit approval.

Existing EMAIL-provider password login supports reviewer access without OTP.
The original private development connection retains learning-only scopes. The
owner approved an additional `BuddyStudy Review 0.2.1` development connection for
verification; its 28-tool catalog includes `get_my_context`, and a real host
request returned the dedicated account profile and accurately empty context.
Full case results, deployment receipts and known failures are recorded in
[live review evidence](../../docs/plugin-live-review-2026-11-01.md).
The post-deployment run completed all five positive and three negative cases in
that approved development host. The unpublished public App's direct invocation
remains unavailable; its configured connection and updated scan were separately
verified. Legal attestations, review submission and publication remain distinct
steps.

Before requesting review, run all five positive and three negative packaged
scenarios against the exact saved version, record and verify a live ChatGPT demo,
and enter the existing tester credentials in the secure portal. Use the actual
domain verification token returned by the portal. Do not invent a plugin ID or
verification token. The authorized developer must complete legal/policy
attestations at the actual submission step. A prepared ZIP is not a submitted or
published plugin.

Existing users with `TERMS_AGREEMENT_REQUIRED` can sign in at connection management
and choose **Review required terms**. This records normal account agreements;
OAuth learning tools do not accept legal terms on the user’s behalf.

### Natural conversation acceptance gate

ID-directed developer prompts are transport/CRUD evidence only. Validate ordinary
conversation without IDs or special submission commands. The host must submit
the learner's exact answer and report the stored grading, rather than merely
praising the response or inventing a score.

The positive answer case now sets up a presented pending question and supplies
only the learner's answer text. Expected behavior is canonical exact-answer
submission and saved grading. Hints, explicit drafts and ambiguous multiple
questions must not be silently submitted.

The November 1 recording verifies a natural Korean answer and unchanged-answer
readback with 98/CORRECT. It does not establish a general fix for historical
translated-question/rubric failures, GPT-6 rendering or multi-question ambiguity.


## Profile and worldwide release candidate (2026-10-31)

The 0.2.1 candidate preserves existing learning creation, generation, grading,
follow-ups and confirmed-deletion behavior. It adds
get_my_context and buddystudy://me/context under a dedicated profile:read scope.
Existing learning grants cannot read profiles or acquire that scope on refresh;
users must reconnect and explicitly consent to the profile disclosure. The new
notice is chatgpt-profile-learning-2026-10-31.html with consent version
learning-profile-manage-2026-10-31. Account terms and optional connection consent
remain separate.

Publisher identity remains the verified GYUMIN HWANGBO. Country targeting is all
supported countries, as selected by the owner for this candidate. The account's
existing question allowance applies (30 on the free monthly tier); the plugin
neither promotes upgrades nor initiates payment. The profile review case replaces
the destructive demo case so no new live deletion is required for that case.

The [November 1 demo](https://ghkdqhrbals.github.io/buddy-studdy/chatgpt-demo-2026-11-01.html)
is a real current-host recording of saved-tree reads, a natural Korean answer,
98/CORRECT grading, unchanged-answer readback and a secret-access refusal.
Its accompanying page explicitly identifies the legacy connection's missing
profile consent. Public playback and seeking were verified after the successful
Pages deployment. The fresh Korean exercises did not reproduce the historical
English-answer penalty; this does not establish a fix for old translated records.
Revalidate every case against the final saved public connection using the
dedicated reviewer account. No public plugin submission or publication is
claimed by this note.


## Apple and Google web login

Configure the existing backend module before showing the social buttons:

| Setting | Value/source |
| --- | --- |
| `MCP_OAUTH_GOOGLE_CLIENT_ID` | Google OAuth **Web application** client ID |
| `MCP_OAUTH_GOOGLE_CLIENT_SECRET` | That web client's server-side secret |
| `MCP_OAUTH_APPLE_CLIENT_ID` | Apple **Services ID**, grouped with the existing iOS primary App ID |

Register these exact callback URLs in the respective provider consoles:

- Google: `https://api.ghkdqhrbals.org/oauth/social/google/callback`
- Apple: `https://api.ghkdqhrbals.org/oauth/social/apple/callback`

Apple also requires the backend domain in the Services ID web configuration.
Keep the native iOS client IDs unchanged. The Google web client must belong to
an appropriate Google project and its consent screen must permit intended users.
A matching email never links different provider identities: the signed `sub` and
provider must match an existing ACTIVE account. New accounts and identity merging
remain in the app. Apple Services ID grouping must preserve the app's subject IDs.

Google uses a server-side code exchange. Apple uses a signed hybrid `code id_token`
response with `form_post`, nonce and code-hash verification, so this sign-in-only
flow does not require persisting Apple refresh tokens or an Apple client secret.
The callback returns a one-time result to the browser; only that result plus the
original HttpOnly cookie can complete login. The result is immediately removed
from the page URL and never stored in local/session storage. Normal ChatGPT OAuth
code+PKCE and explicit learning-data consent still follow login.

Set the variables and secret in `personal-deploy` using its Backend API workflow
(as reflected in `docs/deploy-repo-template/deploy-backend.yml`), or supply the same
settings in the backend environment. Blank settings hide the provider buttons;
email-code login continues to work. Deploying source does not register clients in
the Apple or Google consoles. Test with real existing accounts after configuring.

Provider references: [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect),
[Apple web sign-in](https://developer.apple.com/documentation/signinwithapple/incorporating-sign-in-with-apple-into-other-platforms).

Operational audience checkpoint (2026-11-01): Google Auth Platform was still in
Testing with zero test users. It was changed to In production and read back
successfully. No Google scopes or native client IDs were changed. Provider
console configuration and working email/password reviewer access do not establish
completed real-account Apple/Google callback tests.
