# frozen_string_literal: true

require "minitest/autorun"
require "stringio"
require_relative "../lib/testflight_internal_distribution"

class TestFlightInternalDistributionTest < Minitest::Test
  APP_ID = "6774108938"
  BUNDLE_ID = "io.github.ghkdqhrbals.StudyMate"

  class FakeClient
    attr_reader :added

    def initialize(beta_details: [], groups: [], group_build_responses: [],
                   build_app: nil, group_app: nil, tester_ids: ["tester-id"])
      @beta_details = beta_details
      @groups = groups
      @group_build_responses = group_build_responses
      @build_app = build_app
      @group_app = group_app
      @tester_ids = tester_ids
      @added = []
    end

    def read_build_beta_detail(_build_id)
      @beta_details.shift || @beta_details.last
    end

    def read_build_app(_build_id)
      @build_app
    end

    def beta_groups(_app_id)
      @groups
    end

    def read_beta_group_app(_group_id)
      @group_app
    end

    def beta_group_build_ids(_group_id)
      response = @group_build_responses.shift
      response.nil? ? [] : response
    end

    def beta_group_tester_ids(_group_id)
      @tester_ids
    end

    def add_build_to_beta_group(group_id:, build_id:)
      @added << { group_id: group_id, build_id: build_id }
    end
  end

  def app(id: APP_ID, bundle_id: BUNDLE_ID)
    {
      "type" => "apps",
      "id" => id,
      "attributes" => { "name" => "BuddyStudy", "bundleId" => bundle_id }
    }
  end

  def build(expired: false, expiration_date: "2026-11-13T00:00:00Z",
            audience: "APP_STORE_ELIGIBLE")
    {
      "id" => "build-id",
      "buildNumber" => "89",
      "marketingVersion" => "1.1.0",
      "platform" => "IOS",
      "processingState" => "VALID",
      "buildAudienceType" => audience,
      "expired" => expired,
      "expirationDate" => expiration_date
    }
  end

  def group(id: "group-id", name: "BuddyStudy Internal", internal: true)
    {
      "type" => "betaGroups",
      "id" => id,
      "attributes" => {
        "name" => name,
        "isInternalGroup" => internal,
        "hasAccessToAllBuilds" => false,
        "publicLinkEnabled" => false
      }
    }
  end

  def beta_detail(state)
    {
      "type" => "buildBetaDetails",
      "id" => "detail-id",
      "attributes" => { "internalBuildState" => state }
    }
  end

  def distributor(client)
    TestFlightInternalDistribution::Distributor.new(
      client: client,
      logger: StringIO.new,
      wall_clock: -> { Time.iso8601("2026-08-16T00:00:00Z") }
    )
  end

  def test_apply_requires_an_explicit_one
    assert_equal false, TestFlightInternalDistribution.validate_apply!(nil)
    assert_equal false, TestFlightInternalDistribution.validate_apply!("0")
    assert_equal true, TestFlightInternalDistribution.validate_apply!("1")

    assert_raises(TestFlightInternalDistribution::ConfigurationError) do
      TestFlightInternalDistribution.validate_apply!("true")
    end
  end

  def test_beta_readiness_waits_for_an_internal_ready_state
    clock = 0.0
    client = FakeClient.new(
      beta_details: [beta_detail("PROCESSING"), beta_detail("READY_FOR_BETA_TESTING")]
    )
    waiter = TestFlightInternalDistribution::BetaReadinessWaiter.new(
      client: client,
      logger: StringIO.new,
      monotonic_clock: -> { clock },
      sleeper: ->(seconds) { clock += seconds }
    )

    result = waiter.wait(
      build_id: "build-id",
      marketing_version: "1.1.0",
      build_number: "89",
      timeout_seconds: 10,
      interval_seconds: 1
    )

    assert_equal "READY_FOR_BETA_TESTING", result.dig("attributes", "internalBuildState")
  end

  def test_beta_readiness_reports_export_compliance_as_a_blocker
    client = FakeClient.new(beta_details: [beta_detail("MISSING_EXPORT_COMPLIANCE")])
    waiter = TestFlightInternalDistribution::BetaReadinessWaiter.new(
      client: client,
      logger: StringIO.new
    )

    error = assert_raises(TestFlightInternalDistribution::DistributionBlockedError) do
      waiter.wait(
        build_id: "build-id",
        marketing_version: "1.1.0",
        build_number: "89",
        timeout_seconds: 10,
        interval_seconds: 1
      )
    end
    assert_includes error.message, "MISSING_EXPORT_COMPLIANCE"
  end

  def test_dry_run_targets_one_exact_internal_group_without_writing
    client = FakeClient.new(
      groups: [group],
      group_build_responses: [[]],
      build_app: app,
      group_app: app
    )

    result = distributor(client).distribute(
      app: app,
      build: build,
      expected_app_id: APP_ID,
      expected_bundle_id: BUNDLE_ID,
      target_group_id: "group-id",
      target_group_name: "BuddyStudy Internal",
      apply: false
    )

    assert_equal :add, result
    assert_empty client.added
  end

  def test_apply_adds_only_the_exact_build_and_verifies_the_relationship
    client = FakeClient.new(
      groups: [group],
      group_build_responses: [[], ["build-id"]],
      build_app: app,
      group_app: app
    )

    result = distributor(client).distribute(
      app: app,
      build: build,
      expected_app_id: APP_ID,
      expected_bundle_id: BUNDLE_ID,
      target_group_id: "group-id",
      apply: true
    )

    assert_equal :added, result
    assert_equal [{ group_id: "group-id", build_id: "build-id" }], client.added
  end

  def test_apply_is_idempotent_when_the_relationship_already_exists
    client = FakeClient.new(
      groups: [group],
      group_build_responses: [["build-id"]],
      build_app: app,
      group_app: app
    )

    result = distributor(client).distribute(
      app: app,
      build: build,
      expected_app_id: APP_ID,
      expected_bundle_id: BUNDLE_ID,
      target_group_id: "group-id",
      apply: true
    )

    assert_equal :current, result
    assert_empty client.added
  end

  def test_selector_may_be_omitted_when_exactly_one_internal_group_exists
    client = FakeClient.new(
      groups: [group, group(id: "external-id", name: "External", internal: false)],
      group_build_responses: [[]],
      build_app: app,
      group_app: app
    )

    result = distributor(client).distribute(
      app: app,
      build: build,
      expected_app_id: APP_ID,
      expected_bundle_id: BUNDLE_ID,
      apply: false
    )

    assert_equal :add, result
    assert_empty client.added
  end

  def test_selector_omission_lists_internal_candidates_and_fails_when_ambiguous
    client = FakeClient.new(
      groups: [
        group(id: "first-id", name: "First Internal"),
        group(id: "second-id", name: "Second Internal"),
        group(id: "external-id", name: "External", internal: false)
      ],
      build_app: app,
      group_app: app
    )

    error = assert_raises(TestFlightInternalDistribution::IdentityError) do
      distributor(client).distribute(
        app: app,
        build: build,
        expected_app_id: APP_ID,
        expected_bundle_id: BUNDLE_ID,
        apply: false
      )
    end

    assert_includes error.message, "First Internal (first-id)"
    assert_includes error.message, "Second Internal (second-id)"
    refute_includes error.message, "External"
    assert_empty client.added
  end

  def test_refuses_an_external_group
    client = FakeClient.new(
      groups: [group(internal: false)],
      build_app: app,
      group_app: app
    )

    error = assert_raises(TestFlightInternalDistribution::IdentityError) do
      distributor(client).distribute(
        app: app,
        build: build,
        expected_app_id: APP_ID,
        expected_bundle_id: BUNDLE_ID,
        target_group_id: "group-id",
        apply: true
      )
    end
    assert_includes error.message, "external"
    assert_empty client.added
  end

  def test_refuses_a_group_or_build_from_another_app
    other_app = app(id: "other-app", bundle_id: "example.other")
    client = FakeClient.new(
      groups: [group],
      build_app: other_app,
      group_app: app
    )

    assert_raises(TestFlightInternalDistribution::IdentityError) do
      distributor(client).distribute(
        app: app,
        build: build,
        expected_app_id: APP_ID,
        expected_bundle_id: BUNDLE_ID,
        target_group_id: "group-id",
        apply: false
      )
    end

    client = FakeClient.new(
      groups: [group],
      build_app: app,
      group_app: other_app
    )
    assert_raises(TestFlightInternalDistribution::IdentityError) do
      distributor(client).distribute(
        app: app,
        build: build,
        expected_app_id: APP_ID,
        expected_bundle_id: BUNDLE_ID,
        target_group_id: "group-id",
        apply: false
      )
    end
  end

  def test_refuses_an_expired_build
    client = FakeClient.new(build_app: app)

    assert_raises(TestFlightInternalDistribution::DistributionBlockedError) do
      distributor(client).distribute(
        app: app,
        build: build(expired: true),
        expected_app_id: APP_ID,
        expected_bundle_id: BUNDLE_ID,
        target_group_id: "group-id",
        apply: false
      )
    end
  end

  def test_refuses_an_internal_only_build_as_an_app_review_candidate
    client = FakeClient.new(build_app: app)

    error = assert_raises(TestFlightInternalDistribution::IdentityError) do
      distributor(client).distribute(
        app: app,
        build: build(audience: "INTERNAL_ONLY"),
        expected_app_id: APP_ID,
        expected_bundle_id: BUNDLE_ID,
        target_group_id: "group-id",
        apply: false
      )
    end

    assert_includes error.message, "APP_STORE_ELIGIBLE"
  end

  def test_refuses_to_claim_distribution_to_an_empty_internal_group
    client = FakeClient.new(
      groups: [group],
      build_app: app,
      group_app: app,
      tester_ids: []
    )

    error = assert_raises(TestFlightInternalDistribution::DistributionBlockedError) do
      distributor(client).distribute(
        app: app,
        build: build,
        expected_app_id: APP_ID,
        expected_bundle_id: BUNDLE_ID,
        target_group_id: "group-id",
        apply: false
      )
    end

    assert_includes error.message, "no testers"
    assert_empty client.added
  end

  def test_client_posts_only_a_build_relationship_to_one_beta_group
    client = TestFlightInternalDistribution::AppStoreConnectClient.allocate
    client.instance_variable_set(:@allow_writes, true)
    calls = []
    client.define_singleton_method(:request) do |method, path, query: nil, body: nil|
      calls << { method: method, path: path, query: query, body: body }
      {}
    end

    client.add_build_to_beta_group(group_id: "group-id", build_id: "build-id")

    call = calls.fetch(0)
    assert_equal :post, call.fetch(:method)
    assert_equal "/v1/betaGroups/group-id/relationships/builds", call.fetch(:path)
    assert_equal [{ type: "builds", id: "build-id" }], call.dig(:body, :data)
    refute_includes call.fetch(:path), "betaTesters"
    refute_includes call.fetch(:path), "betaAppReview"
  end

  def test_client_blocks_relationship_writes_in_dry_run_mode
    client = TestFlightInternalDistribution::AppStoreConnectClient.allocate
    client.instance_variable_set(:@allow_writes, false)

    error = assert_raises(TestFlightInternalDistribution::ConfigurationError) do
      client.add_build_to_beta_group(group_id: "group-id", build_id: "build-id")
    end

    assert_includes error.message, "APP_STORE_APPLY=1"
  end
end
