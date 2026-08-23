# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "uri"

module TestFlightBuildNotes
  API_HOST = "api.appstoreconnect.apple.com"
  REQUIRED_LOCALES = %w[en-US ko ja].freeze
  MAX_WHATS_NEW_CHARACTERS = 4_000
  TERMINAL_FAILURE_STATES = %w[FAILED INVALID].freeze

  class Error < StandardError; end
  class ConfigurationError < Error; end
  class ApiError < Error; end
  class BuildProcessingError < Error; end

  module_function

  def validate_localizations!(localizations)
    unless localizations.is_a?(Hash)
      raise ConfigurationError, "TestFlight build localizations must be a JSON object"
    end

    locales = localizations.keys
    missing = REQUIRED_LOCALES - locales
    unexpected = locales - REQUIRED_LOCALES
    unless missing.empty?
      raise ConfigurationError, "Missing TestFlight build locales: #{missing.join(', ')}"
    end
    unless unexpected.empty?
      raise ConfigurationError, "Unexpected TestFlight build locales: #{unexpected.join(', ')}"
    end

    localizations.each do |locale, whats_new|
      unless whats_new.is_a?(String) && !whats_new.strip.empty?
        raise ConfigurationError, "#{locale} TestFlight build note must not be empty"
      end
      if whats_new.length > MAX_WHATS_NEW_CHARACTERS
        raise ConfigurationError,
              "#{locale} TestFlight build note exceeds #{MAX_WHATS_NEW_CHARACTERS} characters"
      end
    end

    localizations
  end

  def positive_number!(value, label, integer: false)
    number = integer ? Integer(value, 10) : Float(value)
    raise ArgumentError unless number.positive? && number.finite?

    number
  rescue ArgumentError, TypeError
    raise ConfigurationError, "#{label} must be a positive #{integer ? 'integer' : 'number'}"
  end

  class AppStoreConnectClient
    def initialize(key_id:, issuer_id:, private_key_path:, sleeper: ->(seconds) { sleep(seconds) })
      @key_id = key_id
      @issuer_id = issuer_id
      @private_key = OpenSSL::PKey.read(File.read(private_key_path))
      @sleeper = sleeper
    end

    def find_app(bundle_id)
      response = request(
        :get,
        "/v1/apps",
        query: {
          "filter[bundleId]" => bundle_id,
          "fields[apps]" => "name,bundleId",
          "limit" => "200"
        }
      )
      matches = response.fetch("data").select do |app|
        app.dig("attributes", "bundleId") == bundle_id
      end
      raise ApiError, "App not found for bundle ID: #{bundle_id}" if matches.empty?
      raise ApiError, "Multiple apps found for bundle ID: #{bundle_id}" if matches.length > 1

      matches.first
    end

    def build_candidates(app_id:, marketing_version:, build_number:)
      response = request(
        :get,
        "/v1/builds",
        query: {
          "filter[app]" => app_id,
          "filter[version]" => build_number,
          "filter[preReleaseVersion.version]" => marketing_version,
          "filter[preReleaseVersion.platform]" => "IOS",
          "fields[builds]" => "version,uploadedDate,processingState,preReleaseVersion",
          "fields[preReleaseVersions]" => "version,platform",
          "include" => "preReleaseVersion",
          "limit" => "200"
        }
      )
      prereleases = response.fetch("included", []).to_h do |item|
        [item.fetch("id"), item]
      end

      response.fetch("data").map do |build|
        prerelease_id = build.dig("relationships", "preReleaseVersion", "data", "id")
        prerelease = prereleases[prerelease_id] || read_prerelease_version(build.fetch("id"))
        {
          "id" => build.fetch("id"),
          "buildNumber" => build.dig("attributes", "version"),
          "marketingVersion" => prerelease.dig("attributes", "version"),
          "platform" => prerelease.dig("attributes", "platform"),
          "processingState" => build.dig("attributes", "processingState"),
          "uploadedDate" => build.dig("attributes", "uploadedDate")
        }
      end
    end

    def beta_build_localizations(build_id)
      request(
        :get,
        "/v1/builds/#{build_id}/betaBuildLocalizations",
        query: {
          "fields[betaBuildLocalizations]" => "locale,whatsNew",
          "limit" => "200"
        }
      ).fetch("data")
    end

    def create_beta_build_localization(build_id:, locale:, whats_new:)
      request(
        :post,
        "/v1/betaBuildLocalizations",
        body: {
          data: {
            type: "betaBuildLocalizations",
            attributes: {
              locale: locale,
              whatsNew: whats_new
            },
            relationships: {
              build: {
                data: {
                  type: "builds",
                  id: build_id
                }
              }
            }
          }
        }
      )
    end

    def update_beta_build_localization(localization_id:, whats_new:)
      request(
        :patch,
        "/v1/betaBuildLocalizations/#{localization_id}",
        body: {
          data: {
            type: "betaBuildLocalizations",
            id: localization_id,
            attributes: {
              whatsNew: whats_new
            }
          }
        }
      )
    end

    private

    def read_prerelease_version(build_id)
      request(
        :get,
        "/v1/builds/#{build_id}/preReleaseVersion",
        query: { "fields[preReleaseVersions]" => "version,platform" }
      ).fetch("data")
    end

    def request(method, path, query: nil, body: nil)
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
        http_request = request_class.new(uri)
        http_request["Authorization"] = "Bearer #{bearer_token}"
        http_request["Content-Type"] = "application/json"
        http_request.body = JSON.generate(body) if body
        response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: true) do |http|
          http.request(http_request)
        end
        parsed = parse_response(response)
        return parsed if response.is_a?(Net::HTTPSuccess)

        if response.code.to_i == 429 || response.code.to_i >= 500
          @sleeper.call(2**attempt)
          next
        end

        raise_api_error(method, path, response.code, parsed)
      end

      raise ApiError, "App Store Connect API request failed after retries: #{method.to_s.upcase} #{path}"
    end

    def parse_response(response)
      return {} if response.body.nil? || response.body.empty?

      JSON.parse(response.body)
    rescue JSON::ParserError
      { "errors" => [{ "detail" => "Response was not valid JSON" }] }
    end

    def raise_api_error(method, path, status, parsed)
      details = parsed.fetch("errors", []).map do |error|
        error["detail"] || error["title"] || error["code"]
      end.compact
      suffix = details.empty? ? "" : ": #{details.join('; ')}"
      raise ApiError,
            "App Store Connect API request failed: #{method.to_s.upcase} #{path} returned #{status}#{suffix}"
    end

    def bearer_token
      now = Time.now.to_i
      header = { alg: "ES256", kid: @key_id, typ: "JWT" }
      payload = {
        iss: @issuer_id,
        iat: now,
        exp: now + 20 * 60,
        aud: "appstoreconnect-v1"
      }
      signing_input = "#{base64url(header.to_json)}.#{base64url(payload.to_json)}"
      digest = OpenSSL::Digest::SHA256.digest(signing_input)
      sequence = OpenSSL::ASN1.decode(@private_key.dsa_sign_asn1(digest))
      signature = sequence.value.map do |integer|
        integer.value.to_s(2).rjust(32, "\0")
      end.join
      "#{signing_input}.#{base64url(signature)}"
    end

    def base64url(value)
      Base64.urlsafe_encode64(value).delete("=")
    end
  end

  class BuildWaiter
    def initialize(client:, logger: $stdout, sleeper: ->(seconds) { sleep(seconds) },
                   monotonic_clock: -> { Process.clock_gettime(Process::CLOCK_MONOTONIC) })
      @client = client
      @logger = logger
      @sleeper = sleeper
      @monotonic_clock = monotonic_clock
    end

    def wait(app_id:, marketing_version:, build_number:, timeout_seconds:, interval_seconds:)
      deadline = @monotonic_clock.call + timeout_seconds
      last_status = nil

      loop do
        candidates = exact_candidates(
          @client.build_candidates(
            app_id: app_id,
            marketing_version: marketing_version,
            build_number: build_number
          ),
          marketing_version: marketing_version,
          build_number: build_number
        )
        if candidates.length > 1
          raise BuildProcessingError,
                "Multiple matching iOS builds found for #{marketing_version} (#{build_number})"
        end

        build = candidates.first
        status = build ? build.fetch("processingState") : "NOT_VISIBLE"
        if status != last_status
          @logger.puts "TestFlight build #{marketing_version} (#{build_number}): #{status}"
          last_status = status
        end
        return build if status == "VALID"
        if TERMINAL_FAILURE_STATES.include?(status)
          raise BuildProcessingError,
                "TestFlight build #{marketing_version} (#{build_number}) processing failed: #{status}"
        end

        remaining = deadline - @monotonic_clock.call
        if remaining <= 0
          raise BuildProcessingError,
                "Timed out waiting for TestFlight build #{marketing_version} (#{build_number})"
        end
        @sleeper.call([interval_seconds, remaining].min)
      end
    end

    private

    def exact_candidates(candidates, marketing_version:, build_number:)
      candidates.select do |build|
        build.fetch("buildNumber") == build_number &&
          build.fetch("marketingVersion") == marketing_version &&
          build.fetch("platform") == "IOS"
      end
    end
  end

  class Synchronizer
    def initialize(client:, logger: $stdout)
      @client = client
      @logger = logger
    end

    def sync(build_id:, desired_localizations:, apply:)
      TestFlightBuildNotes.validate_localizations!(desired_localizations)
      current = localizations_by_locale(build_id)
      actions = desired_localizations.map do |locale, whats_new|
        localization = current[locale]
        action = if localization.nil?
                   :create
                 elsif localization.dig("attributes", "whatsNew") == whats_new
                   :current
                 else
                   :update
                 end
        [locale, action, localization, whats_new]
      end

      actions.each { |locale, action, _localization, _text| @logger.puts "#{locale}: #{action}" }
      return actions.map { |locale, action, _localization, _text| [locale, action] }.to_h unless apply

      actions.each do |locale, action, localization, whats_new|
        case action
        when :create
          @client.create_beta_build_localization(
            build_id: build_id,
            locale: locale,
            whats_new: whats_new
          )
        when :update
          @client.update_beta_build_localization(
            localization_id: localization.fetch("id"),
            whats_new: whats_new
          )
        end
      end
      verify!(build_id, desired_localizations)

      actions.map { |locale, action, _localization, _text| [locale, action] }.to_h
    end

    private

    def localizations_by_locale(build_id)
      localizations = @client.beta_build_localizations(build_id)
      grouped = localizations.group_by { |item| item.dig("attributes", "locale") }
      duplicates = grouped.select { |_locale, items| items.length > 1 }.keys.compact
      unless duplicates.empty?
        raise ApiError, "Duplicate TestFlight build locales: #{duplicates.join(', ')}"
      end

      grouped.transform_values(&:first)
    end

    def verify!(build_id, desired_localizations)
      verified = localizations_by_locale(build_id)
      mismatches = desired_localizations.keys.reject do |locale|
        verified.dig(locale, "attributes", "whatsNew") == desired_localizations.fetch(locale)
      end
      return if mismatches.empty?

      raise ApiError, "TestFlight build-note verification failed for: #{mismatches.join(', ')}"
    end
  end
end
