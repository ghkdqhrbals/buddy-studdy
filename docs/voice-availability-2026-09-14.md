# Buddy voice unavailable — 2026-09-14

## Cause

The reported monthly voice-time screen showed a temporary-service error. An
authenticated production `GET /api/v1/voice-tutor/status` returned HTTP 200,
`eligible=false`, `reason=UNAVAILABLE`, not an HTTP failure. The diagnostic used
the existing review account and signed out its temporary session afterward;
no voice call, quota reservation, purchase or study mutation was performed.

The live `personal-deploy` backend workflow was missing the voice environment
variables and stream/control/SDP routes already present in this repository's
template. No production repository voice activation variable was set. The app
therefore inherited `VOICE_TUTOR_ENABLED=false`. The review account correctly
retained Free quota of zero; disabled provider availability masked its plan
restriction in the existing status reason precedence.

## Changes

- Forward voice settings to the runtime env and add the scoped audio connection
  routes in personal-deploy PR 21. External MCP activation is unchanged.
- Validate activation booleans, require a model for enabled voice, retain the
  separate recording/bucket validation, and report non-secret activation flags.
- Distinguish server-reported voice unavailability from a transient fetch error
  in the iOS entry message. Actual fetch errors retain their specific message.
- User explicitly requested production recheck/redeployment after being told
  the default-off reason. Set voice enabled and recording disabled; redeploy
  the existing GitHub-built image, without changing the App Review candidate.
  This records operational authorization, not a claim of completed legal review.

## Verification

- Generic StudyMateiOS Debug device build passed with signing disabled.
- Both workflow copies passed YAML parsing, Bash syntax and voice env/route
  validation. All 9 scoped backend deployment tests passed.
- Physical iPhone was listed as unavailable, so the changed wording has not yet
  been verified on the device or installed over TestFlight build 118.
- Deployment repository PR: https://github.com/ghkdqhrbals/personal-deploy/pull/21
- Deployment source: `8f5829470d482d397f472000dc0cf58159c0b7db`.
- Backend image: `ghcr.io/ghkdqhrbals/buddystudy-backend:b316fa48cb1466f0d7f7606f5aeb05c39d883691-jvm`.
- Deployment run: https://github.com/ghkdqhrbals/personal-deploy/actions/runs/34819995799
  (completion and post-deploy status are recorded below when verified).
