# Realtime Mini development trial — 2026-09-13

The `dev` profile now defaults `buddystudy.voice-tutor.model` to
`gpt-realtime-2.1-mini`. An explicit `OPENAI_REALTIME_MODEL` continues to override
this value. The common/production default, question generation, grading,
transcription, summary models, Marin voice and 8,000-token conversation budget
are unchanged. New sessions copy this configured model into persisted session
state; existing sessions retain their recorded model.

## Provider verification

Using the existing development credential in memory, a bounded synthetic
Realtime WebSocket session verified:

- Model `gpt-realtime-2.1-mini` accepted the connection.
- Marin audio output completed with the exact transcript `Redis에서 TTL은 무엇인가요?`.
  Usage: 28 input / 91 output tokens (42 text and 49 audio output).
- A forced synthetic function call completed with exact `study_id: 7` and
  `language: ko`. Usage: 356 input / 115 output tokens.
- The provider accepted the 8,000-token post-instructions limit and 0.8 retention.
- No real MCP operation, app session, study mutation or answer submission was
  performed by this probe. It checks protocol compatibility, not Korean voice
  quality or autonomous tool selection across a full lesson.
- Credentials and audio bytes were not written to the report.

Local probe: `build/voice-mini-probe.mjs`; result:
`build/voice-mini-probe-results.json`.
Official model: https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini

## User trial

Start a new app conversation against the development backend. Check study
selection, question reading, interruption, answer submission and grading result
continuation. Compare actual input/output/cache usage in `/token-usage.html`.
Do not infer a fixed cost per minute from the synthetic probe. Subjective audio
quality and real-device interruption remain to be evaluated in this trial.

## Rollback

Set `OPENAI_REALTIME_MODEL=gpt-realtime-2.1` and restart the development backend
when no session is active, or revert the development profile change. An existing
session is not migrated between models while it is running.


## Build and local application

- Existing WebRTC MCP configuration, WebRTC adapter and PCM adapter tests:
  **146 passed**, 0 failures/errors/skipped. `:tutor:bootJar` succeeded.
- Inspected the packaged JAR: `application-dev.yml` contains the Mini default;
  common `application.yml` retains the original default.
- Running container uses `dev`, with no explicit `OPENAI_REALTIME_MODEL` or
  `BUDDYSTUDY_VOICE_TUTOR_MODEL` environment override.
- Active session count was 0 before stopping the local backend.
- Reused the existing image and app volume; no Docker image build or production
  deployment. Previous JAR is backed up at
  `/app/buddystudy-backend.pre-mini-20260913.jar` in the app volume.
- New JAR SHA-256:
  `8b3495e433e04c60115d682798876ad21b2918449bb516e5f2ffc59876288eaa`.
- Container restart: `2026-09-12T19:42:25.111873296Z`.
- `http://127.0.0.1:8080/actuator/health` returned `status: UP` after restart.
- Build output: `build/voice-mini-verification.log`.
