#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "uri"

API_HOST = "api.appstoreconnect.apple.com"
DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
DEFAULT_TERRITORIES = %w[AUS CAN GBR IRL JPN KOR NZL USA].freeze

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

def read_availability(token, app_id)
  api_request(
    :get,
    "/v1/apps/#{app_id}/appAvailabilityV2",
    token,
    query: {
      "fields[appAvailabilities]" => "availableInNewTerritories"
    }
  ).fetch("data")
end

def read_territories(token, availability_id)
  api_request(
    :get,
    "/v2/appAvailabilities/#{availability_id}/territoryAvailabilities",
    token,
    query: {
      "fields[territoryAvailabilities]" => "available,contentStatuses,territory",
      "include" => "territory",
      "limit" => "200"
    }
  ).fetch("data")
end

def territory_code(territory)
  territory.dig("relationships", "territory", "data", "id") ||
    abort("Territory code missing for availability #{territory.fetch("id")}")
end

def update_territory(token, territory, available)
  api_request(
    :patch,
    "/v1/territoryAvailabilities/#{territory.fetch("id")}",
    token,
    body: {
      data: {
        type: "territoryAvailabilities",
        id: territory.fetch("id"),
        attributes: {
          available: available
        }
      }
    }
  )
end

def print_summary(app, availability, territories, desired_codes)
  available_codes = territories.filter_map do |territory|
    territory_code(territory) if territory.dig("attributes", "available")
  end.sort
  changes = territories.filter_map do |territory|
    code = territory_code(territory)
    desired = desired_codes.include?(code)
    [code, desired] unless territory.dig("attributes", "available") == desired
  end

  puts "App: #{app.dig("attributes", "name")} (#{app.dig("attributes", "bundleId")})"
  puts "Available in new territories: #{availability.dig("attributes", "availableInNewTerritories")}"
  puts "Currently available: #{available_codes.length}"
  puts "China mainland: #{available_codes.include?("CHN") ? "available" : "not available"}"
  puts "Desired territories (#{desired_codes.length}): #{desired_codes.join(", ")}"
  puts "Pending territory changes: #{changes.length}"
  puts "Available after changes: #{desired_codes.join(", ")}"
  changes
end

token = app_store_token
bundle_id = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
desired_codes = ENV.fetch("APP_STORE_TERRITORIES", DEFAULT_TERRITORIES.join(","))
  .split(",")
  .map(&:strip)
  .reject(&:empty?)
  .uniq
  .sort
app = find_app(token, bundle_id)
availability = read_availability(token, app.fetch("id"))
territories = read_territories(token, availability.fetch("id"))
known_codes = territories.map { |territory| territory_code(territory) }
unknown_codes = desired_codes - known_codes
abort "Unknown App Store territory codes: #{unknown_codes.join(", ")}" unless unknown_codes.empty?

changes = print_summary(app, availability, territories, desired_codes)

unless ENV["APP_STORE_APPLY"] == "1"
  puts "Dry run only. Set APP_STORE_APPLY=1 to apply these changes."
  exit
end

if availability.dig("attributes", "availableInNewTerritories")
  abort <<~MESSAGE
    Automatic availability for new territories is enabled.
    Disable it in App Store Connect before applying this restricted territory list.
  MESSAGE
end

changes.each_with_index do |(code, desired), index|
  territory = territories.find { |item| territory_code(item) == code }
  update_territory(token, territory, desired)
  puts "Updated #{code}: #{desired ? "available" : "not available"} (#{index + 1}/#{changes.length})"
end

verified_availability = read_availability(token, app.fetch("id"))
verified_territories = read_territories(token, availability.fetch("id"))
verified_codes = verified_territories.filter_map do |territory|
  territory_code(territory) if territory.dig("attributes", "available")
end.sort
abort "App Store territory verification failed" unless verified_codes == desired_codes
if verified_availability.dig("attributes", "availableInNewTerritories")
  abort "Automatic availability for new App Store territories is still enabled"
end

puts "Verified territories: #{verified_codes.join(", ")}"
puts "Verified China mainland: not available"
puts "New territories automatically enabled: " \
     "#{verified_availability.dig("attributes", "availableInNewTerritories")}"
