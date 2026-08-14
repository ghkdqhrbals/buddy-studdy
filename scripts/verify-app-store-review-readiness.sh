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
privacy_ko="$project_root/docs/privacy-2026-08-14.html"
privacy_en="$project_root/docs/en/privacy-2026-08-14.html"
privacy_ja="$project_root/docs/ja/privacy-2026-08-14.html"

plutil -lint "$privacy_manifest"
plutil -lint "$project_root/StudyMate.xcodeproj/project.pbxproj"

ruby -rjson -e '
  version = JSON.parse(File.read(ARGV.fetch(0)))
  app_info = JSON.parse(File.read(ARGV.fetch(1)))
  age_rating = JSON.parse(File.read(ARGV.fetch(2)))
  required = %w[ko en-US ja]
  privacy_urls = {
    "ko" => "https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-08-14.html",
    "en-US" => "https://ghkdqhrbals.github.io/buddy-studdy/en/privacy-2026-08-14.html",
    "ja" => "https://ghkdqhrbals.github.io/buddy-studdy/ja/privacy-2026-08-14.html"
  }
  abort "Missing version localization" unless (required - version.keys).empty?
  abort "Missing App Info localization" unless (required - app_info.keys).empty?
  required.each do |locale|
    abort "Missing subtitle: #{locale}" if app_info.dig(locale, "subtitle").to_s.strip.empty?
    url = app_info.dig(locale, "privacyPolicyUrl").to_s
    abort "Stale privacy URL: #{locale}" unless url == privacy_urls.fetch(locale)
    abort "Version description has stale privacy URL: #{locale}" unless version.dig(locale, "description").to_s.include?(url)
  end
  abort "UGC age-rating flag must be true" unless age_rating["userGeneratedContent"] == true
  abort "Unrestricted web access must be false" unless age_rating["unrestrictedWebAccess"] == false
' "$version_metadata" "$app_info_metadata" "$age_rating_metadata"

test -s "$review_notes"
test "$(wc -m < "$review_notes" | tr -d " ")" -le 4000
test -s "$resolution_reply"
test "$(wc -m < "$resolution_reply" | tr -d " ")" -le 4000

ruby -e '
  notes = File.read(ARGV.fetch(0))
  reply = File.read(ARGV.fetch(1))
  guide = File.read(ARGV.fetch(2))
  expected_notes = %w[DEMO_ACCOUNT_NAME DEMO_ACCOUNT_PASSWORD]
  expected_reply = %w[ATTACHED_VIDEO_FILENAME ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS]
  placeholders = ->(text) { text.scan(/\{\{([A-Z0-9_]+)\}\}/).flatten.uniq.sort }
  abort "Unexpected review-note placeholders" unless placeholders.call(notes) == expected_notes.sort
  abort "Unexpected Resolution Center placeholders" unless placeholders.call(reply) == expected_reply.sort
  sections = reply.scan(/^([1-8])\. /).flatten
  abort "Resolution Center reply must contain exactly sections 1 through 8" unless sections == %w[1 2 3 4 5 6 7 8]
  embedded = guide.match(/```text\n(.*?)\n```/m)&.captures&.first.to_s.strip
  abort "Resolution Center reply and resubmission guide differ" unless embedded == reply.strip
' "$review_notes" "$resolution_reply" "$resubmission_guide"

for policy in "$privacy_ko" "$privacy_en" "$privacy_ja"; do
  test -s "$policy"
  rg -qi "block|차단|ブロック" "$policy"
done

privacy_policy_hash="$(ruby -rdigest -e 'print Digest::SHA256.file(ARGV.fetch(0)).hexdigest' "$privacy_ko")"
rg -q "$privacy_policy_hash" "$project_root/backend/tutor/src/main/resources/db/migration/V62__register_2026_08_14_privacy_policy.sql"
rg -q "$privacy_policy_hash" "$project_root/backend/tutor/src/main/resources/db/migration-mysql/V77__register_2026_08_14_privacy_policy.sql"

rg -q "PrivacyInfo.xcprivacy" "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q "com.apple.developer.applesignin" "$project_root/StudyMate/StudyMateiOS.entitlements"
rg -q "Delete Account|회원탈퇴|アカウント" "$project_root/StudyMate/Views" "$project_root/StudyMate/Models"
rg -q "Report|신고|報告" "$project_root/StudyMate"
rg -q "Block User|사용자 차단|ユーザーをブロック" "$project_root/StudyMate"
rg -q "public-user:block" "$project_root/backend"
rg -q "membershipAutoRenewalDisclosure" "$project_root/StudyMate/Models/StudyModels.swift"
rg -q "subscriptionDisclosure" "$project_root/StudyMate/Views/MobileRootView.swift"
rg -q "AppLegalLinks.termsOfServiceURL" "$project_root/StudyMate/Views/MobileRootView.swift"
rg -q "AppLegalLinks.privacyPolicyURL" "$project_root/StudyMate/Views/MobileRootView.swift"
rg -q 'buddy-studdy/privacy-2026-08-14\.html' "$project_root/StudyMate/Models/StudyModels.swift"
rg -q 'buddy-studdy/en/privacy-2026-08-14\.html' "$project_root/StudyMate/Models/StudyModels.swift"
rg -q 'buddy-studdy/ja/privacy-2026-08-14\.html' "$project_root/StudyMate/Models/StudyModels.swift"
rg -qi "block" "$review_notes"
rg -q "privacy-2026-08-14\.html" "$review_notes"
rg -q "terms-2026-07-30\.html" "$review_notes"
rg -q "300" "$review_notes"
rg -q "1,000" "$review_notes"
rg -q "1\. COMPLETE PHYSICAL-DEVICE VIDEO" "$resolution_reply"
rg -q "8\. IN-APP PURCHASE BENEFITS AND ACCESS PATH" "$resolution_reply"

echo "App Store review source checks passed."
