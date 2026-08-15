#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require_relative "lib/testflight_build_notes"

DEFAULT_BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"
DEFAULT_LOCALIZATIONS_PATH = File.expand_path(
  "../app-store/metadata/testflight-build-localizations.json",
  __dir__
)

def required_env(name)
  value = ENV[name]
  raise TestFlightBuildNotes::ConfigurationError,
        "Missing required environment variable: #{name}" if value.nil? || value.empty?

  value
end

begin
  localizations_path = File.expand_path(
    ENV.fetch("TESTFLIGHT_BUILD_LOCALIZATIONS_PATH", DEFAULT_LOCALIZATIONS_PATH)
  )
  raise TestFlightBuildNotes::ConfigurationError,
        "TestFlight build-localizations file not found" unless File.file?(localizations_path)

  localizations = JSON.parse(File.read(localizations_path))
  TestFlightBuildNotes.validate_localizations!(localizations)

  if ENV["TESTFLIGHT_BUILD_NOTES_VALIDATE_ONLY"] == "1"
    puts "TestFlight build localizations are valid: #{localizations.keys.join(', ')}"
    exit
  end

  marketing_version = required_env("APP_STORE_VERSION_STRING")
  build_number = required_env("APP_STORE_BUILD_NUMBER")
  timeout_seconds = TestFlightBuildNotes.positive_number!(
    ENV.fetch("TESTFLIGHT_BUILD_WAIT_TIMEOUT_SECONDS", "1800"),
    "TESTFLIGHT_BUILD_WAIT_TIMEOUT_SECONDS",
    integer: true
  )
  interval_seconds = TestFlightBuildNotes.positive_number!(
    ENV.fetch("TESTFLIGHT_BUILD_WAIT_INTERVAL_SECONDS", "30"),
    "TESTFLIGHT_BUILD_WAIT_INTERVAL_SECONDS"
  )
  client = TestFlightBuildNotes::AppStoreConnectClient.new(
    key_id: required_env("APPSTORE_CONNECT_KEY_ID"),
    issuer_id: required_env("APPSTORE_CONNECT_ISSUER_ID"),
    private_key_path: required_env("APPSTORE_CONNECT_PRIVATE_KEY_PATH")
  )
  bundle_id = ENV.fetch("APP_BUNDLE_ID", DEFAULT_BUNDLE_ID)
  app = client.find_app(bundle_id)
  build = TestFlightBuildNotes::BuildWaiter.new(client: client).wait(
    app_id: app.fetch("id"),
    marketing_version: marketing_version,
    build_number: build_number,
    timeout_seconds: timeout_seconds,
    interval_seconds: interval_seconds
  )

  puts "App: #{app.dig('attributes', 'name')} (#{bundle_id})"
  puts "Build ready: #{marketing_version} (#{build_number}) [#{build.fetch('processingState')}]"
  apply = ENV["APP_STORE_APPLY"] == "1"
  TestFlightBuildNotes::Synchronizer.new(client: client).sync(
    build_id: build.fetch("id"),
    desired_localizations: localizations,
    apply: apply
  )
  puts apply ? "Updated and verified TestFlight build notes." :
    "Dry run only. Set APP_STORE_APPLY=1 to update TestFlight build notes."
rescue JSON::ParserError => error
  abort "Invalid TestFlight build-localizations JSON: #{error.message}"
rescue TestFlightBuildNotes::Error => error
  abort error.message
end
