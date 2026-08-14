#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "digest"
require "json"
require "net/http"
require "openssl"
require "uri"

module AppStoreScreenshots
  API_HOST = "api.appstoreconnect.apple.com"
  DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
  DEFAULT_LOCALE = "ko"
  DEFAULT_DISPLAY_TYPE = "APP_IPHONE_67"
  MAX_SCREENSHOTS_PER_SET = 10
  EDITABLE_STATES = %w[
    PREPARE_FOR_SUBMISSION
    READY_FOR_REVIEW
    INVALID_BINARY
    REJECTED
    METADATA_REJECTED
    DEVELOPER_REJECTED
  ].freeze
  LEGACY_MUTATION_ENV = %w[
    APP_STORE_DELETE_FAILED
    APP_STORE_DELETE_DUPLICATES
    APP_STORE_DELETE_FILE_NAMES
    APP_STORE_SCREENSHOT_ORDER
  ].freeze

  class APIError < StandardError
    def initialize(method, path, status, body)
      errors = body.is_a?(Hash) ? body.fetch("errors", []) : []
      details = errors.filter_map do |error|
        [error["code"], error["title"], error["detail"]].compact.join(": ")
      end
      suffix = details.empty? ? "" : " (#{details.join("; ")})"
      super("#{method.to_s.upcase} #{path} returned #{status}#{suffix}")
    end
  end

  class Client
    def initialize(token, allow_writes:, sleeper: Kernel)
      @token = token
      @allow_writes = allow_writes
      @sleeper = sleeper
    end

    def request(method, path, query: nil, body: nil)
      refuse_write!(method) unless method == :get
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
      attempts = method == :get ? 4 : 1

      attempts.times do |attempt|
        request = request_class.new(uri)
        request["Authorization"] = "Bearer #{@token}"
        request["Content-Type"] = "application/json"
        request.body = JSON.generate(body) if body
        response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: true) do |http|
          http.request(request)
        end
        parsed = parse_body(response.body)
        return parsed if response.is_a?(Net::HTTPSuccess)

        status = response.code.to_i
        if method == :get && attempt < attempts - 1 && (status == 429 || status >= 500)
          @sleeper.sleep(2**attempt)
          next
        end

        raise APIError.new(method, path, status, parsed)
      end
    end

    def upload_part(operation, file_path)
      refuse_write!(:upload)
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
      length = operation.fetch("length")
      File.open(file_path, "rb") do |file|
        file.seek(operation.fetch("offset"))
        request.body = file.read(length)
      end
      unless request.body && request.body.bytesize == length
        raise "Upload operation requested bytes outside #{File.basename(file_path)}"
      end

      response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: true) do |http|
        http.request(request)
      end
      return if response.is_a?(Net::HTTPSuccess)

      raise "Screenshot part upload returned #{response.code}"
    end

    private

    def refuse_write!(method)
      return if @allow_writes

      raise "Refusing #{method.to_s.upcase} because APP_STORE_APPLY is not 1"
    end

    def parse_body(body)
      return {} if body.nil? || body.empty?

      JSON.parse(body)
    rescue JSON::ParserError
      { "rawResponse" => "non-JSON response omitted" }
    end
  end

  class Sync
    def initialize(client:, apply:, env: ENV, output: $stdout, sleeper: Kernel)
      @client = client
      @apply = apply
      @env = env
      @output = output
      @sleeper = sleeper
      @new_screenshot_ids = []
    end

    def run(arguments)
      reject_legacy_mutation_flags!
      list_only = @env["APP_STORE_LIST_ONLY"] == "1"
      replace_all = @env["APP_STORE_REPLACE_ALL"] == "1"
      paths = arguments.map { |path| File.expand_path(path) }
      raise "APP_STORE_LIST_ONLY does not accept screenshot file arguments" if list_only && !paths.empty?
      if paths.empty? && !list_only
        raise "Usage: #{File.basename($PROGRAM_NAME)} <screenshot.png> [...]"
      end
      validate_files!(paths)

      app = find_app(@env.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID))
      version = find_editable_version(app.fetch("id"))
      locale = @env.fetch("APP_STORE_LOCALE", DEFAULT_LOCALE)
      display_type = @env.fetch("APP_SCREENSHOT_DISPLAY_TYPE", DEFAULT_DISPLAY_TYPE)
      localization = find_localization(version.fetch("id"), locale)
      screenshot_set = find_screenshot_set(localization.fetch("id"), display_type)
      screenshots = screenshot_set ? list_screenshots(screenshot_set.fetch("id")) : []

      print_context(app, version, locale, display_type, screenshots)
      return print_list(screenshots) if list_only

      expected_count = @env["APP_STORE_EXPECTED_FILE_COUNT"]
      if expected_count && paths.length != Integer(expected_count, 10)
        raise "Expected #{expected_count} screenshot files, got #{paths.length}"
      end
      if replace_all && screenshots.length + paths.length > MAX_SCREENSHOTS_PER_SET
        raise "Upload-first replacement would exceed #{MAX_SCREENSHOTS_PER_SET} screenshots " \
              "(#{screenshots.length} existing + #{paths.length} new)"
      end
      print_plan(paths, screenshots, replace_all, screenshot_set.nil?)
      unless @apply
        @output.puts "Dry run only. Set APP_STORE_APPLY=1 to apply this screenshot sync."
        return
      end

      screenshot_set ||= create_screenshot_set(localization.fetch("id"), display_type)
      begin
        uploaded = paths.map do |path|
          screenshot = upload_screenshot(screenshot_set.fetch("id"), path)
          wait_until_complete(screenshot.fetch("id"), File.basename(path))
        end
      rescue StandardError, Interrupt => error
        cleanup_errors = cleanup_new_uploads
        message = "Screenshot upload failed while the previous set was preserved: #{error.message}"
        message += "; cleanup failed: #{cleanup_errors.join("; ")}" unless cleanup_errors.empty?
        raise message
      end
      if replace_all
        screenshots.each do |screenshot|
          @client.request(:delete, "/v1/appScreenshots/#{screenshot.fetch("id")}")
          @output.puts "Deleted old screenshot: #{screenshot.dig("attributes", "fileName")}"
        end
        verify_only_new_ids!(screenshot_set.fetch("id"), uploaded.map { |item| item.fetch("id") })
      end
      final_order = if replace_all
                      uploaded
                    else
                      current = list_screenshots(screenshot_set.fetch("id"))
                      names = paths.map { |path| File.basename(path) }
                      current.sort_by do |screenshot|
                        index = names.index(screenshot.dig("attributes", "fileName"))
                        index || names.length + current.index(screenshot)
                      end
                    end
      reorder_screenshots(screenshot_set.fetch("id"), final_order)
      verify_final!(
        screenshot_set.fetch("id"),
        paths.map { |path| File.basename(path) },
        exact: replace_all
      )
      @output.puts "Verified screenshot order and COMPLETE processing state."
    end

    private

    def reject_legacy_mutation_flags!
      present = LEGACY_MUTATION_ENV.select do |name|
        value = @env[name]
        value && !value.empty? && value != "0"
      end
      return if present.empty?

      raise "Legacy mutation flags are no longer supported (#{present.join(", ")}); " \
            "use APP_STORE_REPLACE_ALL=1 with explicit ordered files"
    end

    def validate_files!(paths)
      names = paths.map { |path| File.basename(path) }
      raise "Screenshot filenames must be unique" unless names.uniq.length == names.length

      paths.each do |path|
        raise "Screenshot not found: #{path}" unless File.file?(path)
        raise "Screenshot is empty: #{path}" unless File.size(path).positive?

        signature = File.binread(path, 8)
        raise "Screenshot must be a PNG: #{path}" unless signature == "\x89PNG\r\n\x1a\n".b
      end
    end

    def find_app(bundle_id)
      response = @client.request(
        :get,
        "/v1/apps",
        query: { "filter[bundleId]" => bundle_id, "limit" => "200" }
      )
      response.fetch("data").find { |item| item.dig("attributes", "bundleId") == bundle_id } ||
        raise("App not found for bundle ID: #{bundle_id}")
    end

    def find_editable_version(app_id)
      versions = @client.request(
        :get,
        "/v1/apps/#{app_id}/appStoreVersions",
        query: { "filter[platform]" => "IOS", "limit" => "200" }
      ).fetch("data")
      if @env["APP_STORE_VERSION_ID"]
        return versions.find { |item| item.fetch("id") == @env["APP_STORE_VERSION_ID"] } ||
          raise("App Store version not found: #{@env["APP_STORE_VERSION_ID"]}")
      end
      if @env["APP_STORE_VERSION_STRING"]
        candidates = versions.select do |item|
          item.dig("attributes", "versionString") == @env["APP_STORE_VERSION_STRING"] &&
            EDITABLE_STATES.include?(item.dig("attributes", "appStoreState"))
        end
        raise "No editable iOS App Store version #{@env["APP_STORE_VERSION_STRING"]} found" if candidates.empty?
        raise "Multiple editable iOS App Store versions #{@env["APP_STORE_VERSION_STRING"]} found" if candidates.length > 1

        return candidates.first
      end

      versions.find { |item| EDITABLE_STATES.include?(item.dig("attributes", "appStoreState")) } ||
        raise("No editable iOS App Store version found")
    end

    def find_localization(version_id, locale)
      localizations = @client.request(
        :get,
        "/v1/appStoreVersions/#{version_id}/appStoreVersionLocalizations",
        query: { "limit" => "200" }
      ).fetch("data")
      localizations.find { |item| item.dig("attributes", "locale") == locale } ||
        raise("App Store version localization not found: #{locale}")
    end

    def find_screenshot_set(localization_id, display_type)
      @client.request(
        :get,
        "/v1/appStoreVersionLocalizations/#{localization_id}/appScreenshotSets",
        query: { "limit" => "200" }
      ).fetch("data").find do |item|
        item.dig("attributes", "screenshotDisplayType") == display_type
      end
    end

    def create_screenshot_set(localization_id, display_type)
      @client.request(
        :post,
        "/v1/appScreenshotSets",
        body: {
          data: {
            type: "appScreenshotSets",
            attributes: { screenshotDisplayType: display_type },
            relationships: {
              appStoreVersionLocalization: {
                data: { type: "appStoreVersionLocalizations", id: localization_id }
              }
            }
          }
        }
      ).fetch("data")
    end

    def list_screenshots(screenshot_set_id)
      document = @client.request(
        :get,
        "/v1/appScreenshotSets/#{screenshot_set_id}/appScreenshots",
        query: { "limit" => "200" }
      )
      raise "Screenshot set has more than 200 assets" if document.dig("links", "next")
      document.fetch("data")
    end

    def print_context(app, version, locale, display_type, screenshots)
      @output.puts "App: #{app.dig("attributes", "name")} (#{app.dig("attributes", "bundleId")})"
      @output.puts "Version: #{version.dig("attributes", "versionString")} " \
                   "[#{version.dig("attributes", "appStoreState")}]"
      @output.puts "Locale: #{locale}"
      @output.puts "Display type: #{display_type}"
      @output.puts "Existing screenshots: #{screenshots.length}"
    end

    def print_list(screenshots)
      screenshots.each do |screenshot|
        attributes = screenshot.fetch("attributes")
        state = attributes.dig("assetDeliveryState", "state") || "UNKNOWN"
        @output.puts "#{attributes.fetch("fileName")} [#{state}]"
      end
      @output.puts "Read-only list complete."
    end

    def print_plan(paths, screenshots, replace_all, create_set)
      @output.puts "Plan: #{create_set ? "create screenshot set; " : ""}" \
                   "upload #{paths.length} sequentially to COMPLETE; " \
                   "#{replace_all ? "delete #{screenshots.length} old assets; " : ""}" \
                   "reorder and verify."
      paths.each_with_index do |path, index|
        @output.puts "  #{index + 1}. #{File.basename(path)}"
      end
    end

    def upload_screenshot(screenshot_set_id, file_path)
      reservation = @client.request(
        :post,
        "/v1/appScreenshots",
        body: {
          data: {
            type: "appScreenshots",
            attributes: {
              fileName: File.basename(file_path),
              fileSize: File.size(file_path)
            },
            relationships: {
              appScreenshotSet: {
                data: { type: "appScreenshotSets", id: screenshot_set_id }
              }
            }
          }
        }
      ).fetch("data")
      @new_screenshot_ids << reservation.fetch("id")
      operations = reservation.dig("attributes", "uploadOperations") || []
      raise "Apple returned no upload operations for #{File.basename(file_path)}" if operations.empty?

      operations.each { |operation| @client.upload_part(operation, file_path) }
      @client.request(
        :patch,
        "/v1/appScreenshots/#{reservation.fetch("id")}",
        body: {
          data: {
            type: "appScreenshots",
            id: reservation.fetch("id"),
            attributes: {
              uploaded: true,
              sourceFileChecksum: Digest::MD5.file(file_path).hexdigest
            }
          }
        }
      )
      @output.puts "Committed: #{File.basename(file_path)}"
      reservation
    end

    def cleanup_new_uploads
      errors = []
      @new_screenshot_ids.each do |screenshot_id|
        begin
          @client.request(:delete, "/v1/appScreenshots/#{screenshot_id}")
          @output.puts "Cleaned up new screenshot after upload failure: #{screenshot_id}"
        rescue StandardError => error
          errors << "#{screenshot_id}: #{error.message}"
        end
      end
      errors
    end

    def verify_only_new_ids!(screenshot_set_id, expected_ids)
      timeout = Integer(@env.fetch("APP_STORE_ASSET_TIMEOUT_SECONDS", "600"), 10)
      interval = Float(@env.fetch("APP_STORE_ASSET_POLL_INTERVAL_SECONDS", "5"))
      deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + timeout
      loop do
        actual_ids = list_screenshots(screenshot_set_id).map { |item| item.fetch("id") }.sort
        return if actual_ids == expected_ids.sort
        raise "Old screenshot deletion verification timed out" if
          Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline

        @sleeper.sleep(interval)
      end
    end

    def wait_until_complete(screenshot_id, filename)
      timeout = Integer(@env.fetch("APP_STORE_ASSET_TIMEOUT_SECONDS", "600"), 10)
      interval = Float(@env.fetch("APP_STORE_ASSET_POLL_INTERVAL_SECONDS", "5"))
      raise "Asset timeout must be positive" unless timeout.positive?
      raise "Asset poll interval cannot be negative" if interval.negative?
      deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + timeout

      loop do
        screenshot = @client.request(
          :get,
          "/v1/appScreenshots/#{screenshot_id}",
          query: {
            "fields[appScreenshots]" =>
              "fileSize,fileName,sourceFileChecksum,assetDeliveryState"
          }
        ).fetch("data")
        state = screenshot.dig("attributes", "assetDeliveryState", "state")
        if state == "COMPLETE"
          @output.puts "Processed COMPLETE: #{filename}"
          return screenshot
        end
        if state == "FAILED"
          errors = screenshot.dig("attributes", "assetDeliveryState", "errors") || []
          raise "#{filename} processing failed: #{errors.map { |error| error["description"] }.compact.join("; ")}"
        end
        raise "#{filename} processing timed out in state #{state.inspect}" if
          Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline

        @sleeper.sleep(interval)
      end
    end

    def reorder_screenshots(screenshot_set_id, screenshots)
      @client.request(
        :patch,
        "/v1/appScreenshotSets/#{screenshot_set_id}/relationships/appScreenshots",
        body: {
          data: screenshots.map do |screenshot|
            { type: "appScreenshots", id: screenshot.fetch("id") }
          end
        }
      )
    end

    def verify_final!(screenshot_set_id, ordered_names, exact:)
      screenshots = list_screenshots(screenshot_set_id)
      actual_names = screenshots.map { |screenshot| screenshot.dig("attributes", "fileName") }
      if exact
        raise "Final screenshot order mismatch: #{actual_names.join(", ")}" unless actual_names == ordered_names
      else
        prefix = actual_names.take(ordered_names.length)
        raise "Uploaded screenshot order mismatch: #{actual_names.join(", ")}" unless prefix == ordered_names
      end
      incomplete = screenshots.select do |screenshot|
        ordered_names.include?(screenshot.dig("attributes", "fileName")) &&
          screenshot.dig("attributes", "assetDeliveryState", "state") != "COMPLETE"
      end
      raise "Final screenshot processing verification failed" unless incomplete.empty?
    end
  end

  module_function

  def required_env(name, env = ENV)
    value = env[name]
    raise "Missing required environment variable: #{name}" if value.nil? || value.empty?
    value
  end

  def base64url(value)
    Base64.urlsafe_encode64(value).delete("=")
  end

  def app_store_token(env = ENV)
    private_key = OpenSSL::PKey.read(File.read(required_env("APPSTORE_CONNECT_PRIVATE_KEY_PATH", env)))
    now = Time.now.to_i
    header = {
      alg: "ES256",
      kid: required_env("APPSTORE_CONNECT_KEY_ID", env),
      typ: "JWT"
    }
    payload = {
      iss: required_env("APPSTORE_CONNECT_ISSUER_ID", env),
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

  def main(arguments = ARGV, env = ENV)
    apply = env["APP_STORE_APPLY"] == "1"
    token = app_store_token(env)
    client = Client.new(token, allow_writes: apply)
    Sync.new(client: client, apply: apply, env: env).run(arguments)
  rescue StandardError, Interrupt => error
    warn error.message
    1
  else
    0
  end
end

exit AppStoreScreenshots.main if $PROGRAM_NAME == __FILE__
