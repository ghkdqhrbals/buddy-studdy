#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "fileutils"
require "json"
require "net/http"
require "openssl"
require "time"
require "uri"

API_HOST = "api.appstoreconnect.apple.com"
DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
CAPABILITY_TYPE = "APPLE_ID_AUTH"

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
  request_class = { get: Net::HTTP::Get, post: Net::HTTP::Post }.fetch(method)
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

def find_bundle_id(token, identifier)
  api_request(
    :get,
    "/v1/bundleIds",
    token,
    query: { "filter[identifier]" => identifier, "limit" => "200" }
  ).fetch("data").find { |item| item.dig("attributes", "identifier") == identifier } ||
    abort("Bundle ID not found: #{identifier}")
end

def capabilities(token, bundle_id)
  api_request(
    :get,
    "/v1/bundleIds/#{bundle_id}/bundleIdCapabilities",
    token
  ).fetch("data")
end

def enable_sign_in_with_apple(token, bundle_id)
  api_request(
    :post,
    "/v1/bundleIdCapabilities",
    token,
    body: {
      data: {
        type: "bundleIdCapabilities",
        attributes: { capabilityType: CAPABILITY_TYPE },
        relationships: {
          bundleId: {
            data: { type: "bundleIds", id: bundle_id }
          }
        }
      }
    }
  )
end

def distribution_certificate(token)
  certificates = api_request(
    :get,
    "/v1/certificates",
    token,
    query: { "limit" => "200" }
  ).fetch("data")
  valid_types = %w[DISTRIBUTION IOS_DISTRIBUTION]
  certificates
    .select { |certificate| valid_types.include?(certificate.dig("attributes", "certificateType")) }
    .select { |certificate| Time.parse(certificate.dig("attributes", "expirationDate")) > Time.now }
    .max_by { |certificate| Time.parse(certificate.dig("attributes", "expirationDate")) } ||
    abort("No valid Apple Distribution certificate found")
end

def create_profile(token, bundle_id, certificate_id)
  name = "BuddyStudy App Store #{Time.now.utc.strftime("%Y%m%d%H%M%S")}"
  api_request(
    :post,
    "/v1/profiles",
    token,
    body: {
      data: {
        type: "profiles",
        attributes: {
          name: name,
          profileType: "IOS_APP_STORE"
        },
        relationships: {
          bundleId: {
            data: { type: "bundleIds", id: bundle_id }
          },
          certificates: {
            data: [{ type: "certificates", id: certificate_id }]
          }
        }
      }
    }
  ).fetch("data")
end

token = app_store_token
identifier = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
bundle_id = find_bundle_id(token, identifier)
enabled = capabilities(token, bundle_id.fetch("id")).any? do |capability|
  capability.dig("attributes", "capabilityType") == CAPABILITY_TYPE
end

puts "Bundle ID: #{identifier}"
puts "Sign in with Apple capability: #{enabled ? "enabled" : "disabled"}"

unless ENV["APP_STORE_APPLY"] == "1"
  action = enabled ? "create a new profile" : "enable the capability and create a new profile"
  puts "Dry run only. Set APP_STORE_APPLY=1 to #{action}."
  exit
end

unless enabled
  enable_sign_in_with_apple(token, bundle_id.fetch("id"))
  puts "Enabled Sign in with Apple capability."
end

output_path = ENV["PROFILE_OUTPUT_PATH"]
if output_path.nil? || output_path.empty?
  puts "Capability verified. Set PROFILE_OUTPUT_PATH to create a new App Store profile."
  exit
end

certificate = distribution_certificate(token)
profile = create_profile(token, bundle_id.fetch("id"), certificate.fetch("id"))
FileUtils.mkdir_p(File.dirname(File.expand_path(output_path)))
File.binwrite(output_path, Base64.decode64(profile.dig("attributes", "profileContent")))

puts "Created App Store profile: #{profile.dig("attributes", "name")}"
puts "Profile UUID: #{profile.dig("attributes", "uuid")}"
puts "Profile path: #{File.expand_path(output_path)}"
