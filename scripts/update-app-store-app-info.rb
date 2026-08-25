#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "uri"

API_HOST = "api.appstoreconnect.apple.com"
DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
DEFAULT_LOCALIZATIONS_PATH = File.expand_path(
  "../app-store/metadata/app-info-localizations.json",
  __dir__
)
DEFAULT_AGE_RATING_PATH = File.expand_path(
  "../app-store/metadata/age-rating.json",
  __dir__
)

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
  private_key = OpenSSL::PKey.read(File.read(required_env("APPSTORE_CONNECT_PRIVATE_KEY_PATH")))
  now = Time.now.to_i
  header = { alg: "ES256", kid: key_id, typ: "JWT" }
  payload = { iss: issuer_id, iat: now, exp: now + 20 * 60, aud: "appstoreconnect-v1" }
  signing_input = "#{base64url(header.to_json)}.#{base64url(payload.to_json)}"
  digest = OpenSSL::Digest::SHA256.digest(signing_input)
  sequence = OpenSSL::ASN1.decode(private_key.dsa_sign_asn1(digest))
  signature = sequence.value.map { |integer| integer.value.to_s(2).rjust(32, "\0") }.join
  "#{signing_input}.#{base64url(signature)}"
end

def api_request(method, path, token, query: nil, body: nil)
  uri = URI::HTTPS.build(host: API_HOST, path: path, query: query && URI.encode_www_form(query))
  request_class = { get: Net::HTTP::Get, post: Net::HTTP::Post, patch: Net::HTTP::Patch }.fetch(method)

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
    query: { "filter[bundleId]" => bundle_id, "limit" => "200" }
  )
  response.fetch("data").find { |item| item.dig("attributes", "bundleId") == bundle_id } ||
    abort("App not found for bundle ID: #{bundle_id}")
end

def find_app_info(token, app_id)
  infos = api_request(
    :get,
    "/v1/apps/#{app_id}/appInfos",
    token,
    query: { "limit" => "200" }
  ).fetch("data")
  abort "No App Info resource found" if infos.empty?
  infos.find { |item| item.dig("attributes", "appStoreState") != "READY_FOR_DISTRIBUTION" } || infos.first
end

def list_localizations(token, app_info_id)
  api_request(
    :get,
    "/v1/appInfos/#{app_info_id}/appInfoLocalizations",
    token,
    query: {
      "fields[appInfoLocalizations]" => "locale,name,subtitle,privacyPolicyUrl,privacyChoicesUrl",
      "limit" => "200"
    }
  ).fetch("data")
end

def create_localization(token, app_info_id, locale, metadata)
  api_request(
    :post,
    "/v1/appInfoLocalizations",
    token,
    body: {
      data: {
        type: "appInfoLocalizations",
        attributes: metadata.merge("locale" => locale),
        relationships: {
          appInfo: {
            data: { type: "appInfos", id: app_info_id }
          }
        }
      }
    }
  )
end

def update_localization(token, localization_id, metadata)
  api_request(
    :patch,
    "/v1/appInfoLocalizations/#{localization_id}",
    token,
    body: {
      data: {
        type: "appInfoLocalizations",
        id: localization_id,
        attributes: metadata
      }
    }
  )
end

def validate_localizations(localizations)
  abort "App Info metadata must contain at least one locale" if localizations.empty?
  localizations.each do |locale, metadata|
    name = metadata.fetch("name")
    subtitle = metadata.fetch("subtitle")
    privacy_url = metadata.fetch("privacyPolicyUrl")
    privacy_choices_url = metadata.fetch("privacyChoicesUrl")
    abort "#{locale} name is empty" if name.strip.empty?
    abort "#{locale} name exceeds 30 characters" if name.length > 30
    abort "#{locale} subtitle is empty" if subtitle.strip.empty?
    abort "#{locale} subtitle exceeds 30 characters" if subtitle.length > 30
    abort "#{locale} privacy policy URL must use HTTPS" unless privacy_url.start_with?("https://")
    abort "#{locale} privacy choices URL must use HTTPS" unless privacy_choices_url.start_with?("https://")
  end
end

def age_rating_declaration(token, app_info_id)
  api_request(
    :get,
    "/v1/appInfos/#{app_info_id}/ageRatingDeclaration",
    token
  ).fetch("data")
end

localizations_path = File.expand_path(
  ENV.fetch("APP_STORE_APP_INFO_PATH", DEFAULT_LOCALIZATIONS_PATH)
)
age_rating_path = File.expand_path(
  ENV.fetch("APP_STORE_AGE_RATING_PATH", DEFAULT_AGE_RATING_PATH)
)
localizations = JSON.parse(File.read(localizations_path))
age_rating = JSON.parse(File.read(age_rating_path))
validate_localizations(localizations)

token = app_store_token
bundle_id = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
app = find_app(token, bundle_id)
app_info = find_app_info(token, app.fetch("id"))
current_localizations = list_localizations(token, app_info.fetch("id"))
current_by_locale = current_localizations.to_h { |item| [item.dig("attributes", "locale"), item] }

localization_updates = localizations.filter_map do |locale, metadata|
  current = current_by_locale[locale]
  changed = current.nil? || metadata.any? do |field, value|
    current.dig("attributes", field) != value
  end
  next unless changed

  puts "#{locale}: #{current.nil? ? "create" : "update"} App Info localization"
  [locale, metadata, current]
end

declaration = age_rating_declaration(token, app_info.fetch("id"))
age_rating_updates = age_rating.select do |field, value|
  declaration.dig("attributes", field) != value
end
age_rating_updates.each do |field, value|
  puts "Age rating: #{field} #{declaration.dig("attributes", field).inspect} -> #{value.inspect}"
end

puts "App: #{app.dig("attributes", "name")} (#{bundle_id})"
puts "Pending App Info localizations: #{localization_updates.length}"
puts "Pending age-rating fields: #{age_rating_updates.length}"

unless ENV["APP_STORE_APPLY"] == "1"
  puts "Dry run only. Set APP_STORE_APPLY=1 to apply these changes."
  exit
end

localization_updates.each do |locale, metadata, current|
  if current
    update_localization(token, current.fetch("id"), metadata)
  else
    create_localization(token, app_info.fetch("id"), locale, metadata)
  end
  puts "Applied App Info localization: #{locale}"
end

unless age_rating_updates.empty?
  api_request(
    :patch,
    "/v1/ageRatingDeclarations/#{declaration.fetch("id")}",
    token,
    body: {
      data: {
        type: "ageRatingDeclarations",
        id: declaration.fetch("id"),
        attributes: age_rating_updates
      }
    }
  )
  puts "Applied age-rating declaration"
end

verified = list_localizations(token, app_info.fetch("id")).to_h do |item|
  [item.dig("attributes", "locale"), item]
end
localizations.each do |locale, metadata|
  current = verified[locale] || abort("App Info localization verification failed: #{locale}")
  metadata.each do |field, value|
    abort "App Info localization verification failed: #{locale} #{field}" unless
      current.dig("attributes", field) == value
  end
end

verified_age_rating = age_rating_declaration(token, app_info.fetch("id"))
age_rating.each do |field, value|
  abort "Age-rating verification failed: #{field}" unless
    verified_age_rating.dig("attributes", field) == value
end

puts "Verified App Info localizations and age-rating declaration."
