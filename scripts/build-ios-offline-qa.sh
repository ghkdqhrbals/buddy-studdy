#!/usr/bin/env bash
set -euo pipefail

# Build only. Never install, launch, register an App ID, or update provisioning.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
qa_bundle_id='io.github.ghkdqhrbals.StudyMate.OfflineQA'
production_bundle_id='io.github.ghkdqhrbals.StudyMate'
fixture='feed'
language='ko'
platform='device'
prepare_only=0
derived_data_path=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prepare-only) prepare_only=1; shift ;;
    --simulator) platform='simulator'; shift ;;
    --fixture) fixture="${2:?Missing fixture}"; shift 2 ;;
    --language) language="${2:?Missing language}"; shift 2 ;;
    --derived-data-path) derived_data_path="${2:?Missing existing DerivedData path}"; shift 2 ;;
    --help)
      echo 'Usage: build-ios-offline-qa.sh [--prepare-only] [--simulator] [--fixture feed|interests|learning-result|statistics|study-tree|study-list|records|membership|follow-up|follow-up-pending|custom-question|custom-records] [--language ko|en|ja] [--derived-data-path existing-cache]'
      exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$fixture" in
  feed|interests|learning-result|statistics|study-tree|study-list|records|membership|follow-up|follow-up-pending|custom-question|custom-records) ;;
  *) echo 'Unknown fixture.' >&2; exit 2 ;;
esac
case "$language" in ko|en|ja) ;; *) echo 'Select ko, en or ja.' >&2; exit 2 ;; esac
[[ "$qa_bundle_id" != "$production_bundle_id" ]] || exit 2

if [[ -n "$derived_data_path" ]]; then
  derived_data_path="$(python3 - "$derived_data_path" "$repo_root" <<'PY'
import pathlib, plistlib, sys
candidate = pathlib.Path(sys.argv[1]).expanduser().resolve()
repo = pathlib.Path(sys.argv[2]).resolve()
metadata = candidate / 'info.plist'
if not metadata.is_file() or not (candidate / 'Build').is_dir():
    raise SystemExit('Cache reuse requires an existing Xcode DerivedData directory.')
info = plistlib.loads(metadata.read_bytes())
workspace = pathlib.Path(info.get('WorkspacePath', '')).resolve()
if not workspace.is_relative_to(repo / 'StudyMate.xcodeproj'):
    raise SystemExit('Refusing a DerivedData cache belonging to another workspace.')
print(candidate)
PY
  )"
fi

build_root="$(mktemp -d "${TMPDIR:-/tmp}/buddystudy-offline-qa.XXXXXX")"
derived_data_path="${derived_data_path:-$build_root/DerivedData}"
config_root="$build_root/Configuration"
mkdir -p "$config_root"

python3 - "$repo_root" "$config_root" "$fixture" "$language" "$qa_bundle_id" <<'PY'
import pathlib, plistlib, sys
repo, output = map(pathlib.Path, sys.argv[1:3])
fixture, language, bundle = sys.argv[3:6]
assert bundle == 'io.github.ghkdqhrbals.StudyMate.OfflineQA'
info = plistlib.loads((repo / 'StudyMate/iOSInfo.plist').read_bytes())
info.update({
    'CFBundleIdentifier': bundle,
    'CFBundleDisplayName': 'BuddyStudy QA',
    'CFBundleURLTypes': [{'CFBundleURLName': bundle, 'CFBundleURLSchemes': ['buddystudy-offline-qa']}],
    'BuddyStudyOfflineQA': True,
    'BuddyStudyScreenshotFixture': fixture,
    'BuddyStudyScreenshotLanguage': language,
    'BuddyStudyBackendBaseURL': 'http://127.0.0.1:9',
    'FirebaseAnalyticsCollectionEnabled': False,
    'FirebaseDataCollectionDefaultEnabled': False,
})
for key in ['SentryDSN', 'RevenueCatPublicSDKKey', 'GoogleOAuthClientID',
            'GoogleOAuthRedirectScheme', 'UIBackgroundModes', 'BGTaskSchedulerPermittedIdentifiers']:
    info.pop(key, None)
(output / 'Info.plist').write_bytes(plistlib.dumps(info))
(output / 'OfflineQA.entitlements').write_bytes(plistlib.dumps({}))
# Verify isolation before any build. These checks also run in --prepare-only mode.
assert info['CFBundleIdentifier'] != 'io.github.ghkdqhrbals.StudyMate'
assert info['CFBundleDisplayName'] == 'BuddyStudy QA'
assert info['CFBundleURLTypes'][0]['CFBundleURLSchemes'] == ['buddystudy-offline-qa']
assert not plistlib.loads((output / 'OfflineQA.entitlements').read_bytes())
assert not any(key in info for key in ['SentryDSN', 'RevenueCatPublicSDKKey', 'GoogleOAuthClientID'])
print('PASS: distinct QA identifier/name/scheme, empty capability entitlements, no service credentials.')
PY

destination='generic/platform=iOS Simulator'
products='Debug-iphonesimulator'
if [[ "$platform" == device ]]; then
  destination='generic/platform=iOS'
  products='Debug-iphoneos'
  # Select an existing, unexpired wildcard profile backed by a local identity.
  # Keep the matched certificate/profile pair for local signing after the build.
  # No signing override is passed to Xcode or its package resource targets.
  python3 - "$config_root" <<'PY'
import datetime, hashlib, json, pathlib, plistlib, re, subprocess, sys
result = subprocess.run(['security', 'find-identity', '-v', '-p', 'codesigning'],
                        check=True, capture_output=True, text=True)
identities = set(re.findall(r'\b[0-9A-F]{40}\b', result.stdout))
candidates = []
for root in [pathlib.Path.home() / 'Library/Developer/Xcode/UserData/Provisioning Profiles',
             pathlib.Path.home() / 'Library/MobileDevice/Provisioning Profiles']:
    for path in root.glob('*.mobileprovision'):
        result = subprocess.run(['security', 'cms', '-D', '-i', str(path)], capture_output=True)
        if result.returncode: continue
        profile = plistlib.loads(result.stdout)
        entitlements = profile.get('Entitlements', {})
        expiry = profile.get('ExpirationDate', datetime.datetime.min)
        if entitlements.get('application-identifier') != '4CL25TC734.*': continue
        if not entitlements.get('get-task-allow') or not profile.get('ProvisionedDevices'): continue
        if expiry <= datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None): continue
        matches = sorted(hashlib.sha1(cert).hexdigest().upper()
                         for cert in profile.get('DeveloperCertificates', [])
                         if hashlib.sha1(cert).hexdigest().upper() in identities)
        if not matches: continue
        candidates.append((expiry, profile['UUID'], str(path), matches[0]))
if not candidates:
    raise SystemExit('No usable local wildcard development profile; nothing was registered or changed.')
_, uuid, profile_path, identity = max(candidates)
output = pathlib.Path(sys.argv[1]) / 'Signing.json'
output.write_text(json.dumps({'profileUUID': uuid, 'profilePath': profile_path, 'identity': identity}))
output.chmod(0o600)
print('PASS: existing wildcard profile matches a usable local signing identity.')
PY
fi

# Do not override product names, bundle identifiers or Info.plists globally:
# those overrides also reach Swift Package resource bundles. Compile normally,
# unsigned; only the copied app below becomes the separately identified QA app.
build_command=(xcodebuild -project "$repo_root/StudyMate.xcodeproj" -scheme StudyMateiOS
  -configuration Debug -destination "$destination" -derivedDataPath "$derived_data_path"
  CODE_SIGNING_ALLOWED=NO ENABLE_DEBUG_DYLIB=NO BUDDYSTUDY_FIREBASE_PLIST_PATH= build)
printf 'QA build directory: %s\n' "$build_root"
printf 'Build cache: %s\n' "$derived_data_path"
if [[ "$prepare_only" == 1 ]]; then
  echo 'Prepared and checked QA configuration only. No build/install/launch was performed.'
  exit 0
fi

"${build_command[@]}"
app_path="$build_root/QA/BuddyStudyOfflineQA.app"
python3 - "$derived_data_path/Build/Products/$products/StudyMate.app" "$app_path" "$qa_bundle_id" "$platform" "$config_root" <<'PY'
import hashlib, json, pathlib, plistlib, shutil, subprocess, sys
unsigned, app = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
bundle, platform, config = sys.argv[3], sys.argv[4], pathlib.Path(sys.argv[5])
assert bundle == 'io.github.ghkdqhrbals.StudyMate.OfflineQA'
assert unsigned.is_dir() and not app.exists()
assert not list(unsigned.rglob('*.appex')), 'Embedded extensions require separate QA identities'
app.parent.mkdir(parents=True, exist_ok=True)
shutil.copytree(unsigned, app, symlinks=True)
info = plistlib.loads((app / 'Info.plist').read_bytes())
# A reused cache may retain Xcode's prior preview/debug dylibs. The QA build
# explicitly links a normal executable; discard only unreferenced preview code
# from our copy, never from the cache or the user's installed application.
executable = app / info['CFBundleExecutable']
dependencies = subprocess.run(['otool', '-L', str(executable)],
                              check=True, capture_output=True, text=True).stdout
for name in [info['CFBundleExecutable'] + '.debug.dylib', '__preview.dylib']:
    assert name not in dependencies, 'QA executable still depends on Xcode preview code'
    (app / name).unlink(missing_ok=True)
template = plistlib.loads((config / 'Info.plist').read_bytes())
for key in ['CFBundleIdentifier', 'CFBundleDisplayName', 'CFBundleURLTypes',
            'BuddyStudyOfflineQA', 'BuddyStudyScreenshotFixture', 'BuddyStudyScreenshotLanguage',
            'BuddyStudyBackendBaseURL', 'FirebaseAnalyticsCollectionEnabled', 'FirebaseDataCollectionDefaultEnabled']:
    info[key] = template[key]
for key in ['SentryDSN', 'RevenueCatPublicSDKKey', 'GoogleOAuthClientID', 'GoogleOAuthRedirectScheme',
            'UIBackgroundModes', 'BGTaskSchedulerPermittedIdentifiers']:
    info.pop(key, None)
(app / 'Info.plist').write_bytes(plistlib.dumps(info))
# Do not inherit a source configuration or old profile in the isolated copy.
for name in ['GoogleService-Info.plist', 'embedded.mobileprovision']:
    (app / name).unlink(missing_ok=True)
assert info['CFBundleIdentifier'] == bundle == 'io.github.ghkdqhrbals.StudyMate.OfflineQA'
assert info['CFBundleDisplayName'] == 'BuddyStudy QA' and info['BuddyStudyOfflineQA'] is True
assert info['CFBundleURLTypes'][0]['CFBundleURLSchemes'] == ['buddystudy-offline-qa']
assert len(info['CFBundleURLTypes']) == 1
assert not any(key in info for key in ['SentryDSN', 'RevenueCatPublicSDKKey', 'GoogleOAuthClientID'])
if platform == 'device':
    signing = json.loads((config / 'Signing.json').read_text())
    profile_path = pathlib.Path(signing['profilePath'])
    result = subprocess.run(['security', 'cms', '-D', '-i', str(profile_path)], check=True, capture_output=True)
    profile = plistlib.loads(result.stdout)
    assert profile['UUID'] == signing['profileUUID']
    assert profile['Entitlements']['application-identifier'] == '4CL25TC734.*'
    assert signing['identity'] in {hashlib.sha1(cert).hexdigest().upper()
                                   for cert in profile['DeveloperCertificates']}
    shutil.copyfile(profile_path, app / 'embedded.mobileprovision')
    expected_entitlements = {
        'application-identifier': '4CL25TC734.' + bundle,
        'com.apple.developer.team-identifier': '4CL25TC734',
        'get-task-allow': True,
        'keychain-access-groups': ['4CL25TC734.' + bundle],
    }
    entitlement_path = config / 'SignedOfflineQA.entitlements'
    entitlement_path.write_bytes(plistlib.dumps(expected_entitlements))
    # Sign nested code inside-out, without propagating the app's entitlements.
    nested = set(app.rglob('*.framework')) | set(app.rglob('*.dylib'))
    for code in sorted(nested, key=lambda path: (len(path.parts), str(path)), reverse=True):
        assert code.resolve().is_relative_to(app.resolve()), 'Nested code escaped QA app'
        subprocess.run(['codesign', '--force', '--sign', signing['identity'], '--timestamp=none', str(code)],
                       check=True, capture_output=True)
    subprocess.run(['codesign', '--force', '--sign', signing['identity'], '--timestamp=none',
                    '--generate-entitlement-der', '--entitlements', str(entitlement_path), str(app)],
                   check=True, capture_output=True)
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(app)], check=True)
    result = subprocess.run(['codesign', '-d', '--entitlements', ':-', str(app)],
                            check=True, capture_output=True)
    entitlements = plistlib.loads(result.stdout)
    assert entitlements['application-identifier'] == '4CL25TC734.' + bundle
    assert entitlements.get('get-task-allow') is True
    assert entitlements == expected_entitlements, 'Unexpected capability in QA app'
    # Verify the final leaf certificate is the same local identity selected above.
    certificate_prefix = str(config / 'VerifiedSigningCertificate')
    subprocess.run(['codesign', '-d', '--extract-certificates=' + certificate_prefix, str(app)],
                   check=True, capture_output=True)
    assert hashlib.sha1(pathlib.Path(certificate_prefix + '0').read_bytes()).hexdigest().upper() == signing['identity']
else:
    subprocess.run(['codesign', '--force', '--sign', '-', str(app)], check=True, capture_output=True)
print('PASS: built app isolation checks. This is fixture rendering evidence only.')
PY
printf 'QA app (not installed): %s\n' "$app_path"
