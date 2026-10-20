# BuddyStudy for ChatGPT

End users connect their **own** BuddyStudy account to create studies and topics, request questions, submit answers for grading and manage learning records. Existing ownership checks and account quotas apply. Study/topic creation does not consume question quota. Deletion requires confirmation of the exact target. Account administration, API keys, resumes and private profile tools remain excluded.

## Backend configuration

Deploy the migration and backend first through the existing Backend API workflow.
Publish the KO/EN/JA `chatgpt-learning-management-2026-10-21.html` disclosures before enabling.

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
| Scopes | `learning:read learning:write learning:delete offline_access` |
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
2. Enter the email shown in BuddyStudy's app profile and the emailed 8-digit code, or use the existing password for an email-registered account.
3. Review any pending required account terms and explicitly agree if desired, then separately allow the requested learning read, write and deletion permissions to ChatGPT.
4. Ask “Show my recent learning records” or “Which topics should I review?”
5. Manage or revoke connections at `https://api.ghkdqhrbals.org/oauth/manage`.

A login attempt lasts 10 minutes with five code attempts. Email and authorization
requests are rate-limited. Access tokens last at most 15 minutes. Refresh tokens
rotate and have an absolute 30-day lifetime. Reusing a redeemed refresh token
revokes the whole connection, including its current access token. Disconnecting
also revokes server access immediately, but does not delete previously shared
ChatGPT conversation content. Connection management requires fresh email login.

## Verification and remaining rollout work

See `docs/MCP_SERVER.md` for tests. Production activation, actual SMTP delivery,
MySQL migration, live ChatGPT authorization/callback/refresh and two-account
isolation must be exercised in a deployment environment before public listing.
GitHub Actions must not add runtime smoke or health gates. No production SSH.

Official references:
- https://developers.openai.com/plugins/build/auth
- https://developers.openai.com/plugins/build/plugins

## Public review preparation

The publication ZIP includes five positive and three negative review scenarios,
listing metadata, public support/privacy/terms URLs, and the remote OAuth MCP
configuration. It contains no reviewer credentials.

The OpenAI organization shows **Verified** as of 2026-10-21. The plugin upload
form offers the verified identity **GYUMIN HWANGBO**. The 0.2.0 package targets
**KR, US and JP**, as selected by the owner.

The learning backend rollout and real ChatGPT tester email authentication passed.
The owner approved and completed the actual ChatGPT connection. A live read returned zero studies; root/topic creation was **Blocked** by `403 TERMS_AGREEMENT_REQUIRED` on the existing tester account. Remaining write cases, demo and public submission await authorized latest-terms acceptance in the normal account flow. No public draft has been uploaded or submitted.
Existing EMAIL-provider password login supports reviewer access without OTP;
credentials must remain outside this package and use the existing test account.

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
