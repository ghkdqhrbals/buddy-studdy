#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "uri"
require_relative "lib/app_store_review_notes"

API_HOST = "api.appstoreconnect.apple.com"
DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
DEFAULT_NOTES_PATH = File.expand_path("../app-store/metadata/review-notes.txt", __dir__)
DEFAULT_REPLY_PATH = File.expand_path("../app-store/metadata/resolution-center-reply.txt", __dir__)
DEFAULT_GUIDE_PATH = File.expand_path("../docs/APP_STORE_REVIEW_RESUBMISSION_1.1.0.md", __dir__)
EDITABLE_STATES = %w[
  PREPARE_FOR_SUBMISSION
  READY_FOR_REVIEW
  INVALID_BINARY
  REJECTED
  METADATA_REJECTED
  DEVELOPER_REJECTED
].freeze

def required_env(name)
  value = ENV[name]
  abort "Missing required environment variable: #{name}" if value.nil? || value.empty?
  value
end

def base64url(value)
  Base64.urlsafe_encode64(value).delete("=")
end

def app_store_token
  private_key = OpenSSL::PKey.read(File.read(required_env("APPSTORE_CONNECT_PRIVATE_KEY_PATH")))
  now = Time.now.to_i
  header = { alg: "ES256", kid: required_env("APPSTORE_CONNECT_KEY_ID"), typ: "JWT" }
  payload = {
    iss: required_env("APPSTORE_CONNECT_ISSUER_ID"),
    iat: now,
    exp: now + 20 * 60,
    aud: "appstoreconnect-v1"
  }
  signing_input = "#{base64url(header.to_json)}.#{base64url(payload.to_json)}"
  digest = OpenSSL::Digest::SHA256.digest(signing_input)
  sequence = OpenSSL::ASN1.decode(private_key.dsa_sign_asn1(digest))
  signature = sequence.value.map { |integer| integer.value.to_s(2).rjust(32, "\0") }.join
  "#{signing_input}.#{base64url(signature)}"
end

def api_request(method, path, token, query: nil, body: nil)
  uri = URI::HTTPS.build(host: API_HOST, path: path, query: query && URI.encode_www_form(query))
  request_class = { get: Net::HTTP::Get, patch: Net::HTTP::Patch }.fetch(method)
  request = request_class.new(uri)
  request["Authorization"] = "Bearer #{token}"
  request["Content-Type"] = "application/json"
  request.body = JSON.generate(body) if body
  response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: true) { |http| http.request(request) }
  parsed = response.body.nil? || response.body.empty? ? {} : JSON.parse(response.body)
  return parsed if response.is_a?(Net::HTTPSuccess)

  warn JSON.pretty_generate(parsed)
  abort "App Store Connect API request failed: #{method.to_s.upcase} #{uri} returned #{response.code}"
end

notes_path = File.expand_path(ENV.fetch("APP_STORE_REVIEW_NOTES_PATH", DEFAULT_NOTES_PATH))
reply_path = File.expand_path(ENV.fetch("APP_STORE_RESOLUTION_REPLY_PATH", DEFAULT_REPLY_PATH))
guide_path = File.expand_path(ENV.fetch("APP_STORE_RESUBMISSION_GUIDE_PATH", DEFAULT_GUIDE_PATH))
notes_template = File.read(notes_path)
resolution_reply = File.read(reply_path)
resubmission_guide = File.read(guide_path)
begin
  AppStoreReviewNotes.validate_review_package!(
    notes: notes_template,
    reply: resolution_reply,
    guide: resubmission_guide,
    allow_recording_placeholders: false
  )
rescue AppStoreReviewNotes::ValidationError => error
  abort error.message
end

if ENV["APP_STORE_REVIEW_NOTES_VALIDATE_ONLY"] == "1"
  puts "App Store review notes are complete for sync; only server-side demo credentials remain to be resolved."
  exit
end

token = app_store_token
bundle_id = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
app = api_request(
  :get,
  "/v1/apps",
  token,
  query: { "filter[bundleId]" => bundle_id, "limit" => "200" }
).fetch("data").find { |item| item.dig("attributes", "bundleId") == bundle_id }
abort "App not found for bundle ID: #{bundle_id}" unless app

versions = api_request(
  :get,
  "/v1/apps/#{app.fetch("id")}/appStoreVersions",
  token,
  query: { "filter[platform]" => "IOS", "limit" => "200" }
).fetch("data")
version = if ENV["APP_STORE_VERSION_ID"]
            versions.find { |item| item.fetch("id") == ENV["APP_STORE_VERSION_ID"] }
          elsif ENV["APP_STORE_VERSION_STRING"]
            candidates = versions.select do |item|
              item.dig("attributes", "versionString") == ENV["APP_STORE_VERSION_STRING"] &&
                EDITABLE_STATES.include?(item.dig("attributes", "appStoreState"))
            end
            abort "Multiple editable iOS App Store versions #{ENV["APP_STORE_VERSION_STRING"]} found" if
              candidates.length > 1
            candidates.first
          else
            versions.find { |item| EDITABLE_STATES.include?(item.dig("attributes", "appStoreState")) }
          end
abort "No editable iOS App Store version found" unless version

detail = api_request(
  :get,
  "/v1/appStoreVersions/#{version.fetch("id")}/appStoreReviewDetail",
  token,
  query: {
    "fields[appStoreReviewDetails]" =>
      "contactFirstName,contactLastName,contactPhone,contactEmail,demoAccountName,demoAccountPassword,demoAccountRequired,notes"
  }
).fetch("data")

demo_account_name = detail.dig("attributes", "demoAccountName").to_s
demo_account_password = detail.dig("attributes", "demoAccountPassword").to_s
abort "App Review demo account name is empty" if demo_account_name.empty?
abort "App Review demo account password is empty" if demo_account_password.empty?

notes = notes_template
  .gsub("{{DEMO_ACCOUNT_NAME}}", demo_account_name)
  .gsub("{{DEMO_ACCOUNT_PASSWORD}}", demo_account_password)
  .strip
begin
  AppStoreReviewNotes.validate_rendered!(notes)
  review_notes_bytes = AppStoreReviewNotes.validate_byte_limit!(notes)
rescue AppStoreReviewNotes::ValidationError => error
  abort error.message
end

puts "App: #{app.dig("attributes", "name")} (#{bundle_id})"
puts "Version: #{version.dig("attributes", "versionString")} [#{version.dig("attributes", "appStoreState")}]"
puts "Demo account required: #{detail.dig("attributes", "demoAccountRequired")}"
puts "Demo account configured: #{!detail.dig("attributes", "demoAccountName").to_s.empty?}"
puts "Review contact configured: #{!detail.dig("attributes", "contactEmail").to_s.empty?}"
puts "Review notes: #{review_notes_bytes}/4,000 UTF-8 bytes"

if detail.dig("attributes", "notes") == notes
  puts "Review notes are already current."
  exit
end

unless ENV["APP_STORE_APPLY"] == "1"
  puts "Review notes need an update. Dry run only; set APP_STORE_APPLY=1 to apply."
  exit
end

api_request(
  :patch,
  "/v1/appStoreReviewDetails/#{detail.fetch("id")}",
  token,
  body: {
    data: {
      type: "appStoreReviewDetails",
      id: detail.fetch("id"),
      attributes: { notes: notes }
    }
  }
)

verified = api_request(
  :get,
  "/v1/appStoreReviewDetails/#{detail.fetch("id")}",
  token,
  query: { "fields[appStoreReviewDetails]" => "notes" }
)
abort "Review notes verification failed" unless verified.dig("data", "attributes", "notes") == notes

puts "Updated and verified App Store review notes."
