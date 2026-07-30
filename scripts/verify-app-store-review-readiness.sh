#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
privacy_manifest="$project_root/StudyMate/Resources/PrivacyInfo.xcprivacy"
version_metadata="$project_root/app-store/metadata/version-localizations.json"
app_info_metadata="$project_root/app-store/metadata/app-info-localizations.json"
age_rating_metadata="$project_root/app-store/metadata/age-rating.json"
review_notes="$project_root/app-store/metadata/review-notes.txt"

plutil -lint "$privacy_manifest"
plutil -lint "$project_root/StudyMate.xcodeproj/project.pbxproj"

ruby -rjson -e '
  version = JSON.parse(File.read(ARGV.fetch(0)))
  app_info = JSON.parse(File.read(ARGV.fetch(1)))
  age_rating = JSON.parse(File.read(ARGV.fetch(2)))
  required = %w[ko en-US ja]
  abort "Missing version localization" unless (required - version.keys).empty?
  abort "Missing App Info localization" unless (required - app_info.keys).empty?
  required.each do |locale|
    abort "Missing subtitle: #{locale}" if app_info.dig(locale, "subtitle").to_s.strip.empty?
    url = app_info.dig(locale, "privacyPolicyUrl").to_s
    abort "Invalid privacy URL: #{locale}" unless url.start_with?("https://")
  end
  abort "UGC age-rating flag must be true" unless age_rating["userGeneratedContent"] == true
  abort "Unrestricted web access must be false" unless age_rating["unrestrictedWebAccess"] == false
' "$version_metadata" "$app_info_metadata" "$age_rating_metadata"

test -s "$review_notes"
test "$(wc -m < "$review_notes" | tr -d " ")" -le 4000

rg -q "PrivacyInfo.xcprivacy" "$project_root/StudyMate.xcodeproj/project.pbxproj"
rg -q "Delete Account|회원탈퇴|アカウント" "$project_root/StudyMate/Views" "$project_root/StudyMate/Models"
rg -q "Block user|사용자 차단|ユーザーをブロック" "$project_root/StudyMate"

echo "App Store review source checks passed."
