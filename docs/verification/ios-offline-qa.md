# Separate iOS fixture app

The offline QA build is for inspecting actual SwiftUI layout and navigation with
synthetic fixture data while retaining the installed TestFlight app and its
drafts. It is not an App Store candidate or a backend integration test.

```sh
# Read-only signing inspection and temporary QA plist generation; no build.
bash scripts/build-ios-offline-qa.sh --prepare-only

# Build a separately signed device app; never installs or launches it.
bash scripts/build-ios-offline-qa.sh --fixture feed --language ko

# The same isolated app for a disposable simulator.
bash scripts/build-ios-offline-qa.sh --simulator --fixture study-tree --language en
```

The script fixes the bundle ID to
`io.github.ghkdqhrbals.StudyMate.OfflineQA`, display name to `BuddyStudy QA`, and
URL scheme to `buddystudy-offline-qa`. It generates a temporary Info.plist and
empty capability entitlements without changing the project or production plist.
Existing wildcard development provisioning and a matching local signing
identity are required for device builds. It never enables provisioning updates,
registers identifiers, uploads, installs, launches or replaces an existing app.
After a device build, it verifies the signature, exact QA identifier, isolated
keychain group and absence of production capabilities. A build failure is not
permission to reuse the production bundle identifier or install the regular app.

The DEBUG-only bundle marker makes fixture mode survive a normal app relaunch;
`--fixture` and `--language` select the next build's initial fixture. Existing
`BUDDYSTUDY_SCREENSHOT_FIXTURE` and `BUDDYSTUDY_SCREENSHOT_LANGUAGE` simulator
launch overrides still work. A Release build never reads the QA marker.

In fixture mode, startup returns before billing listeners or backend startup
work. Firebase initialization, Analytics, Sentry, RevenueCat and AdMob consent
preparation are disabled. The common backend transport rejects requests before
logging or sending them, including writes reached by interacting with a fixture.
The QA plist also removes Google login configuration, SDK configuration keys and
background task declarations. Its backend URL is a non-service loopback address.
The separate app has its own settings, records and bundle-scoped backend Keychain
identity; no personal preferences or credentials are copied into it.

Fixture actions that require a backend can display an unavailable-network error;
they are not simulated successful mutations. The public-feed fixture keeps its
six original samples so search clearing restores the rows. Search matches local
question/topic text; Following uses the synthetic interests; date/views/likes
sort and bounded paging apply only to those samples. Recommended preserves the
authored order and is not an implementation of production ranking. This route does not establish
login, account isolation on the server, purchase/restore, Voice calls, push,
CloudKit, Universal Links or live analytics delivery. External links deliberately
opened in another app are outside the fixture transport boundary. Do not sign in,
purchase, or substitute real user data to turn a rendering check into a live test.

The existing `capture-growth-screenshots.sh` targets the production bundle ID on
a disposable simulator and does not target this QA app. Do not use that script
on a simulator containing personal drafts. Likewise, the regular hosted
`StudyMateiOSTests` device command would install the normal host app; use a
disposable simulator for those tests while preserving TestFlight on the phone.
Source-file inspection tests must run on the simulator, where repository paths
are readable. Physical QA installation and visual verification are separate
explicit steps and must target only the script's verified QA artifact.

The builder's static/preparation checks are not evidence of a successful compile,
device installation or visual pass. Record those outcomes separately with the
source revision, device/OS, fixture and language.
