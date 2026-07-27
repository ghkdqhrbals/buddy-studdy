# Question Localization

## Current Contract

BuddyStudy currently supports Korean and English application content.

- `questions.language`, `topic`, `question`, and `hint` are the canonical generated source.
- `topic_en`, `question_en`, and `hint_en` are a derived English snapshot.
- English feed, record, push, and notification projections select the complete English snapshot.
- A missing English topic falls back to the source only while legacy data is waiting for backfill.
- Static iOS labels remain in `AppStrings`; backend content translation does not replace UI-string localization.

The topic, question, and hint are translated together for new questions. This keeps a feed label aligned with the terminology used in the question instead of translating each surface independently.

## Provider Failover

`QuestionTranslationService` depends only on `QuestionTranslationPort`. The infrastructure adapter resolves that port through an ordered provider chain:

1. `openai`
2. `libretranslate`

The order is configured with `BUDDYSTUDY_TRANSLATION_PROVIDER_ORDER`. LibreTranslate uses `BUDDYSTUDY_TRANSLATION_BASE_URL` and an optional `BUDDYSTUDY_TRANSLATION_API_KEY`.

Each provider is attempted once per event-processing attempt. The adapter validates non-empty topic content and an English question before accepting a result. A timeout or invalid result moves immediately to the next provider. The Redis Stream Inbox lease owns the outer retry policy, so provider clients must not add another exponential retry loop.

Metrics use the bounded labels `provider` and `outcome`:

```text
buddystudy.translation.requests{provider="openai",outcome="success"}
buddystudy.translation.requests{provider="libretranslate",outcome="failure"}
```

No question text, user identifier, API key, or translated content is placed in metric labels.

## Existing Data Backfill

Migration V16 adds `topic_en`. English-source rows are filled directly from `topic`.

For previously translated Korean questions, `question-topic-translation-backfill`:

- selects at most the configured batch size;
- processes only `READY` rows with `question_en` present and `topic_en` missing;
- runs network translation outside a database transaction;
- writes only `topic_en` with an idempotent `topic_en is null` condition;
- uses the managed-job advisory lock so multiple backend instances do not run the batch together;
- retries failed rows on a later run without blocking successful rows.

The batch is intentionally bounded to avoid a deployment-time translation spike.

## Locale Resolution

Backend content locale resolution follows:

1. explicit API language parameter;
2. authenticated user's `appLanguage`;
3. normalized base language (`en-US` becomes `en`);
4. canonical Korean source as the final fallback.

Dates and relative times are formatted by iOS using the selected locale and timezone. The backend returns instants, not preformatted English or Korean date strings.

## Adding More Languages

Do not add `topic_ja`, `question_ja`, `hint_ja`, and similar columns. When a third content language is introduced, migrate derived snapshots to:

```text
question_localizations
- question_id
- locale
- topic
- question
- hint
- status
- provider
- source_hash
- translation_version
- created_at
- updated_at

primary key (question_id, locale)
```

`source_hash` invalidates stale localizations when canonical content changes. `translation_version` supports controlled retranslation after prompt or provider changes. The canonical question remains the source of truth; localized rows are replaceable projections.

Study-tree topic names should use the same pattern through `study_localizations` when translated navigation is introduced. Public search indexes must be partitioned by locale so an English query does not mix Korean source tokens into ranking.

## Product Boundaries

- Generated topics, questions, hints, notification text, and public-feed projections are localizable.
- User answers and comments are not automatically translated without an explicit product opt-in.
- AI-generated grading feedback is requested directly in the user's selected language rather than translated after grading.
- Provider failure must never return a partially mixed topic/question snapshot as `READY`.
- New locale rollout requires translation-quality fixtures for technical terms, code, Markdown, and short topic labels before the locale is enabled for users.
