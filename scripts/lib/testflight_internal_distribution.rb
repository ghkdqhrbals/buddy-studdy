# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "time"
require "uri"

module TestFlightInternalDistribution
  API_HOST = "api.appstoreconnect.apple.com"
  TERMINAL_PROCESSING_STATES = %w[FAILED INVALID].freeze
  READY_INTERNAL_STATES = %w[READY_FOR_BETA_TESTING IN_BETA_TESTING].freeze
  WAITING_INTERNAL_STATES = %w[PROCESSING].freeze
  BLOCKED_INTERNAL_STATES = %w[
    PROCESSING_EXCEPTION
    MISSING_EXPORT_COMPLIANCE
    IN_EXPORT_COMPLIANCE_REVIEW
    EXPIRED
  ].freeze
  SUPPORTED_BUILD_AUDIENCES = %w[APP_STORE_ELIGIBLE INTERNAL_ONLY].freeze

  class Error < StandardError; end
  class ConfigurationError < Error; end
  class IdentityError < Error; end
  class DistributionBlockedError < Error; end

  module_function

  def optional_value(value)
    value.to_s.strip.then { |candidate| candidate.empty? ? nil : candidate }
  end

  def validate_apply!(value)
    return false if value.nil? || value.empty? || value == "0"
    return true if value == "1"

    raise ConfigurationError, "APP_STORE_APPLY must be exactly 0 or 1"
  end

  def validate_build_audience!(value)
    audience = optional_value(value) || "APP_STORE_ELIGIBLE"
    return audience if SUPPORTED_BUILD_AUDIENCES.include?(audience)

    raise ConfigurationError,
          "TESTFLIGHT_EXPECTED_BUILD_AUDIENCE must be APP_STORE_ELIGIBLE or INTERNAL_ONLY"
  end

  def positive_number!(value, label, integer: false)
    number = integer ? Integer(value, 10) : Float(value)
    raise ArgumentError unless number.positive? && number.finite?

    number
  rescue ArgumentError, TypeError
    raise ConfigurationError, "#{label} must be a positive #{integer ? 'integer' : 'number'}"
  end

  class AppStoreConnectClient
    def initialize(key_id:, issuer_id:, private_key_path:, allow_writes: false,
                   sleeper: ->(seconds) { sleep(seconds) })
      @key_id = key_id
      @issuer_id = issuer_id
      @private_key = OpenSSL::PKey.read(File.read(private_key_path))
      @allow_writes = allow_writes
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
      raise IdentityError, "App not found for bundle ID: #{bundle_id}" if matches.empty?
      raise IdentityError, "Multiple apps found for bundle ID: #{bundle_id}" if matches.length > 1

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
          "fields[builds]" =>
            "version,uploadedDate,expirationDate,expired,processingState," \
            "buildAudienceType,preReleaseVersion",
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
          "uploadedDate" => build.dig("attributes", "uploadedDate"),
          "expirationDate" => build.dig("attributes", "expirationDate"),
          "expired" => build.dig("attributes", "expired"),
          "buildAudienceType" => build.dig("attributes", "buildAudienceType")
        }
      end
    end

    def read_build_app(build_id)
      request(
        :get,
        "/v1/builds/#{build_id}/app",
        query: { "fields[apps]" => "name,bundleId" }
      ).fetch("data")
    end

    def read_build_beta_detail(build_id)
      request(
        :get,
        "/v1/builds/#{build_id}/buildBetaDetail",
        query: {
          "fields[buildBetaDetails]" =>
            "internalBuildState,externalBuildState,autoNotifyEnabled"
        }
      ).fetch("data")
    end

    def beta_groups(app_id)
      collection_data(
        "/v1/apps/#{app_id}/betaGroups",
        query: {
          "fields[betaGroups]" =>
            "name,isInternalGroup,hasAccessToAllBuilds,publicLinkEnabled",
          "limit" => "200"
        }
      )
    end

    def read_beta_group_app(group_id)
      request(
        :get,
        "/v1/betaGroups/#{group_id}/app",
        query: { "fields[apps]" => "name,bundleId" }
      ).fetch("data")
    end

    def beta_group_build_ids(group_id)
      collection_data(
        "/v1/betaGroups/#{group_id}/relationships/builds",
        query: { "limit" => "200" }
      ).map { |linkage| linkage.fetch("id") }
    end

    def beta_group_tester_ids(group_id)
      collection_data(
        "/v1/betaGroups/#{group_id}/relationships/betaTesters",
        query: { "limit" => "200" }
      ).map { |tester| tester.fetch("id") }
    end

    def add_build_to_beta_group(group_id:, build_id:)
      raise ConfigurationError, "App Store Connect writes require APP_STORE_APPLY=1" \
        unless @allow_writes

      request(
        :post,
        "/v1/betaGroups/#{group_id}/relationships/builds",
        body: {
          data: [
            {
              type: "builds",
              id: build_id
            }
          ]
        }
      )
    end

    private

    def collection_data(path, query: nil)
      items = []
      current_path = path
      current_query = query

      loop do
        response = request(:get, current_path, query: current_query)
        items.concat(response.fetch("data"))
        next_url = response.dig("links", "next")
        break if next_url.nil? || next_url.empty?

        uri = URI(next_url)
        unless uri.scheme == "https" && uri.host == API_HOST
          raise Error, "Refusing unexpected App Store Connect pagination URL"
        end
        current_path = uri.path
        current_query = uri.query ? URI.decode_www_form(uri.query).to_h : nil
      end

      items
    end

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
      request_class = { get: Net::HTTP::Get, post: Net::HTTP::Post }.fetch(method)

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

      raise Error,
            "App Store Connect API request failed after retries: #{method.to_s.upcase} #{path}"
    end

    def parse_response(response)
      return {} if response.body.nil? || response.body.empty?

      JSON.parse(response.body)
    rescue JSON::ParserError
      { "errors" => [{ "detail" => "Response was not valid JSON" }] }
    end

    def raise_api_error(method, path, status, parsed)
      details = parsed.fetch("errors", []).filter_map do |error|
        error["detail"] || error["title"] || error["code"]
      end
      suffix = details.empty? ? "" : ": #{details.join('; ')}"
      raise Error,
            "App Store Connect API request failed: #{method.to_s.upcase} #{path} " \
            "returned #{status}#{suffix}"
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
        candidates = @client.build_candidates(
          app_id: app_id,
          marketing_version: marketing_version,
          build_number: build_number
        ).select do |build|
          build.fetch("buildNumber") == build_number &&
            build.fetch("marketingVersion") == marketing_version &&
            build.fetch("platform") == "IOS"
        end
        if candidates.length > 1
          raise DistributionBlockedError,
                "Multiple matching iOS builds found for #{marketing_version} (#{build_number})"
        end

        build = candidates.first
        status = build ? build.fetch("processingState") : "NOT_VISIBLE"
        if status != last_status
          @logger.puts "TestFlight build #{marketing_version} (#{build_number}): #{status}"
          last_status = status
        end
        return build if status == "VALID"
        if TERMINAL_PROCESSING_STATES.include?(status)
          raise DistributionBlockedError,
                "TestFlight build #{marketing_version} (#{build_number}) processing failed: #{status}"
        end

        remaining = deadline - @monotonic_clock.call
        if remaining <= 0
          raise DistributionBlockedError,
                "Timed out waiting for TestFlight build #{marketing_version} (#{build_number})"
        end
        @sleeper.call([interval_seconds, remaining].min)
      end
    end
  end

  class BetaReadinessWaiter
    def initialize(client:, logger: $stdout, sleeper: ->(seconds) { sleep(seconds) },
                   monotonic_clock: -> { Process.clock_gettime(Process::CLOCK_MONOTONIC) })
      @client = client
      @logger = logger
      @sleeper = sleeper
      @monotonic_clock = monotonic_clock
    end

    def wait(build_id:, marketing_version:, build_number:, timeout_seconds:, interval_seconds:)
      deadline = @monotonic_clock.call + timeout_seconds
      last_state = nil

      loop do
        detail = @client.read_build_beta_detail(build_id)
        state = detail.dig("attributes", "internalBuildState") || "UNKNOWN"
        if state != last_state
          @logger.puts "TestFlight internal state #{marketing_version} (#{build_number}): #{state}"
          last_state = state
        end
        return detail if READY_INTERNAL_STATES.include?(state)

        if BLOCKED_INTERNAL_STATES.include?(state) || !WAITING_INTERNAL_STATES.include?(state)
          raise DistributionBlockedError,
                "TestFlight build #{marketing_version} (#{build_number}) cannot be internally " \
                "distributed while internalBuildState is #{state}"
        end

        remaining = deadline - @monotonic_clock.call
        if remaining <= 0
          raise DistributionBlockedError,
                "Timed out waiting for TestFlight internal readiness for " \
                "#{marketing_version} (#{build_number})"
        end
        @sleeper.call([interval_seconds, remaining].min)
      end
    end
  end

  class Distributor
    def initialize(client:, logger: $stdout, wall_clock: -> { Time.now.utc })
      @client = client
      @logger = logger
      @wall_clock = wall_clock
    end

    def distribute(app:, build:, expected_app_id:, expected_bundle_id:,
                   expected_build_audience: "APP_STORE_ELIGIBLE", target_group_id: nil,
                   target_group_name: nil, apply: false)
      expected_build_audience =
        TestFlightInternalDistribution.validate_build_audience!(expected_build_audience)
      verify_app!(app, expected_app_id, expected_bundle_id)
      verify_build!(build, expected_build_audience)
      verify_build_app!(build.fetch("id"), expected_app_id, expected_bundle_id)
      group = resolve_group(
        app_id: expected_app_id,
        expected_bundle_id: expected_bundle_id,
        target_group_id: target_group_id,
        target_group_name: target_group_name
      )

      group_id = group.fetch("id")
      group_name = group.dig("attributes", "name")
      if @client.beta_group_tester_ids(group_id).empty?
        raise DistributionBlockedError,
              "Internal TestFlight group #{group_name} has no testers"
      end
      current_build_ids = @client.beta_group_build_ids(group_id)
      if current_build_ids.include?(build.fetch("id"))
        @logger.puts "Already distributed to internal group: #{group_name} (#{group_id})"
        return :current
      end

      @logger.puts "Planned internal group: #{group_name} (#{group_id})"
      unless apply
        @logger.puts "Dry run: no TestFlight relationship was changed."
        return :add
      end

      @client.add_build_to_beta_group(group_id: group_id, build_id: build.fetch("id"))
      verified_build_ids = @client.beta_group_build_ids(group_id)
      unless verified_build_ids.include?(build.fetch("id"))
        raise DistributionBlockedError,
              "Internal TestFlight distribution verification failed for group #{group_name}"
      end

      @logger.puts "Distributed and verified internal group: #{group_name} (#{group_id})"
      :added
    end

    private

    def verify_app!(app, expected_app_id, expected_bundle_id)
      return if app.fetch("id") == expected_app_id &&
                app.dig("attributes", "bundleId") == expected_bundle_id

      raise IdentityError,
            "Resolved app does not match expected app ID #{expected_app_id} and bundle " \
            "#{expected_bundle_id}"
    end

    def verify_build!(build, expected_build_audience)
      unless build.fetch("processingState") == "VALID"
        raise IdentityError, "Refusing to distribute a build that is not VALID"
      end
      unless build.fetch("buildAudienceType") == expected_build_audience
        raise IdentityError,
              "Refusing to distribute a build whose audience is not #{expected_build_audience}"
      end
      if build["expired"] == true
        raise DistributionBlockedError, "Refusing to distribute an expired TestFlight build"
      end

      expiration = TestFlightInternalDistribution.optional_value(build["expirationDate"])
      return unless expiration && Time.iso8601(expiration) <= @wall_clock.call

      raise DistributionBlockedError, "Refusing to distribute a TestFlight build past expiration"
    rescue ArgumentError
      raise IdentityError, "TestFlight build returned an invalid expiration date"
    end

    def verify_build_app!(build_id, expected_app_id, expected_bundle_id)
      build_app = @client.read_build_app(build_id)
      return if build_app.fetch("id") == expected_app_id &&
                build_app.dig("attributes", "bundleId") == expected_bundle_id

      raise IdentityError, "Target build belongs to a different App Store Connect app"
    end

    def resolve_group(app_id:, expected_bundle_id:, target_group_id:, target_group_name:)
      group_id = TestFlightInternalDistribution.optional_value(target_group_id)
      group_name = TestFlightInternalDistribution.optional_value(target_group_name)
      groups = @client.beta_groups(app_id)
      if group_id.nil? && group_name.nil?
        internal_groups = groups.select do |group|
          group.dig("attributes", "isInternalGroup") == true
        end
        return verify_group_app!(internal_groups.first, app_id, expected_bundle_id) \
          if internal_groups.length == 1

        candidates = internal_group_candidates(internal_groups)
        raise IdentityError,
              "Internal TestFlight group selector was omitted and exactly one internal group " \
              "was not found. Candidates: #{candidates}"
      end

      matches = groups.select do |group|
        (group_id.nil? || group.fetch("id") == group_id) &&
          (group_name.nil? || group.dig("attributes", "name") == group_name)
      end
      if matches.empty?
        raise IdentityError, "Specified TestFlight group was not found on the exact app"
      end
      if matches.length > 1
        raise IdentityError,
              "Specified TestFlight group name is ambiguous; provide its exact resource ID"
      end

      group = matches.first
      unless group.dig("attributes", "isInternalGroup") == true
        raise IdentityError, "Refusing to distribute to an external TestFlight group"
      end

      verify_group_app!(group, app_id, expected_bundle_id)
    end

    def verify_group_app!(group, app_id, expected_bundle_id)
      group_app = @client.read_beta_group_app(group.fetch("id"))
      unless group_app.fetch("id") == app_id &&
             group_app.dig("attributes", "bundleId") == expected_bundle_id
        raise IdentityError, "Specified TestFlight group belongs to a different app"
      end

      group
    end

    def internal_group_candidates(groups)
      return "(none)" if groups.empty?

      groups.map do |group|
        "#{group.dig('attributes', 'name')} (#{group.fetch('id')})"
      end.join(", ")
    end
  end
end
