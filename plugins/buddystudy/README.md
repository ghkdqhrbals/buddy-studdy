# BuddyStudy for ChatGPT

End users connect their **own** BuddyStudy account and retrieve their study tree,
questions, answers, grading feedback and topic-level statistics. This is not an
administrator integration. No API key, device token or manual bearer copy is
required. No content-creation, deletion, account administration, resume or private
profile tools are exposed by the OAuth connection.

## Backend configuration

Deploy the migration and backend first through the existing Backend API workflow.
Publish the KO/EN/JA `chatgpt-connection-2026-10-20.html` disclosures before enabling.

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
| Scopes | `learning:read offline_access` |
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
2. Enter the email shown in BuddyStudy's app profile and the emailed 8-digit code.
3. Review and allow read-only disclosure of learning records to ChatGPT.
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

The publisher completed identity submission on 2026-10-21. The OpenAI organization
settings currently show **Identity in review**; the plugin portal still refuses
draft creation until the publisher identity is verified. Do not start another
Persona inquiry while the submitted one is being reviewed.

After approval, upload the ZIP at `https://platform.openai.com/plugins`, then use
the actual domain verification token returned by the portal. Do not invent a
plugin ID or a verification token. Before requesting review, run all packaged
scenarios using an isolated sample-data account, record the live walkthrough,
and enter reviewer credentials in the secure portal. OpenAI requires reviewer
access that does not depend on interactive email codes or MFA; the current OAuth
email-code login requires a supported reviewer-access path before submission.
The existing app email/password login may provide the credential-verification
boundary for that path, but no reviewer bypass or static verification code has
been introduced. Policy attestations must be reviewed at the actual submission
step. A prepared ZIP is not a submitted or published plugin.
