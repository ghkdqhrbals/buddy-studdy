#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "uri"

API_HOST = "api.appstoreconnect.apple.com"
DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
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
  key_id = required_env("APPSTORE_CONNECT_KEY_ID")
  issuer_id = required_env("APPSTORE_CONNECT_ISSUER_ID")
  private_key_path = required_env("APPSTORE_CONNECT_PRIVATE_KEY_PATH")
  private_key = OpenSSL::PKey.read(File.read(private_key_path))
  now = Time.now.to_i
  header = { alg: "ES256", kid: key_id, typ: "JWT" }
  payload = { iss: issuer_id, iat: now, exp: now + 20 * 60, aud: "appstoreconnect-v1" }
  signing_input = "#{base64url(header.to_json)}.#{base64url(payload.to_json)}"
  der_signature = private_key.dsa_sign_asn1(OpenSSL::Digest::SHA256.digest(signing_input))
  sequence = OpenSSL::ASN1.decode(der_signature)
  raw_signature = sequence.value.map { |integer| integer.value.to_s(2).rjust(32, "\0") }.join
  "#{signing_input}.#{base64url(raw_signature)}"
end

def api_request(method, path, token, query: nil, body: nil)
  uri = URI::HTTPS.build(
    host: API_HOST,
    path: path,
    query: query && URI.encode_www_form(query)
  )
  request_class = {
    get: Net::HTTP::Get,
    patch: Net::HTTP::Patch
  }.fetch(method)
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

def find_app(token, bundle_id)
  response = api_request(
    :get,
    "/v1/apps",
    token,
    query: {
      "filter[bundleId]" => bundle_id,
      "limit" => "200"
    }
  )
  response.fetch("data").find { |item| item.dig("attributes", "bundleId") == bundle_id } ||
    abort("App not found for bundle ID: #{bundle_id}")
end

def find_editable_version(token, app_id)
  response = api_request(
    :get,
    "/v1/apps/#{app_id}/appStoreVersions",
    token,
    query: {
      "filter[platform]" => "IOS",
      "limit" => "200"
    }
  )
  explicit_id = ENV["APP_STORE_VERSION_ID"]
  versions = response.fetch("data")
  return versions.find { |item| item.fetch("id") == explicit_id } ||
    abort("App Store version not found: #{explicit_id}") if explicit_id

  versions.find { |item| EDITABLE_STATES.include?(item.dig("attributes", "appStoreState")) } ||
    abort("No editable iOS App Store version found")
end

def current_build_id(token, version_id)
  response = api_request(
    :get,
    "/v1/appStoreVersions/#{version_id}/relationships/build",
    token
  )
  response["data"]&.fetch("id")
end

def read_build(token, build_id)
  return unless build_id

  api_request(
    :get,
    "/v1/builds/#{build_id}",
    token,
    query: {
      "fields[builds]" => "version,uploadedDate,processingState,iconAssetToken"
    }
  ).fetch("data")
end

def find_build(token, app_id, build_number)
  response = api_request(
    :get,
    "/v1/builds",
    token,
    query: {
      "filter[app]" => app_id,
      "filter[version]" => build_number,
      "fields[builds]" => "version,uploadedDate,processingState,iconAssetToken,preReleaseVersion",
      "limit" => "200"
    }
  )
  candidates = response.fetch("data").select do |build|
    build.dig("attributes", "version") == build_number &&
      build.dig("attributes", "processingState") == "VALID"
  end

  matches = candidates.map do |build|
    prerelease = api_request(
      :get,
      "/v1/builds/#{build.fetch("id")}/preReleaseVersion",
      token,
      query: { "fields[preReleaseVersions]" => "version,platform" }
    ).fetch("data")
    [build, prerelease]
  end
  matches.find { |(_build, prerelease)| prerelease.dig("attributes", "platform") == "IOS" } ||
    abort("Valid iOS build #{build_number} not found")
end

def select_build(token, version_id, build_id)
  api_request(
    :patch,
    "/v1/appStoreVersions/#{version_id}/relationships/build",
    token,
    body: {
      data: {
        type: "builds",
        id: build_id
      }
    }
  )
end

def update_version_string(token, version_id, version_string)
  api_request(
    :patch,
    "/v1/appStoreVersions/#{version_id}",
    token,
    body: {
      data: {
        type: "appStoreVersions",
        id: version_id,
        attributes: {
          versionString: version_string
        }
      }
    }
  ).fetch("data")
end

token = app_store_token
bundle_id = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
target_build_number = required_env("APP_STORE_BUILD_NUMBER")
app = find_app(token, bundle_id)
version = find_editable_version(token, app.fetch("id"))
current_build = read_build(token, current_build_id(token, version.fetch("id")))
target_build, target_prerelease = find_build(
  token,
  app.fetch("id"),
  target_build_number
)

puts "App: #{app.dig("attributes", "name")} (#{bundle_id})"
puts "Version: #{version.dig("attributes", "versionString")} [#{version.dig("attributes", "appStoreState")}]"
puts "Current build: #{current_build&.dig("attributes", "version") || "none"}"
puts "Target build: #{target_build.dig("attributes", "version")} " \
     "[#{target_build.dig("attributes", "processingState")}]"
puts "Target version: #{target_prerelease.dig("attributes", "version")}"
puts "Target uploaded: #{target_build.dig("attributes", "uploadedDate")}"
puts "Target icon included: #{!target_build.dig("attributes", "iconAssetToken").nil?}"

if ENV["APP_STORE_APPLY"] == "1"
  target_version_string = target_prerelease.dig("attributes", "version")
  unless target_version_string == version.dig("attributes", "versionString")
    unless ENV["APP_STORE_SYNC_VERSION"] == "1"
      abort "Target build version does not match the editable App Store version. " \
            "Set APP_STORE_SYNC_VERSION=1 to update it."
    end
    version = update_version_string(token, version.fetch("id"), target_version_string)
    puts "Updated App Store version: #{version.dig("attributes", "versionString")}"
  end
  select_build(token, version.fetch("id"), target_build.fetch("id"))
  selected_build = read_build(token, current_build_id(token, version.fetch("id")))
  abort "Build selection verification failed" unless selected_build&.fetch("id") == target_build.fetch("id")

  puts "Selected build: #{selected_build.dig("attributes", "version")}"
else
  puts "Dry run only. Set APP_STORE_APPLY=1 to select the target build."
end
