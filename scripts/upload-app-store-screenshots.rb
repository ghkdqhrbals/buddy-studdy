#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "digest"
require "json"
require "net/http"
require "openssl"
require "uri"

API_HOST = "api.appstoreconnect.apple.com"
DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
DEFAULT_LOCALE = "ko"
DEFAULT_DISPLAY_TYPE = "APP_IPHONE_67"
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
    post: Net::HTTP::Post,
    patch: Net::HTTP::Patch,
    delete: Net::HTTP::Delete
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

def upload_part(operation, file_path)
  uri = URI(operation.fetch("url"))
  method = operation.fetch("method").upcase
  request_class = {
    "PUT" => Net::HTTP::Put,
    "POST" => Net::HTTP::Post
  }.fetch(method)
  request = request_class.new(uri)
  operation.fetch("requestHeaders", []).each do |header|
    request[header.fetch("name")] = header.fetch("value")
  end

  File.open(file_path, "rb") do |file|
    file.seek(operation.fetch("offset"))
    request.body = file.read(operation.fetch("length"))
  end

  response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: true) { |http| http.request(request) }
  return if response.is_a?(Net::HTTPSuccess)

  abort "Screenshot data upload failed: #{method} #{uri.host} returned #{response.code}"
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

def find_localization(token, version_id, locale)
  response = api_request(
    :get,
    "/v1/appStoreVersions/#{version_id}/appStoreVersionLocalizations",
    token,
    query: { "limit" => "200" }
  )
  localizations = response.fetch("data")
  match = localizations.find { |item| item.dig("attributes", "locale") == locale }
  return match if match

  available_locales = localizations.filter_map { |item| item.dig("attributes", "locale") }
  abort "App Store version localization not found: #{locale}. Available: #{available_locales.join(", ")}"
end

def find_or_create_screenshot_set(token, localization_id, display_type)
  response = api_request(
    :get,
    "/v1/appStoreVersionLocalizations/#{localization_id}/appScreenshotSets",
    token,
    query: { "limit" => "200" }
  )
  existing = response.fetch("data").find do |item|
    item.dig("attributes", "screenshotDisplayType") == display_type
  end
  return existing if existing

  api_request(
    :post,
    "/v1/appScreenshotSets",
    token,
    body: {
      data: {
        type: "appScreenshotSets",
        attributes: {
          screenshotDisplayType: display_type
        },
        relationships: {
          appStoreVersionLocalization: {
            data: {
              type: "appStoreVersionLocalizations",
              id: localization_id
            }
          }
        }
      }
    }
  ).fetch("data")
end

def list_screenshots(token, screenshot_set_id)
  api_request(
    :get,
    "/v1/appScreenshotSets/#{screenshot_set_id}/appScreenshots",
    token,
    query: { "limit" => "200" }
  ).fetch("data")
end

def reorder_screenshots(token, screenshot_set_id, screenshots, ordered_file_names)
  screenshots_by_name = screenshots.group_by { |screenshot| screenshot.dig("attributes", "fileName") }
  missing_file_names = ordered_file_names.reject { |file_name| screenshots_by_name.key?(file_name) }
  abort "Cannot reorder missing screenshots: #{missing_file_names.join(", ")}" unless missing_file_names.empty?

  ordered = ordered_file_names.flat_map { |file_name| screenshots_by_name.fetch(file_name) }
  ordered_ids = ordered.map { |screenshot| screenshot.fetch("id") }
  remaining = screenshots.reject { |screenshot| ordered_ids.include?(screenshot.fetch("id")) }
  api_request(
    :patch,
    "/v1/appScreenshotSets/#{screenshot_set_id}/relationships/appScreenshots",
    token,
    body: {
      data: (ordered + remaining).map do |screenshot|
        {
          type: "appScreenshots",
          id: screenshot.fetch("id")
        }
      end
    }
  )
end

def reserve_screenshot(token, screenshot_set_id, file_path)
  api_request(
    :post,
    "/v1/appScreenshots",
    token,
    body: {
      data: {
        type: "appScreenshots",
        attributes: {
          fileName: File.basename(file_path),
          fileSize: File.size(file_path)
        },
        relationships: {
          appScreenshotSet: {
            data: {
              type: "appScreenshotSets",
              id: screenshot_set_id
            }
          }
        }
      }
    }
  ).fetch("data")
end

def commit_screenshot(token, screenshot_id, file_path)
  api_request(
    :patch,
    "/v1/appScreenshots/#{screenshot_id}",
    token,
    body: {
      data: {
        type: "appScreenshots",
        id: screenshot_id,
        attributes: {
          uploaded: true,
          sourceFileChecksum: Digest::MD5.file(file_path).hexdigest
        }
      }
    }
  )
end

list_only = ENV["APP_STORE_LIST_ONLY"] == "1"
file_paths = ARGV.map { |path| File.expand_path(path) }
if file_paths.empty? && !list_only
  abort "Usage: #{File.basename($PROGRAM_NAME)} <screenshot.png> [...]"
end
file_paths.each { |path| abort "Screenshot not found: #{path}" unless File.file?(path) }

token = app_store_token
bundle_id = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
locale = ENV.fetch("APP_STORE_LOCALE", DEFAULT_LOCALE)
display_type = ENV.fetch("APP_SCREENSHOT_DISPLAY_TYPE", DEFAULT_DISPLAY_TYPE)
app = find_app(token, bundle_id)
version = find_editable_version(token, app.fetch("id"))
localization = find_localization(token, version.fetch("id"), locale)
screenshot_set = find_or_create_screenshot_set(token, localization.fetch("id"), display_type)

puts "App: #{app.dig("attributes", "name")} (#{bundle_id})"
puts "Version: #{version.dig("attributes", "versionString")} [#{version.dig("attributes", "appStoreState")}]"
puts "Locale: #{locale}"
puts "Display type: #{display_type}"

if list_only
  screenshots = list_screenshots(token, screenshot_set.fetch("id"))
  if ENV["APP_STORE_DELETE_FAILED"] == "1"
    failed_screenshots = screenshots.select do |screenshot|
      screenshot.dig("attributes", "assetDeliveryState", "state") == "FAILED"
    end
    failed_screenshots.each do |screenshot|
      api_request(:delete, "/v1/appScreenshots/#{screenshot.fetch("id")}", token)
      puts "Deleted failed upload: #{screenshot.dig("attributes", "fileName")}"
    end
    screenshots = list_screenshots(token, screenshot_set.fetch("id"))
  end
  if ENV["APP_STORE_DELETE_DUPLICATES"] == "1"
    screenshots.group_by { |screenshot| screenshot.dig("attributes", "fileName") }.each_value do |group|
      next if group.length < 2

      keeper = group.find do |screenshot|
        screenshot.dig("attributes", "assetDeliveryState", "state") == "COMPLETE"
      end || group.first
      group.reject { |screenshot| screenshot.fetch("id") == keeper.fetch("id") }.each do |duplicate|
        api_request(:delete, "/v1/appScreenshots/#{duplicate.fetch("id")}", token)
        puts "Deleted duplicate upload: #{duplicate.dig("attributes", "fileName")}"
      end
    end
    screenshots = list_screenshots(token, screenshot_set.fetch("id"))
  end
  ordered_file_names = ENV.fetch("APP_STORE_SCREENSHOT_ORDER", "")
    .split(",")
    .map(&:strip)
    .reject(&:empty?)
  unless ordered_file_names.empty?
    reorder_screenshots(
      token,
      screenshot_set.fetch("id"),
      screenshots,
      ordered_file_names
    )
    puts "Reordered screenshots: #{ordered_file_names.join(", ")}"
    screenshots = list_screenshots(token, screenshot_set.fetch("id"))
  end
  puts "Screenshots: #{screenshots.length}"
  screenshots.each do |screenshot|
    attributes = screenshot.fetch("attributes")
    state = attributes.dig("assetDeliveryState", "state") || "UNKNOWN"
    puts "#{attributes.fetch("fileName")} [#{state}]"
    next if state == "COMPLETE"

    attributes.dig("assetDeliveryState", "errors")&.each do |error|
      puts "  #{error["code"]}: #{error["description"]}"
    end
  end
  exit
end

file_paths.each do |file_path|
  reservation = reserve_screenshot(token, screenshot_set.fetch("id"), file_path)
  reservation.fetch("attributes").fetch("uploadOperations").each do |operation|
    upload_part(operation, file_path)
  end
  commit_screenshot(token, reservation.fetch("id"), file_path)
  puts "Uploaded: #{File.basename(file_path)}"
end
