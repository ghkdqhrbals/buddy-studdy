#!/usr/bin/env ruby
# frozen_string_literal: true

require_relative "lib/testflight_internal_distribution"

DEFAULT_APP_ID = "6774108938"
DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"

def required_env(name)
  value = ENV[name]
  if value.nil? || value.strip.empty?
    raise TestFlightInternalDistribution::ConfigurationError,
          "Missing required environment variable: #{name}"
  end

  value.strip
end

begin
  marketing_version = required_env("APP_STORE_VERSION_STRING")
  build_number = required_env("APP_STORE_BUILD_NUMBER")
  expected_app_id = ENV.fetch("APP_STORE_APP_ID", DEFAULT_APP_ID).strip
  bundle_id = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID).strip
  raise TestFlightInternalDistribution::ConfigurationError,
        "APP_STORE_APP_ID must not be empty" if expected_app_id.empty?
  raise TestFlightInternalDistribution::ConfigurationError,
        "APP_BUNDLE_ID must not be empty" if bundle_id.empty?

  timeout_seconds = TestFlightInternalDistribution.positive_number!(
    ENV.fetch("TESTFLIGHT_BUILD_WAIT_TIMEOUT_SECONDS", "1800"),
    "TESTFLIGHT_BUILD_WAIT_TIMEOUT_SECONDS",
    integer: true
  )
  interval_seconds = TestFlightInternalDistribution.positive_number!(
    ENV.fetch("TESTFLIGHT_BUILD_WAIT_INTERVAL_SECONDS", "30"),
    "TESTFLIGHT_BUILD_WAIT_INTERVAL_SECONDS"
  )
  apply = TestFlightInternalDistribution.validate_apply!(ENV["APP_STORE_APPLY"])
  client = TestFlightInternalDistribution::AppStoreConnectClient.new(
    key_id: required_env("APPSTORE_CONNECT_KEY_ID"),
    issuer_id: required_env("APPSTORE_CONNECT_ISSUER_ID"),
    private_key_path: required_env("APPSTORE_CONNECT_PRIVATE_KEY_PATH"),
    allow_writes: apply
  )

  app = client.find_app(bundle_id)
  unless app.fetch("id") == expected_app_id
    raise TestFlightInternalDistribution::IdentityError,
          "Bundle #{bundle_id} resolved to app #{app.fetch('id')}, expected #{expected_app_id}"
  end
  build = TestFlightInternalDistribution::BuildWaiter.new(client: client).wait(
    app_id: expected_app_id,
    marketing_version: marketing_version,
    build_number: build_number,
    timeout_seconds: timeout_seconds,
    interval_seconds: interval_seconds
  )
  TestFlightInternalDistribution::BetaReadinessWaiter.new(client: client).wait(
    build_id: build.fetch("id"),
    marketing_version: marketing_version,
    build_number: build_number,
    timeout_seconds: timeout_seconds,
    interval_seconds: interval_seconds
  )

  puts "App: #{app.dig('attributes', 'name')} (#{expected_app_id}, #{bundle_id})"
  puts "Exact build: #{marketing_version} (#{build_number}) [#{build.fetch('processingState')}]"
  result = TestFlightInternalDistribution::Distributor.new(client: client).distribute(
    app: app,
    build: build,
    expected_app_id: expected_app_id,
    expected_bundle_id: bundle_id,
    target_group_id: ENV["TESTFLIGHT_INTERNAL_GROUP_ID"],
    target_group_name: ENV["TESTFLIGHT_INTERNAL_GROUP_NAME"],
    apply: apply
  )
  puts "Internal distribution result: #{result}"
rescue TestFlightInternalDistribution::Error => error
  abort error.message
end
