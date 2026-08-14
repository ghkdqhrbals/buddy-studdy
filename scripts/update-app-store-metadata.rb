#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "uri"

API_HOST = "api.appstoreconnect.apple.com"
DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
DEFAULT_METADATA_PATH = File.expand_path(
  "../app-store/metadata/version-localizations.json",
  __dir__
)
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
  digest = OpenSSL::Digest::SHA256.digest(signing_input)
  der_signature = private_key.dsa_sign_asn1(digest)
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
    patch: Net::HTTP::Patch
  }.fetch(method)

  4.times do |attempt|
    request = request_class.new(uri)
    request["Authorization"] = "Bearer #{token}"
    request["Content-Type"] = "application/json"
    request.body = JSON.generate(body) if body
    response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: true) { |http| http.request(request) }
    parsed = response.body.nil? || response.body.empty? ? {} : JSON.parse(response.body)
    return parsed if response.is_a?(Net::HTTPSuccess)

    if response.code.to_i == 429 || response.code.to_i >= 500
      sleep(2**attempt)
      next
    end

    warn JSON.pretty_generate(parsed)
    abort "App Store Connect API request failed: #{method.to_s.upcase} #{uri} returned #{response.code}"
  end

  abort "App Store Connect API request failed after retries: #{method.to_s.upcase} #{uri}"
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

  explicit_version = ENV["APP_STORE_VERSION_STRING"]
  if explicit_version
    candidates = versions.select do |item|
      item.dig("attributes", "versionString") == explicit_version &&
        EDITABLE_STATES.include?(item.dig("attributes", "appStoreState"))
    end
    abort "No editable iOS App Store version #{explicit_version} found" if candidates.empty?
    abort "Multiple editable iOS App Store versions #{explicit_version} found" if candidates.length > 1

    return candidates.first
  end

  versions.find { |item| EDITABLE_STATES.include?(item.dig("attributes", "appStoreState")) } ||
    abort("No editable iOS App Store version found")
end

def list_localizations(token, version_id)
  api_request(
    :get,
    "/v1/appStoreVersions/#{version_id}/appStoreVersionLocalizations",
    token,
    query: {
      "fields[appStoreVersionLocalizations]" => "locale,promotionalText,description,keywords,supportUrl,marketingUrl,whatsNew",
      "limit" => "200"
    }
  ).fetch("data")
end

def create_localization(token, version_id, locale, metadata)
  attributes = {
    locale: locale,
    promotionalText: metadata.fetch("promotionalText"),
    description: metadata.fetch("description"),
    keywords: metadata.fetch("keywords"),
    supportUrl: metadata.fetch("supportUrl"),
    marketingUrl: metadata.fetch("marketingUrl")
  }
  attributes[:whatsNew] = metadata.fetch("whatsNew") if metadata.key?("whatsNew")
  api_request(
    :post,
    "/v1/appStoreVersionLocalizations",
    token,
    body: {
      data: {
        type: "appStoreVersionLocalizations",
        attributes: attributes,
        relationships: {
          appStoreVersion: {
            data: {
              type: "appStoreVersions",
              id: version_id
            }
          }
        }
      }
    }
  ).fetch("data")
end

def update_localization(token, localization_id, metadata)
  attributes = {
    promotionalText: metadata.fetch("promotionalText"),
    description: metadata.fetch("description"),
    keywords: metadata.fetch("keywords"),
    supportUrl: metadata.fetch("supportUrl"),
    marketingUrl: metadata.fetch("marketingUrl")
  }
  attributes[:whatsNew] = metadata.fetch("whatsNew") if metadata.key?("whatsNew")
  api_request(
    :patch,
    "/v1/appStoreVersionLocalizations/#{localization_id}",
    token,
    body: {
      data: {
        type: "appStoreVersionLocalizations",
        id: localization_id,
        attributes: attributes
      }
    }
  )
end

def validate_metadata(metadata_by_locale)
  abort "Metadata file must contain at least one locale" if metadata_by_locale.empty?

  metadata_by_locale.each do |locale, metadata|
    promotional_text = metadata.fetch("promotionalText")
    description = metadata.fetch("description")
    keywords = metadata.fetch("keywords")
    support_url = metadata.fetch("supportUrl")
    marketing_url = metadata.fetch("marketingUrl")
    whats_new = metadata["whatsNew"]
    abort "#{locale} promotional text exceeds 170 characters" if promotional_text.length > 170
    abort "#{locale} description exceeds 4,000 characters" if description.length > 4_000
    abort "#{locale} What's New exceeds 4,000 characters" if whats_new && whats_new.length > 4_000
    abort "#{locale} keywords exceed 100 bytes" if keywords.bytesize > 100
    abort "#{locale} promotional text is empty" if promotional_text.strip.empty?
    abort "#{locale} description is empty" if description.strip.empty?
    abort "#{locale} What's New is empty" if whats_new && whats_new.strip.empty?
    abort "#{locale} keywords are empty" if keywords.strip.empty?
    abort "#{locale} support URL must use HTTPS" unless support_url.start_with?("https://")
    abort "#{locale} marketing URL must use HTTPS" unless marketing_url.start_with?("https://")
  end
end

metadata_path = File.expand_path(ENV.fetch("APP_STORE_METADATA_PATH", DEFAULT_METADATA_PATH))
abort "Metadata file not found: #{metadata_path}" unless File.file?(metadata_path)
metadata_by_locale = JSON.parse(File.read(metadata_path))
validate_metadata(metadata_by_locale)

token = app_store_token
bundle_id = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
app = find_app(token, bundle_id)
version = find_editable_version(token, app.fetch("id"))
localizations = list_localizations(token, version.fetch("id"))
localizations_by_locale = localizations.to_h do |localization|
  [localization.dig("attributes", "locale"), localization]
end

puts "App: #{app.dig("attributes", "name")} (#{bundle_id})"
puts "Version: #{version.dig("attributes", "versionString")} " \
     "[#{version.dig("attributes", "appStoreState")}]"
puts "Existing locales: #{localizations_by_locale.keys.sort.join(", ")}"

pending = metadata_by_locale.filter_map do |locale, metadata|
  localization = localizations_by_locale[locale]
  state = if localization.nil?
            "create"
          elsif localization.dig("attributes", "promotionalText") != metadata.fetch("promotionalText") ||
                localization.dig("attributes", "description") != metadata.fetch("description") ||
                localization.dig("attributes", "keywords") != metadata.fetch("keywords") ||
                localization.dig("attributes", "supportUrl") != metadata.fetch("supportUrl") ||
                localization.dig("attributes", "marketingUrl") != metadata.fetch("marketingUrl") ||
                (metadata.key?("whatsNew") &&
                  localization.dig("attributes", "whatsNew") != metadata.fetch("whatsNew"))
            "update"
          end
  next unless state

  puts "#{locale}: #{state} " \
       "(promotional #{metadata.fetch("promotionalText").length}/170, " \
       "description #{metadata.fetch("description").length}/4000, " \
       "What's New #{metadata["whatsNew"]&.length || "not applicable"}, " \
       "keywords #{metadata.fetch("keywords").bytesize}/100 bytes)"
  [locale, metadata, localization]
end

puts "Pending localizations: #{pending.length}"
unless ENV["APP_STORE_APPLY"] == "1"
  puts "Dry run only. Set APP_STORE_APPLY=1 to apply these changes."
  exit
end

pending.each do |locale, metadata, localization|
  if localization
    update_localization(token, localization.fetch("id"), metadata)
    puts "Updated: #{locale}"
  else
    create_localization(token, version.fetch("id"), locale, metadata)
    puts "Created: #{locale}"
  end
end

verified = list_localizations(token, version.fetch("id")).to_h do |localization|
  [localization.dig("attributes", "locale"), localization]
end
metadata_by_locale.each do |locale, metadata|
  localization = verified[locale] || abort("Localization verification failed: #{locale} is missing")
  fields = %w[promotionalText description keywords supportUrl marketingUrl]
  fields << "whatsNew" if metadata.key?("whatsNew")
  fields.each do |field|
    next if localization.dig("attributes", field) == metadata.fetch(field)

    abort "Localization verification failed: #{locale} #{field} does not match"
  end
end

puts "Verified locales: #{metadata_by_locale.keys.sort.join(", ")}"
