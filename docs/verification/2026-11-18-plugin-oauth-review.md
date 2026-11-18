# BuddyStudy OAuth review follow-up — 2026-11-18

The OpenAI review email received at 01:49 KST rejects submitted plugin 0.2.1
because login or OAuth could not be completed. It does not identify the failed
request, account, callback or error code. The earlier `/oauth/manage` password
test and an existing MCP connection's profile read were insufficient to establish
that a new connection from the submitted plugin works.

## Actual submitted-host verification

The existing public submission and organization were preserved:

- Plugin: `plugin_asdk_app_6ae65ced3f908191ba7ddf9ba790bd25`
- Rejected release: `pluginrel_2f60fa2567c48191aaeaacea004ccb6c`
- Associated app: `asdk_app_6ae65ced3f908191ba7ddf9ba790bd25`
- Canonical resource: `https://api.ghkdqhrbals.org/api/v1/mcp`

The portal's **Reconnect** action started a new authorization request. The
dedicated account's stored password was used without exposing or saving it in
source, packages, screenshots or browser storage. The password path reached the
learning/profile consent screen without email codes, social login, registration
or required-terms setup. The displayed account matched the dedicated reviewer.
An independent fresh authorize/password check also returned no pending terms.

After allowing the same existing learning/profile permissions, the callback
returned to the Platform and its OAuth popup closed. Reloading the submitted
version showed **Authorized** and a fresh scan of all 28 tools with **No findings
in the latest MCP scan**. This verifies the real submitted host's new
authorization, callback, token exchange and authenticated MCP discovery; it is
not merely an old bearer-token read. An earlier attempt using another account
reached required terms and was cancelled without agreeing to them.

The exact reviewer's historical failure remains unknown. The demonstrated
access traps are that password login was behind the default email-code form,
and opening `/oauth/connect.html` directly displayed a form with no usable
authorization request. The patch removes those traps without weakening account
authentication, legal consent, scope boundaries, PKCE, resource checks or cookies.

## Change and local verification

- Separate first-screen password and emailed-code login choices, explicit form
  headings and submit labels, with Korean and English copy.
- Missing/malformed IDs show host restart instructions without API calls or a
  credential form. Expired session checks also hide the unusable form.
- A separate `/oauth/manage` link starts management-only verification; it never
  fabricates a new ChatGPT authorization request.
- The local Playwright fixture covers password sign-in without code delivery,
  the retained code flow, password clearing, missing/malformed/expired requests,
  Korean/English and a 390 × 844 viewport. Five cases passed with no browser
  errors. Fixture results do not by themselves prove live OAuth.
- JavaScript syntax and `git diff --check` passed. `:tutor:processResources`
  passed; generated HTML, JS and CSS byte-match the revised source.
- `scripts/package-buddystudy-plugin.py` passed all package checks. The three-file
  0.2.2 candidate ZIP SHA-256 is
  `eb9feedc5f0e5b621ab0cbbed9c70808c3f10d4f49221af5e7d6e5fdaea45fb1`.

The public author package is 0.2.2 with the same identity, endpoint, OAuth client,
five scopes, five positive and three negative cases, demo URL and country
targeting. Reviewer credentials remain exclusively in private portal fields.

## Rollout and resubmission

Pending: Backend-only Actions deployment, post-deployment new portal connection,
saved 0.2.2 draft validation, private reviewer instructions readback and renewed
developer attestations before an authorized review submission. A successful
login or scan is not OpenAI review approval or directory publication.
