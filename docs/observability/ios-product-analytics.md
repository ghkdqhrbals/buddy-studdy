# iOS Product Analytics

BuddyStudy collects coarse iOS product events through Firebase Analytics. The
same Firebase Apple app configuration is also used by Firebase Remote Config
for maintenance and update control; Remote Config does not widen the analytics
payload boundary below. Monitoring pages, the backend, and the macOS target do
not use the Analytics integration.

## SDK and privacy boundary

- Package: Firebase Apple SDK `12.17.0`
- Product: `FirebaseAnalyticsCore`
- Advertising identifier support: not linked
- Automatic SwiftUI screen reporting: disabled
- Firebase user ID: not set
- Debug collection: disabled unless `BUDDYSTUDY_GA_DEBUG=1`

The analytics API accepts fixed enums instead of arbitrary payload dictionaries. Do not add question text, answers, study or topic names, email addresses, profile values, access tokens, device IDs, APNs tokens, request IDs, correlation IDs, or raw error text.

## Event catalog

| Event | Parameters |
| --- | --- |
| `screen_view` | Fixed `screen_name`, `SwiftUI` screen class |
| `login_flow` | `method`: Google or email, `outcome`: started/completed/failed/cancelled/verification required |
| `study_created` | Root study or descendant topic |
| `question_requested` | Manual or scheduled |
| `question_completed` | Manual or scheduled |
| `question_failed` | Manual or scheduled |
| `answer_submitted` | None |
| `answer_grading_completed` | None |
| `answer_grading_failed` | None |
| `notification_opened` | Question, community, or general |

User properties are limited to app language and signed-in/signed-out state.

## Release configuration

1. Register the iOS bundle ID `io.github.ghkdqhrbals.StudyMate` in Firebase.
2. Enable Google Analytics for that Firebase project.
3. Download `GoogleService-Info.plist`.
4. Base64-encode the file and store it as the GitHub Actions Secret `GOOGLE_SERVICE_INFO_PLIST_BASE64`.
5. Update the App Store privacy disclosure before enabling production collection.
6. Run the iOS release workflow.

The workflow validates the plist, bundle ID, Google app ID, API key, and
Firebase project ID. Firebase's generated Apple configuration can contain
`IS_ANALYTICS_ENABLED=false` even when the project is linked to Google
Analytics, so collection is enabled explicitly through
`Analytics.setAnalyticsCollectionEnabled(true)` after the project and app
identifiers pass validation. Remote Config initialization is independent of
the debug Analytics collection switch. The committed plist remains a
nonfunctional placeholder so local builds cannot accidentally connect to an
unknown project.

## Verification

For an explicit development verification, install a real Firebase plist locally and launch with `BUDDYSTUDY_GA_DEBUG=1`. Verify events in Firebase DebugView, then remove the real plist before committing. Normal local builds must remain analytics-disabled.
