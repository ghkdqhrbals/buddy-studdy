# Guideline 3.1.1 resubmission — 2026-09-15

Apple rejected 1.1.0 (118) on September 14 for external tipping under
Guideline 3.1.1. Submission: `26e49ef8-1a93-41aa-bc2e-6298f6d1d5f5`.

The iOS Settings footer now contains only Feedback. The Ko-fi URL, tipping
control, separator and unused Korean/English/Japanese tipping strings were
removed for every storefront. Existing StoreKit subscription flows are unchanged.
TestFlight notes in all three locales identify the removal and its check.

Source baseline: `origin/main` at `1d57ac12`; changes are isolated on
`codex/remove-external-tipping` because the original local main checkout is old
and contains unrelated user changes. No backend deployment is required.

Verification:
- App Store source readiness script passed using Ruby 3.0.0.
- No Ko-fi, Tip Me or Support developer reference remains in app source.
- Generic iOS Debug build passed with `StudyMateiOS`, generic iOS destination,
  and code signing disabled.
- Two existing BillingLocalizationTests passed on physical iPhone 16 Pro,
  iOS 26.6.2 (23G90): Japanese membership/billing labels and monthly-only catalog.
  This was a development-signed build of the replacement source, not TestFlight.
- Current physical-iPhone visual verification: pending Mac unlock.
- Physical iPad is unavailable; no new iPad verification is claimed.

The historical build-118 recording and its stated limitations remain intact.
The September 13 submission record documents the user's earlier decision to
submit with those limitations. This current request explicitly authorizes
removal, supplementation and resubmission; do not claim the historical video
shows the replacement binary. Keep release mode MANUAL.

The focused response is `app-store/metadata/resolution-center-reply-3.1.1.txt`.
Release run `34892803983` succeeded for 1.1.0 (119), source `5edcd932`.
The exported IPA identifies the production API and contains no Ko-fi URL or
tipping strings in its executable. Apple build
`7d4799d3-195a-4497-9dec-e9e210ff81b7` is VALID and selected for version
`f1fd5f81-f24c-46c9-be83-41f0f2406691`; release mode is MANUAL.

Review Notes were updated and read back exactly. Demo credentials and contact
fields were preserved. Historical video attachment remains COMPLETE.
Actual resubmission is pending.
