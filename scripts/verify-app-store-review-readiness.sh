#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
privacy_manifest="$project_root/StudyMate/Resources/PrivacyInfo.xcprivacy"
version_metadata="$project_root/app-store/metadata/version-localizations.json"
app_info_metadata="$project_root/app-store/metadata/app-info-localizations.json"
age_rating_metadata="$project_root/app-store/metadata/age-rating.json"
review_notes="$project_root/app-store/metadata/review-notes.txt"
resolution_reply="$project_root/app-store/metadata/resolution-center-reply.txt"
resubmission_guide="$project_root/docs/APP_STORE_REVIEW_RESUBMISSION_1.1.0.md"
testflight_notes="$project_root/app-store/metadata/testflight-build-localizations.json"
privacy_ko="$project_root/docs/privacy-2026-08-25.html"
privacy_en="$project_root/docs/en/privacy-2026-08-25.html"
privacy_ja="$project_root/docs/ja/privacy-2026-08-25.html"
admob_release_checklist="$project_root/docs/ADMOB_RELEASE_CHECKLIST.md"
app_ads_template="$project_root/docs/app-ads.txt.template"

plutil -lint "$privacy_manifest"
plutil -lint "$project_root/StudyMate.xcodeproj/project.pbxproj"

ruby -rjson -e '
  version = JSON.parse(File.read(ARGV.fetch(0)))
  app_info = JSON.parse(File.read(ARGV.fetch(1)))
  age_rating = JSON.parse(File.read(ARGV.fetch(2)))
  required = %w[ko en-US ja]
  privacy_urls = {
    "ko" => "https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-08-25.html",
    "en-US" => "https://ghkdqhrbals.github.io/buddy-studdy/en/privacy-2026-08-25.html",
    "ja" => "https://ghkdqhrbals.github.io/buddy-studdy/ja/privacy-2026-08-25.html"
  }
  abort "Missing version localization" unless (required - version.keys).empty?
  abort "Missing App Info localization" unless (required - app_info.keys).empty?
  required.each do |locale|
    abort "Missing subtitle: #{locale}" if app_info.dig(locale, "subtitle").to_s.strip.empty?
    url = app_info.dig(locale, "privacyPolicyUrl").to_s
    abort "Stale privacy URL: #{locale}" unless url == privacy_urls.fetch(locale)
    choices_url = app_info.dig(locale, "privacyChoicesUrl").to_s
    abort "Stale privacy choices URL: #{locale}" unless choices_url == "#{url}#ads"
    abort "Version description has stale privacy URL: #{locale}" unless version.dig(locale, "description").to_s.include?(url)
  end
  abort "Advertising age-rating flag must be true" unless age_rating["advertising"] == true
  abort "UGC age-rating flag must be true" unless age_rating["userGeneratedContent"] == true
  abort "Unrestricted web access must be false" unless age_rating["unrestrictedWebAccess"] == false
' "$version_metadata" "$app_info_metadata" "$age_rating_metadata"

test -s "$review_notes"
test "$(wc -c < "$review_notes" | tr -d " ")" -le 4000
test -s "$resolution_reply"
test "$(wc -c < "$resolution_reply" | tr -d " ")" -le 4000

ruby -I "$project_root/scripts/lib" -r app_store_review_notes -e '
  notes = File.read(ARGV.fetch(0))
  reply = File.read(ARGV.fetch(1))
  guide = File.read(ARGV.fetch(2))
  begin
    result = AppStoreReviewNotes.validate_review_package!(
      notes: notes,
      reply: reply,
      guide: guide,
      allow_recording_placeholders: true
    )
  rescue AppStoreReviewNotes::ValidationError => error
    abort error.message
  end
  if result.fetch(:status) == :pre_recording
    puts "App Review readiness: PRE-RECORDING TEMPLATE (video filename and tested physical device/OS/build list are pending)."
    puts "App Store Connect sync is intentionally blocked until both placeholders are replaced."
  else
    puts "App Review readiness: COMPLETE CANDIDATE (recording/device placeholders are resolved)."
  end
' "$review_notes" "$resolution_reply" "$resubmission_guide"

set +e
TESTFLIGHT_BUILD_NOTES_VALIDATE_ONLY=1 \
  ruby "$project_root/scripts/update-testflight-build-notes.rb"
testflight_notes_status=$?
set -e

for policy in "$privacy_ko" "$privacy_en" "$privacy_ja"; do
  test -s "$policy"
  rg -qi "block|차단|ブロック" "$policy"
done

privacy_policy_hash="$(ruby -rdigest -e 'print Digest::SHA256.file(ARGV.fetch(0)).hexdigest' "$privacy_ko")"
rg -q "$privacy_policy_hash" "$project_root/backend/tutor/src/main/resources/db/migration"
rg -q "$privacy_policy_hash" "$project_root/backend/tutor/src/main/resources/db/migration-mysql"
rg -q "9999-12-31" "$project_root/backend/tutor/src/main/resources/db/migration/V67__register_2026_08_25_privacy_policy.sql"
rg -q "9999-12-31" "$project_root/backend/tutor/src/main/resources/db/migration-mysql/V90__register_2026_08_25_privacy_policy.sql"

test -s "$admob_release_checklist"
rg -q "separate post-approval change" "$admob_release_checklist"
test -s "$app_ads_template"
rg -q '^google\.com, pub-REPLACE_WITH_ADMOB_PUBLISHER_ID, DIRECT, f08c47fec0942fa0$' "$app_ads_template"
if test -e "$project_root/docs/app-ads.txt" && rg -q 'REPLACE_WITH_ADMOB_PUBLISHER_ID' "$project_root/docs/app-ads.txt"; then
  echo "docs/app-ads.txt must never publish the placeholder publisher ID." >&2
  exit 1
fi
if test -e "$project_root/docs/app-ads.txt"; then
  rg -q '^google\.com, pub-[0-9]{16}, DIRECT, f08c47fec0942fa0$' "$project_root/docs/app-ads.txt"
fi

rg -q "PrivacyInfo.xcprivacy" "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q 'version = 13\.8\.0;' "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q '"version" : "13\.8\.0"' "$project_root/StudyMate.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
rg -q '<key>GADApplicationIdentifier</key>' "$project_root/StudyMate/iOSInfo.plist"
rg -q '<string>\$\(ADMOB_APP_ID\)</string>' "$project_root/StudyMate/iOSInfo.plist"
rg -q '<key>BuddyStudyAdMobNativeAdUnitID</key>' "$project_root/StudyMate/iOSInfo.plist"
rg -q '<string>\$\(ADMOB_NATIVE_AD_UNIT_ID\)</string>' "$project_root/StudyMate/iOSInfo.plist"
rg -q '<key>SKAdNetworkItems</key>' "$project_root/StudyMate/iOSInfo.plist"
test "$(rg -c '<key>SKAdNetworkIdentifier</key>' "$project_root/StudyMate/iOSInfo.plist")" -ge 50
rg -q 'AdMobPrivacyCoordinator\.shared\.prepareForAppLaunch()' "$project_root/StudyMate/StudyMateiOSApp.swift"
rg -q 'startMobileAdsIfAuthorized' "$project_root/StudyMate/Services/AdMobNativeAdvertising.swift"
test "$(rg -c 'MobileAds\.shared\.start\(\)' "$project_root/StudyMate/Services/AdMobNativeAdvertising.swift")" -eq 1
rg -q 'Validate AdMob configuration' "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q 'ca-app-pub-3940256099942544~1458002511' "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q 'ca-app-pub-3940256099942544/3986624511' "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q 'demo_publisher_prefix=.*ca-app-pub-3940256099942544' "$project_root/StudyMate.xcodeproj/project.pbxproj"
if rg -q 'NSUserTrackingUsageDescription|ATTrackingManager|AppTrackingTransparency' "$project_root/StudyMate" "$project_root/StudyMate.xcodeproj/project.pbxproj"; then
  echo "ATT framework, prompt, and NSUserTrackingUsageDescription are forbidden for this release." >&2
  exit 1
fi
test "$(/usr/libexec/PlistBuddy -c 'Print :NSPrivacyTracking' "$privacy_manifest")" = "false"
for collected_type in \
  NSPrivacyCollectedDataTypeCoarseLocation \
  NSPrivacyCollectedDataTypeDeviceID \
  NSPrivacyCollectedDataTypeProductInteraction \
  NSPrivacyCollectedDataTypeAdvertisingData \
  NSPrivacyCollectedDataTypeCrashData \
  NSPrivacyCollectedDataTypePerformanceData \
  NSPrivacyCollectedDataTypeOtherDiagnosticData; do
  rg -q "$collected_type" "$privacy_manifest"
done
rg -q 'ADMOB_APP_ID: \$\{\{ vars\.ADMOB_APP_ID \}\}' "$project_root/.github/workflows/release.yml"
rg -q 'ADMOB_NATIVE_AD_UNIT_ID: \$\{\{ vars\.ADMOB_NATIVE_AD_UNIT_ID \}\}' "$project_root/.github/workflows/release.yml"
rg -Fq 'ca-app-pub-3940256099942544~*' "$project_root/.github/workflows/release.yml"
rg -Fq 'ca-app-pub-3940256099942544/*' "$project_root/.github/workflows/release.yml"
rg -q 'admob_app_publisher=.*ADMOB_APP_ID' "$project_root/.github/workflows/release.yml"
rg -q 'admob_unit_publisher=.*ADMOB_NATIVE_AD_UNIT_ID' "$project_root/.github/workflows/release.yml"
rg -q 'docs/app-ads\.txt must match the publisher' "$project_root/.github/workflows/release.yml"
rg -q 'Gem::Version\.new\("26\.2"\)' "$project_root/.github/workflows/release.yml"
rg -Fq 'native-ad-placement-policies/${COMMUNITY_FEED_PLACEMENT}' "$project_root/monitoring/api-dashboard/src/pages/AdvertisingPage.jsx"
rg -q '\? "UNKNOWN" : placementStatus' "$project_root/monitoring/api-dashboard/src/pages/AdvertisingPage.jsx"
rg -q 'contentHash: contentHash' "$project_root/StudyMate/Services/RemotePushBackendClient.swift"
rg -q "com.apple.developer.applesignin" "$project_root/StudyMate/StudyMateiOS.entitlements"
rg -q "Delete Account|회원탈퇴|アカウント" "$project_root/StudyMate/Views" "$project_root/StudyMate/Models"
rg -q "Report|신고|報告" "$project_root/StudyMate"
rg -q "Block User|사용자 차단|ユーザーをブロック" "$project_root/StudyMate"
rg -q "public-user:block" "$project_root/backend"
rg -q "membershipAutoRenewalDisclosure" "$project_root/StudyMate/Models/StudyModels.swift"
rg -q "subscriptionDisclosure" "$project_root/StudyMate/Views/MobileRootView.swift"
rg -q "AppLegalLinks.termsOfServiceURL" "$project_root/StudyMate/Views/MobileRootView.swift"
rg -q "AppLegalLinks.privacyPolicyURL" "$project_root/StudyMate/Views/MobileRootView.swift"
rg -q 'buddy-studdy/privacy-2026-08-25\.html' "$project_root/StudyMate/Models/StudyModels.swift"
rg -q 'buddy-studdy/en/privacy-2026-08-25\.html' "$project_root/StudyMate/Models/StudyModels.swift"
rg -q 'buddy-studdy/ja/privacy-2026-08-25\.html' "$project_root/StudyMate/Models/StudyModels.swift"
rg -q '<key>BuddyStudyBackendBaseURL</key>' "$project_root/StudyMate/iOSInfo.plist"
rg -q 'BUDDYSTUDY_BACKEND_BASE_URL = "https://lowfidev\.cloud";' "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q 'BUDDYSTUDY_BACKEND_BASE_URL = "https://api\.ghkdqhrbals\.org";' "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q 'PRODUCTION_BACKEND_BASE_URL: https://api\.ghkdqhrbals\.org' "$project_root/.github/workflows/release.yml"
rg -q 'App Review candidate build' "$testflight_notes"
rg -q 'App Store 심사용 후보 빌드' "$testflight_notes"
rg -q 'App Store審査用の候補ビルド' "$testflight_notes"
rg -q 'production BuddyStudy API at https://api\.ghkdqhrbals\.org' "$review_notes"
rg -qi "block" "$review_notes"
rg -q "privacy-2026-08-25\.html" "$review_notes"
rg -q "terms-2026-07-30\.html" "$review_notes"
rg -q "300" "$review_notes"
rg -q "1,000" "$review_notes"
rg -q "1\. COMPLETE PHYSICAL-DEVICE VIDEO" "$resolution_reply"
rg -q "8\. IN-APP PURCHASES AND PURCHASE LOCATION" "$resolution_reply"

echo "App Store review source checks passed."
echo "Manual gate: verify the production RevenueCat webhook delivers both Production and Sandbox events without a duplicate development integration."
echo "Manual gate: verify the published root app-ads.txt, AdMob account review, and app readiness authorization before enabling production ads."

if [ "$testflight_notes_status" -ne 0 ]; then
  exit "$testflight_notes_status"
fi
