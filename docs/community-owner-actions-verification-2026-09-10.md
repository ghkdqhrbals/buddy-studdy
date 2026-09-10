# Own public-question options — 2026-09-10

## Behavior and cause

My public questions expose Make private and Delete, with no Edit, Report or
Block action. Other users' questions retain Report and Block. The same policy
is used by feed/context menus and public-question detail.

A reproducible cold-start path restored the signed-in state before the profile
request completed. At that point communityProfile was nil and the fallback
backend user ID was zero. Comparing the public author to those unresolved local
IDs returned false, which incorrectly meant “another user's question” and showed
moderation actions on the owner's question.

The authenticated backend now returns isOwnedByMe using the canonical record
user ID and current request principal. Shared mapping covers list, detail, v2
feed/search, liked pages, generated questions and voice records. Missing public
author display data does not affect ownership. The flag is viewer-specific like
isLikedByMe; the existing account/session/backend cache boundaries still apply.

The iOS decoder preserves true, false and an absent legacy field separately.
The runtime bean mapper initially emitted ownedByMe instead of isOwnedByMe.
An explicit JsonProperty fixes the wire name; both Kotlin and plain bean mapper
tests cover it. iOS also accepts the ownedByMe alias for compatibility, while an
explicit canonical false takes precedence over that alias.
Server ownership takes precedence. For old servers only, a single known positive
viewer ID and author ID may resolve ownership. Unknown or conflicting identity
is not treated as another user and exposes no destructive option. Signed-out
state cannot reuse an owned response to manage a record. Management actions
recheck the captured identity around record loading, and self/unknown reports are
rejected before networking. Existing canonical delete/publicity handlers continue
to authorize the actual mutation.

No editing API or editor was added. No user record was deleted, privatized,
reported or blocked during verification.

## Verification

- Generic iOS Debug build (StudyMateiOS, generic/platform=iOS, signing disabled):
  passed, build/community-owner-actions/ios-generic-final.log.
- iPhone 16 Pro / iOS 26.6.1: 67 tests, zero failures or skips.
- iOS simulator: the same 67 tests, zero failures or skips.
- Suites: CommunityQuestionActionPolicyTests 8, VoiceCommonRecordTests 56,
  CommunityFeedBlockingTests 3. The cold-start AppState regression confirms
  profile=nil/user ID=0, server-owned management-only actions, other-user
  moderation, unknown legacy actions hidden, zero HTTP calls and draft retention.
- Backend: CommunityServiceTest 31 and PublicQuestionProjectionMappersTest 2,
  zero failures/errors/skips; followed by successful tutor bootJar.
  Tests cover ownership for all shared read routes, missing author data,
  owner/other/unauthenticated viewers, equal display names, voice records,
  and the exact Boolean JSON field spelling.
- Independent diff review and git diff --check passed.
- Result bundles: build/community-owner-actions/ios-device-final.xcresult and
  ios-simulator-final.xcresult; backend log: backend-wire-fix.log in that directory.

## Local rollout

The local database showed zero READY/ACTIVE/ENDING voice sessions before restart.
The existing dev application volume/JDK container was reused without an image
build, schema migration, production deployment or direct SSH.

New JAR SHA-256:
01f56cacc8f4bcceb76fb05733055244a6fadfd974a658a55e203d14f53f183e

Previous JAR was preserved in the same volume as
/app/buddystudy-backend.pre-owner-actions-20260910.jar, SHA-256:
0a9fd4b61b80a6007f28b348a06fcfd7d0fc4a55a17ce03ca5c7f9b67ef9c93c

The final backend restarted at 16:45:29 KST. Local and dev-tunnel health passed;
the tunnel response at 16:45:48 KST reported environment=dev, database=true and
redis=true. The local public-question HTTP response returned status 200 with
Boolean isOwnedByMe=false for an anonymous viewer and no ownedByMe alias. The
anonymous public-question probe through the tunnel returned 403, so JSON wire
verification used the local endpoint; routing was not changed.

The signed final app was installed by the physical-device tests and launched on
the user's iPhone at 16:46:31 KST with the existing https://lowfidev.cloud backend.
Verification covered decoding, cold-start AppState and action policy, without
executing live user-record management or moderation actions.
