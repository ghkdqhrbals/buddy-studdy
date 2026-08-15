# frozen_string_literal: true

require "minitest/autorun"
require "stringio"
require_relative "../lib/testflight_build_notes"

class TestFlightBuildNotesTest < Minitest::Test
  LOCALIZATIONS = {
    "en-US" => "App Review candidate using the production API.",
    "ko" => "운영 API를 사용하는 App Store 심사용 후보 빌드입니다.",
    "ja" => "本番APIを使用するApp Store審査用候補ビルドです。"
  }.freeze

  class FakeClient
    attr_reader :creates, :updates

    def initialize(build_responses: [], localization_responses: [])
      @build_responses = build_responses
      @localization_responses = localization_responses
      @creates = []
      @updates = []
    end

    def build_candidates(**_arguments)
      @build_responses.shift || @build_responses.last || []
    end

    def beta_build_localizations(_build_id)
      response = @localization_responses.shift
      response.nil? ? [] : Marshal.load(Marshal.dump(response))
    end

    def create_beta_build_localization(**arguments)
      @creates << arguments
    end

    def update_beta_build_localization(**arguments)
      @updates << arguments
    end
  end

  def build(state:, id: "build-id", version: "1.1.0", number: "89", platform: "IOS")
    {
      "id" => id,
      "buildNumber" => number,
      "marketingVersion" => version,
      "platform" => platform,
      "processingState" => state,
      "uploadedDate" => "2026-08-15T00:00:00Z"
    }
  end

  def localization(id, locale, text)
    {
      "id" => id,
      "attributes" => {
        "locale" => locale,
        "whatsNew" => text
      }
    }
  end

  def test_validates_exact_required_locales_and_content
    assert_equal LOCALIZATIONS, TestFlightBuildNotes.validate_localizations!(LOCALIZATIONS)

    missing = LOCALIZATIONS.reject { |locale, _text| locale == "ja" }
    error = assert_raises(TestFlightBuildNotes::ConfigurationError) do
      TestFlightBuildNotes.validate_localizations!(missing)
    end
    assert_includes error.message, "Missing"

    empty = LOCALIZATIONS.merge("ko" => "  ")
    assert_raises(TestFlightBuildNotes::ConfigurationError) do
      TestFlightBuildNotes.validate_localizations!(empty)
    end
  end

  def test_validates_positive_finite_wait_values
    assert_equal 30, TestFlightBuildNotes.positive_number!("30", "timeout", integer: true)
    assert_equal 0.5, TestFlightBuildNotes.positive_number!("0.5", "interval")

    %w[0 -1 Infinity NaN].each do |value|
      assert_raises(TestFlightBuildNotes::ConfigurationError) do
        TestFlightBuildNotes.positive_number!(value, "wait")
      end
    end
  end

  def test_client_uses_all_exact_build_filters
    client = TestFlightBuildNotes::AppStoreConnectClient.allocate
    calls = []
    client.define_singleton_method(:request) do |method, path, query: nil, body: nil|
      calls << { method: method, path: path, query: query, body: body }
      {
        "data" => [
          {
            "id" => "build-id",
            "attributes" => {
              "version" => "89",
              "processingState" => "VALID",
              "uploadedDate" => "2026-08-15T00:00:00Z"
            },
            "relationships" => {
              "preReleaseVersion" => {
                "data" => { "type" => "preReleaseVersions", "id" => "prerelease-id" }
              }
            }
          }
        ],
        "included" => [
          {
            "type" => "preReleaseVersions",
            "id" => "prerelease-id",
            "attributes" => { "version" => "1.1.0", "platform" => "IOS" }
          }
        ]
      }
    end

    builds = client.build_candidates(
      app_id: "app-id",
      marketing_version: "1.1.0",
      build_number: "89"
    )

    assert_equal "build-id", builds.first.fetch("id")
    query = calls.fetch(0).fetch(:query)
    assert_equal "app-id", query.fetch("filter[app]")
    assert_equal "89", query.fetch("filter[version]")
    assert_equal "1.1.0", query.fetch("filter[preReleaseVersion.version]")
    assert_equal "IOS", query.fetch("filter[preReleaseVersion.platform]")
  end

  def test_client_builds_supported_create_and_update_payloads
    client = TestFlightBuildNotes::AppStoreConnectClient.allocate
    calls = []
    client.define_singleton_method(:request) do |method, path, query: nil, body: nil|
      calls << { method: method, path: path, query: query, body: body }
      {}
    end

    client.create_beta_build_localization(
      build_id: "build-id",
      locale: "ko",
      whats_new: "심사용 후보"
    )
    client.update_beta_build_localization(
      localization_id: "localization-id",
      whats_new: "심사용 후보"
    )

    create = calls.fetch(0)
    assert_equal :post, create.fetch(:method)
    assert_equal "/v1/betaBuildLocalizations", create.fetch(:path)
    assert_equal "ko", create.dig(:body, :data, :attributes, :locale)
    assert_equal "build-id", create.dig(:body, :data, :relationships, :build, :data, :id)
    update = calls.fetch(1)
    assert_equal :patch, update.fetch(:method)
    assert_equal "/v1/betaBuildLocalizations/localization-id", update.fetch(:path)
    assert_equal "심사용 후보", update.dig(:body, :data, :attributes, :whatsNew)
  end

  def test_waits_until_the_exact_build_is_valid
    clock = 0.0
    client = FakeClient.new(
      build_responses: [
        [],
        [build(state: "PROCESSING"), build(state: "VALID", id: "wrong", version: "1.0.0")],
        [build(state: "VALID")]
      ]
    )
    waiter = TestFlightBuildNotes::BuildWaiter.new(
      client: client,
      logger: StringIO.new,
      monotonic_clock: -> { clock },
      sleeper: ->(seconds) { clock += seconds }
    )

    result = waiter.wait(
      app_id: "app-id",
      marketing_version: "1.1.0",
      build_number: "89",
      timeout_seconds: 10,
      interval_seconds: 1
    )

    assert_equal "build-id", result.fetch("id")
  end

  def test_stops_on_terminal_processing_failure
    client = FakeClient.new(build_responses: [[build(state: "INVALID")]])
    waiter = TestFlightBuildNotes::BuildWaiter.new(client: client, logger: StringIO.new)

    error = assert_raises(TestFlightBuildNotes::BuildProcessingError) do
      waiter.wait(
        app_id: "app-id",
        marketing_version: "1.1.0",
        build_number: "89",
        timeout_seconds: 10,
        interval_seconds: 1
      )
    end
    assert_includes error.message, "INVALID"
  end

  def test_times_out_when_the_build_never_appears
    clock = 0.0
    client = FakeClient.new(build_responses: [[], [], []])
    waiter = TestFlightBuildNotes::BuildWaiter.new(
      client: client,
      logger: StringIO.new,
      monotonic_clock: -> { clock },
      sleeper: ->(seconds) { clock += seconds }
    )

    error = assert_raises(TestFlightBuildNotes::BuildProcessingError) do
      waiter.wait(
        app_id: "app-id",
        marketing_version: "1.1.0",
        build_number: "89",
        timeout_seconds: 2,
        interval_seconds: 1
      )
    end
    assert_includes error.message, "Timed out"
  end

  def test_rejects_ambiguous_exact_builds
    client = FakeClient.new(
      build_responses: [[build(state: "VALID"), build(state: "VALID", id: "second")]]
    )
    waiter = TestFlightBuildNotes::BuildWaiter.new(client: client, logger: StringIO.new)

    assert_raises(TestFlightBuildNotes::BuildProcessingError) do
      waiter.wait(
        app_id: "app-id",
        marketing_version: "1.1.0",
        build_number: "89",
        timeout_seconds: 10,
        interval_seconds: 1
      )
    end
  end

  def test_dry_run_plans_without_writing
    current = [
      localization("en", "en-US", LOCALIZATIONS.fetch("en-US")),
      localization("ko", "ko", "old")
    ]
    client = FakeClient.new(localization_responses: [current])
    actions = TestFlightBuildNotes::Synchronizer.new(
      client: client,
      logger: StringIO.new
    ).sync(build_id: "build-id", desired_localizations: LOCALIZATIONS, apply: false)

    assert_equal({ "en-US" => :current, "ko" => :update, "ja" => :create }, actions)
    assert_empty client.creates
    assert_empty client.updates
  end

  def test_apply_upserts_and_verifies_localizations
    current = [
      localization("en", "en-US", LOCALIZATIONS.fetch("en-US")),
      localization("ko", "ko", "old")
    ]
    verified = LOCALIZATIONS.map do |locale, text|
      localization(locale, locale, text)
    end
    client = FakeClient.new(localization_responses: [current, verified])
    actions = TestFlightBuildNotes::Synchronizer.new(
      client: client,
      logger: StringIO.new
    ).sync(build_id: "build-id", desired_localizations: LOCALIZATIONS, apply: true)

    assert_equal({ "en-US" => :current, "ko" => :update, "ja" => :create }, actions)
    assert_equal [{ localization_id: "ko", whats_new: LOCALIZATIONS.fetch("ko") }], client.updates
    assert_equal [
      {
        build_id: "build-id",
        locale: "ja",
        whats_new: LOCALIZATIONS.fetch("ja")
      }
    ], client.creates
  end

  def test_apply_fails_when_verification_does_not_match
    current = []
    client = FakeClient.new(localization_responses: [current, current])
    synchronizer = TestFlightBuildNotes::Synchronizer.new(client: client, logger: StringIO.new)

    assert_raises(TestFlightBuildNotes::ApiError) do
      synchronizer.sync(
        build_id: "build-id",
        desired_localizations: LOCALIZATIONS,
        apply: true
      )
    end
  end
end
