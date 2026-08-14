#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "digest"
require "json"
require "net/http"
require "openssl"
require "uri"

module AppStoreReviewAssets
  API_HOST = "api.appstoreconnect.apple.com"
  DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
  DEFAULT_REVIEW_SUBMISSION_ID = "0d6aa057-848e-4b3a-b064-e55e89665328"
  DEFAULT_SUBSCRIPTIONS_PATH = File.expand_path(
    "../app-store/billing/subscriptions.json",
    __dir__
  )
  DEFAULT_SCREENSHOT_PATH = File.expand_path(
    "../app-store/review-assets/membership-review-2026-08-14-1242x2688.png",
    __dir__
  )
  EXPECTED_APP_ID = "6774108938"
  EXPECTED_IMAGE_WIDTH = 1_242
  EXPECTED_IMAGE_HEIGHT = 2_688
  MUTABLE_SUBMISSION_STATES = %w[READY_FOR_REVIEW UNRESOLVED_ISSUES].freeze
  REVIEWABLE_ITEM_RELATIONSHIPS = %w[
    appStoreVersion
    appCustomProductPageVersion
    appStoreVersionExperiment
    appStoreVersionExperimentV2
    appEvent
    backgroundAssetVersion
    gameCenterAchievementVersion
    gameCenterActivityVersion
    gameCenterChallengeVersion
    gameCenterLeaderboardSetVersion
    gameCenterLeaderboardVersion
    inAppPurchaseVersion
    subscriptionVersion
    subscriptionGroupVersion
  ].freeze
  # A freshly re-created item must be queued for the next submission. Accepted,
  # approved, rejected, and removed are documented states but are not resubmission-ready.
  EXPECTED_REATTACHED_ITEM_STATE = "READY_FOR_REVIEW"
  TARGET_PRODUCTS = {
    "TIER2" => {
      "appStoreConnectId" => "6797527129",
      "productId" => "io.github.ghkdqhrbals.StudyMate.tier2.monthly"
    },
    "TIER3" => {
      "appStoreConnectId" => "6797527130",
      "productId" => "io.github.ghkdqhrbals.StudyMate.tier3.monthly"
    }
  }.freeze

  class APIError < StandardError
    attr_reader :status

    def initialize(method, path, status, body)
      @status = status
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

    def request(method, path, query: nil, body: nil, allow_statuses: [])
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
        status = response.code.to_i
        return parsed if response.is_a?(Net::HTTPSuccess)
        return nil if allow_statuses.include?(status)

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
      offset = operation.fetch("offset")
      File.open(file_path, "rb") do |file|
        file.seek(offset)
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
    def initialize(client:, apply:, env: ENV, output: $stdout, error_output: $stderr, sleeper: Kernel)
      @client = client
      @apply = apply
      @env = env
      @output = output
      @error_output = error_output
      @sleeper = sleeper
      @created_screenshots = {}
      @target_items = []
    end

    def run
      screenshot_path = File.expand_path(
        @env.fetch("APP_STORE_REVIEW_SCREENSHOT_PATH", DEFAULT_SCREENSHOT_PATH)
      )
      validate_png!(screenshot_path)
      products = load_products
      submission_id = @env.fetch(
        "APP_STORE_REVIEW_SUBMISSION_ID",
        DEFAULT_REVIEW_SUBMISSION_ID
      )
      submission = read_submission(submission_id)
      validate_submission!(submission)
      validate_products!(products)

      item_document = list_submission_items(submission_id)
      validate_submission_composition!(item_document, require_ready_subscriptions: false)
      validate_submission_app!(item_document)
      @target_items = find_target_items(item_document, products)
      current_screenshots = products.to_h do |product|
        [product.fetch("appStoreConnectId"), read_subscription_screenshot(product.fetch("appStoreConnectId"))]
      end

      print_plan(submission, products, current_screenshots, screenshot_path)
      unless @apply
        @output.puts "Dry run only. Set APP_STORE_APPLY=1 to replace and verify review assets."
        return
      end

      begin
        detach_items
        verify_items_absent!(submission_id)
        products.each do |product|
          replace_subscription_screenshot(
            product,
            current_screenshots.fetch(product.fetch("appStoreConnectId")),
            screenshot_path
          )
        end
        readd_missing_items(submission_id)
        verify_final_state!(submission_id, products, screenshot_path)
      rescue StandardError, Interrupt => original_error
        recovery_errors = recover_after_failure(submission_id)
        message = "App Store review asset sync failed: #{original_error.message}"
        unless recovery_errors.empty?
          message += "; recovery also failed: #{recovery_errors.join("; ")}"
        end
        raise message
      end

      @output.puts "Verified two COMPLETE subscription screenshots and restored both submission items."
    end

    private

    def load_products
      path = File.expand_path(
        @env.fetch("APP_STORE_SUBSCRIPTIONS_PATH", DEFAULT_SUBSCRIPTIONS_PATH)
      )
      raise "Subscription manifest not found: #{path}" unless File.file?(path)

      manifest = JSON.parse(File.read(path))
      manifest_app = manifest.fetch("app")
      raise "Subscription manifest has an unexpected app ID" unless
        manifest_app.fetch("appleId").to_s == EXPECTED_APP_ID
      raise "Subscription manifest has an unexpected bundle ID" unless
        manifest_app.fetch("bundleId") == @env.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)

      by_tier = manifest.fetch("products").to_h { |product| [product.fetch("tierCode"), product] }
      TARGET_PRODUCTS.map do |tier_code, expected|
        product = by_tier.fetch(tier_code) { raise "Missing #{tier_code} in subscription manifest" }
        expected.each do |field, value|
          raise "#{tier_code} #{field} does not match the approved product" unless product.fetch(field) == value
        end
        product
      end
    end

    def validate_png!(path)
      raise "Review screenshot not found: #{path}" unless File.file?(path)
      raise "Review screenshot must not be empty: #{path}" unless File.size(path).positive?

      header = File.binread(path, 24)
      signature = "\x89PNG\r\n\x1a\n".b
      unless header.bytesize == 24 && header.start_with?(signature) && header.byteslice(12, 4) == "IHDR"
        raise "Review screenshot must have a valid PNG IHDR header: #{path}"
      end

      width, height = header.byteslice(16, 8).unpack("NN")
      return if width == EXPECTED_IMAGE_WIDTH && height == EXPECTED_IMAGE_HEIGHT

      raise "Review screenshot must be #{EXPECTED_IMAGE_WIDTH}x#{EXPECTED_IMAGE_HEIGHT}; got #{width}x#{height}"
    end

    def read_submission(submission_id)
      @client.request(
        :get,
        "/v1/reviewSubmissions/#{submission_id}",
        query: {
          "fields[reviewSubmissions]" => "platform,state"
        }
      ).fetch("data")
    end

    def validate_submission!(submission)
      raise "Review submission ID does not match the requested submission" unless
        submission.fetch("id") == @env.fetch(
          "APP_STORE_REVIEW_SUBMISSION_ID",
          DEFAULT_REVIEW_SUBMISSION_ID
        )
      raise "Review submission is not for iOS" unless submission.dig("attributes", "platform") == "IOS"

      state = submission.dig("attributes", "state")
      return unless @apply && !MUTABLE_SUBMISSION_STATES.include?(state)

      raise "Review submission state #{state.inspect} is not safe to mutate"
    end

    def validate_submission_app!(document)
      version_linkages = document.fetch("data").filter_map do |item|
        item.dig("relationships", "appStoreVersion", "data")
      end
      raise "Review submission must contain exactly one App Store version" unless
        version_linkages.length == 1 && version_linkages.first.fetch("type") == "appStoreVersions"

      version_id = version_linkages.first.fetch("id")
      response = @client.request(
        :get,
        "/v1/appStoreVersions/#{version_id}",
        query: {
          "fields[appStoreVersions]" => "platform,versionString,appStoreState,app",
          "fields[apps]" => "bundleId,name",
          "include" => "app"
        }
      )
      version = response.fetch("data")
      expected_version = @env.fetch("APP_STORE_VERSION_STRING", "1.1.0")
      raise "Review submission App Store version is not iOS #{expected_version}" unless
        version.dig("attributes", "platform") == "IOS" &&
        version.dig("attributes", "versionString") == expected_version
      raise "Review submission App Store version does not belong to BuddyStudy" unless
        version.dig("relationships", "app", "data", "id") == EXPECTED_APP_ID

      included_app = response.fetch("included", []).find do |resource|
        resource.fetch("type") == "apps" && resource.fetch("id") == EXPECTED_APP_ID
      end
      raise "BuddyStudy app was not included with the review version" unless included_app
      raise "Review submission bundle ID does not match BuddyStudy" unless
        included_app.dig("attributes", "bundleId") == @env.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
    end

    def validate_products!(products)
      products.each do |product|
        subscription_id = product.fetch("appStoreConnectId")
        remote = @client.request(
          :get,
          "/v1/subscriptions/#{subscription_id}",
          query: { "fields[subscriptions]" => "productId,name,state" }
        ).fetch("data")
        raise "Subscription resource ID mismatch for #{product.fetch("tierCode")}" unless
          remote.fetch("id") == subscription_id
        raise "Subscription product ID mismatch for #{product.fetch("tierCode")}" unless
          remote.dig("attributes", "productId") == product.fetch("productId")
      end
    end

    def list_submission_items(submission_id)
      document = @client.request(
        :get,
        "/v1/reviewSubmissions/#{submission_id}/items",
        query: {
          "fields[reviewSubmissionItems]" =>
            "state,appStoreVersion,subscriptionVersion,subscriptionGroupVersion",
          "fields[subscriptionVersions]" => "version,state,subscription",
          "include" => "appStoreVersion,subscriptionVersion,subscriptionGroupVersion",
          "limit" => "200"
        }
      )
      raise "Review submission has more than 200 items; refusing an incomplete sync" if
        document.dig("links", "next")
      document
    end

    def subscription_items(document)
      versions = document.fetch("included", []).select do |resource|
        resource.fetch("type") == "subscriptionVersions"
      end.to_h { |resource| [resource.fetch("id"), resource] }

      document.fetch("data").filter_map do |item|
        version_linkage = item.dig("relationships", "subscriptionVersion", "data")
        next unless version_linkage&.fetch("type") == "subscriptionVersions"

        version_id = version_linkage.fetch("id")
        version = versions.fetch(version_id) do
          raise "Subscription version #{version_id} was not included in the submission response"
        end
        subscription_id = version.dig("relationships", "subscription", "data", "id")
        raise "Subscription version #{version_id} has no subscription relationship" unless subscription_id

        {
          item_id: item.fetch("id"),
          version_id: version_id,
          subscription_id: subscription_id,
          state: item.dig("attributes", "state")
        }
      end
    end

    def find_target_items(document, products)
      items = subscription_items(document)
      expected_subscription_ids = products.map { |product| product.fetch("appStoreConnectId") }.sort
      actual_subscription_ids = items.map { |item| item.fetch(:subscription_id) }.sort
      unless actual_subscription_ids == expected_subscription_ids
        raise "Expected exactly the two approved subscriptionVersion items; found subscription IDs " \
              "#{actual_subscription_ids.join(", ")}"
      end
      raise "Duplicate subscriptionVersion item found" unless
        items.map { |item| item.fetch(:version_id) }.uniq.length == items.length

      items.sort_by { |item| item.fetch(:subscription_id) }
    end

    def read_subscription_screenshot(subscription_id)
      document = @client.request(
        :get,
        "/v1/subscriptions/#{subscription_id}/appStoreReviewScreenshot",
        query: {
          "fields[subscriptionAppStoreReviewScreenshots]" =>
            "fileSize,fileName,sourceFileChecksum,assetDeliveryState,subscription"
        },
        allow_statuses: [404]
      )
      document&.fetch("data")
    end

    def print_plan(submission, products, current_screenshots, screenshot_path)
      @output.puts "Review submission: #{submission.fetch("id")} [#{submission.dig("attributes", "state")}]"
      @output.puts "Replacement image: #{screenshot_path} (#{File.size(screenshot_path)} bytes)"
      products.each do |product|
        subscription_id = product.fetch("appStoreConnectId")
        item = @target_items.find { |candidate| candidate.fetch(:subscription_id) == subscription_id }
        screenshot = current_screenshots.fetch(subscription_id)
        current = if screenshot
                    "#{screenshot.dig("attributes", "fileName")} " \
                      "[#{screenshot.dig("attributes", "assetDeliveryState", "state")}]"
                  else
                    "none"
                  end
        @output.puts "#{product.fetch("tierCode")}: item #{item.fetch(:item_id)} " \
                     "[#{item.fetch(:state)}], subscriptionVersion #{item.fetch(:version_id)}, " \
                     "current screenshot #{current}"
      end
      @output.puts "Plan: detach 2 items, replace 2 screenshots sequentially, then re-add and verify 2 items."
    end

    def detach_items
      @target_items.each do |item|
        @client.request(:delete, "/v1/reviewSubmissionItems/#{item.fetch(:item_id)}")
        @output.puts "Detached review item: #{item.fetch(:item_id)}"
      end
    end

    def verify_items_absent!(submission_id)
      current_version_ids = subscription_items(list_submission_items(submission_id)).map do |item|
        item.fetch(:version_id)
      end
      still_attached = @target_items.map { |item| item.fetch(:version_id) } & current_version_ids
      raise "Review items were not detached: #{still_attached.join(", ")}" unless still_attached.empty?
    end

    def replace_subscription_screenshot(product, existing, screenshot_path)
      subscription_id = product.fetch("appStoreConnectId")
      if existing
        @client.request(
          :delete,
          "/v1/subscriptionAppStoreReviewScreenshots/#{existing.fetch("id")}"
        )
        @output.puts "Deleted old #{product.fetch("tierCode")} review screenshot: #{existing.fetch("id")}"
      end

      reservation = @client.request(
        :post,
        "/v1/subscriptionAppStoreReviewScreenshots",
        body: {
          data: {
            type: "subscriptionAppStoreReviewScreenshots",
            attributes: {
              fileName: File.basename(screenshot_path),
              fileSize: File.size(screenshot_path)
            },
            relationships: {
              subscription: {
                data: { type: "subscriptions", id: subscription_id }
              }
            }
          }
        }
      ).fetch("data")
      screenshot_id = reservation.fetch("id")
      @created_screenshots[subscription_id] = screenshot_id
      operations = reservation.dig("attributes", "uploadOperations") || []
      raise "Apple returned no upload operations for #{product.fetch("tierCode")}" if operations.empty?

      operations.each { |operation| @client.upload_part(operation, screenshot_path) }
      @client.request(
        :patch,
        "/v1/subscriptionAppStoreReviewScreenshots/#{screenshot_id}",
        body: {
          data: {
            type: "subscriptionAppStoreReviewScreenshots",
            id: screenshot_id,
            attributes: {
              uploaded: true,
              sourceFileChecksum: Digest::MD5.file(screenshot_path).hexdigest
            }
          }
        }
      )
      wait_until_complete(screenshot_id, product.fetch("tierCode"))
      @output.puts "Uploaded COMPLETE #{product.fetch("tierCode")} review screenshot: #{screenshot_id}"
    end

    def wait_until_complete(screenshot_id, label)
      timeout = Integer(@env.fetch("APP_STORE_ASSET_TIMEOUT_SECONDS", "600"), 10)
      interval = Float(@env.fetch("APP_STORE_ASSET_POLL_INTERVAL_SECONDS", "5"))
      raise "Asset timeout must be positive" unless timeout.positive?
      raise "Asset poll interval cannot be negative" if interval.negative?
      deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + timeout

      loop do
        screenshot = read_screenshot(screenshot_id)
        state = screenshot.dig("attributes", "assetDeliveryState", "state")
        return screenshot if state == "COMPLETE"
        if state == "FAILED"
          errors = screenshot.dig("attributes", "assetDeliveryState", "errors") || []
          raise "#{label} screenshot processing failed: #{errors.map { |error| error["description"] }.compact.join("; ")}"
        end
        raise "#{label} screenshot processing timed out in state #{state.inspect}" if
          Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline

        @sleeper.sleep(interval)
      end
    end

    def read_screenshot(screenshot_id, allow_missing: false)
      document = @client.request(
        :get,
        "/v1/subscriptionAppStoreReviewScreenshots/#{screenshot_id}",
        query: {
          "fields[subscriptionAppStoreReviewScreenshots]" =>
            "fileSize,fileName,sourceFileChecksum,assetDeliveryState,subscription"
        },
        allow_statuses: allow_missing ? [404] : []
      )
      document&.fetch("data")
    end

    def create_review_item(submission_id, version_id)
      @client.request(
        :post,
        "/v1/reviewSubmissionItems",
        body: {
          data: {
            type: "reviewSubmissionItems",
            relationships: {
              reviewSubmission: {
                data: { type: "reviewSubmissions", id: submission_id }
              },
              subscriptionVersion: {
                data: { type: "subscriptionVersions", id: version_id }
              }
            }
          }
        }
      ).fetch("data")
    end

    def readd_missing_items(submission_id)
      current = subscription_items(list_submission_items(submission_id))
      current_version_ids = current.map { |item| item.fetch(:version_id) }
      @target_items.each do |target|
        next if current_version_ids.include?(target.fetch(:version_id))

        created = create_review_item(submission_id, target.fetch(:version_id))
        @output.puts "Re-added subscriptionVersion #{target.fetch(:version_id)} as item #{created.fetch("id")}"
      end
    end

    def verify_final_state!(submission_id, products, screenshot_path)
      checksum = Digest::MD5.file(screenshot_path).hexdigest
      products.each do |product|
        subscription_id = product.fetch("appStoreConnectId")
        expected_screenshot_id = @created_screenshots.fetch(subscription_id)
        screenshot = read_subscription_screenshot(subscription_id)
        raise "Missing final screenshot for #{product.fetch("tierCode")}" unless screenshot
        raise "Final screenshot ID mismatch for #{product.fetch("tierCode")}" unless
          screenshot.fetch("id") == expected_screenshot_id
        raise "Final screenshot is not COMPLETE for #{product.fetch("tierCode")}" unless
          screenshot.dig("attributes", "assetDeliveryState", "state") == "COMPLETE"
        raise "Final screenshot filename mismatch for #{product.fetch("tierCode")}" unless
          screenshot.dig("attributes", "fileName") == File.basename(screenshot_path)
        raise "Final screenshot checksum mismatch for #{product.fetch("tierCode")}" unless
          screenshot.dig("attributes", "sourceFileChecksum").to_s.downcase == checksum
        raise "Final screenshot relationship mismatch for #{product.fetch("tierCode")}" unless
          screenshot.dig("relationships", "subscription", "data", "id") == subscription_id
      end

      final_document = list_submission_items(submission_id)
      validate_submission_composition!(final_document, require_ready_subscriptions: true)
      current = subscription_items(final_document)
      expected_versions = @target_items.map { |item| item.fetch(:version_id) }.sort
      actual_targets = current.select { |item| expected_versions.include?(item.fetch(:version_id)) }
      actual_versions = actual_targets.map { |item| item.fetch(:version_id) }.sort
      raise "Final review submission item relationship verification failed" unless
        actual_versions == expected_versions && actual_targets.length == expected_versions.length
      states = actual_targets.map { |item| item.fetch(:state) }.uniq
      raise "Final subscription review items are not #{EXPECTED_REATTACHED_ITEM_STATE}: #{states.join(", ")}" unless
        states == [EXPECTED_REATTACHED_ITEM_STATE]

      @output.puts "Final submission: 4 items (1 appStoreVersion, 1 subscriptionGroupVersion, " \
                   "2 subscriptionVersion); subscription items #{EXPECTED_REATTACHED_ITEM_STATE}."
    end

    def validate_submission_composition!(document, require_ready_subscriptions:)
      items = document.fetch("data")
      raise "Review submission must contain exactly 4 items; found #{items.length}" unless items.length == 4

      counts = Hash.new(0)
      items.each do |item|
        relationships = REVIEWABLE_ITEM_RELATIONSHIPS.select do |name|
          item.dig("relationships", name, "data")
        end
        unless relationships.length == 1
          raise "Review item #{item.fetch("id")} must have exactly one reviewable relationship"
        end
        counts[relationships.first] += 1
      end
      expected = {
        "appStoreVersion" => 1,
        "subscriptionGroupVersion" => 1,
        "subscriptionVersion" => 2
      }
      raise "Unexpected review submission composition: #{counts.inspect}" unless counts == expected

      return unless require_ready_subscriptions

      states = subscription_items(document).map { |item| item.fetch(:state) }
      return if states.length == 2 && states.all? { |state| state == EXPECTED_REATTACHED_ITEM_STATE }

      raise "Re-attached subscription items must be #{EXPECTED_REATTACHED_ITEM_STATE}; found #{states.join(", ")}"
    end

    def recover_after_failure(submission_id)
      errors = []
      @created_screenshots.each_value do |screenshot_id|
        begin
          screenshot = read_screenshot(screenshot_id, allow_missing: true)
          next unless screenshot
          next if screenshot.dig("attributes", "assetDeliveryState", "state") == "COMPLETE"

          @client.request(:delete, "/v1/subscriptionAppStoreReviewScreenshots/#{screenshot_id}")
          @error_output.puts "Recovery deleted incomplete screenshot reservation #{screenshot_id}."
        rescue StandardError => error
          errors << "cleanup #{screenshot_id}: #{error.message}"
        end
      end

      begin
        readd_missing_items(submission_id)
        current_versions = subscription_items(list_submission_items(submission_id)).map do |item|
          item.fetch(:version_id)
        end
        missing = @target_items.map { |item| item.fetch(:version_id) } - current_versions
        raise "missing subscriptionVersions #{missing.join(", ")}" unless missing.empty?

        @error_output.puts "Recovery restored both subscriptionVersion relationships."
      rescue StandardError => error
        errors << "restore review items: #{error.message}"
      end
      errors
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

  def main(env = ENV)
    apply = env["APP_STORE_APPLY"] == "1"
    token = app_store_token(env)
    client = Client.new(token, allow_writes: apply)
    Sync.new(client: client, apply: apply, env: env).run
  rescue StandardError, Interrupt => error
    warn error.message
    1
  else
    0
  end
end

exit AppStoreReviewAssets.main if $PROGRAM_NAME == __FILE__
