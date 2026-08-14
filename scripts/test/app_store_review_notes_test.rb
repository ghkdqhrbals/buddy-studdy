# frozen_string_literal: true

require "minitest/autorun"
require_relative "../lib/app_store_review_notes"

class AppStoreReviewNotesTest < Minitest::Test
  COMPLETE_TEMPLATE = <<~TEXT
    Test ID: {{DEMO_ACCOUNT_NAME}}
    Test password: {{DEMO_ACCOUNT_PASSWORD}}
    - Video: BuddyStudy-review.mov
    - Physical models, exact OS versions, selected build: iPhone 16 Pro, iOS 26.6; iPad Pro 13-inch (M4), iPadOS 26.6; build 89
  TEXT

  PRE_RECORDING_TEMPLATE = <<~TEXT
    Test ID: {{DEMO_ACCOUNT_NAME}}
    Test password: {{DEMO_ACCOUNT_PASSWORD}}
    - Video: {{ATTACHED_VIDEO_FILENAME}}
    - Physical models, exact OS versions, selected build: {{ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS}}
  TEXT

  COMPLETE_VIDEO = "BuddyStudy-review.mov"
  COMPLETE_DEVICES = "iPhone 16 Pro, iOS 26.6; iPad Pro 13-inch (M4), iPadOS 26.6; build 89"

  def resolution_reply(video:, devices:)
    <<~TEXT
      Hello App Review,

      1. COMPLETE PHYSICAL-DEVICE VIDEO
      Attached video: #{video}
      Walkthrough details.

      2. TESTED DEVICES AND OS VERSIONS
      #{devices}
      Test details.

      3. FUNCTIONS
      Details.

      4. SETUP
      Details.

      5. SERVICES
      Details.

      6. REGIONS
      Details.

      7. REGULATED CONTENT
      Details.

      8. IN-APP PURCHASES
      Details.
    TEXT
  end

  def guide_for(reply)
    "Before\n\n```text\n#{reply.strip}\n```\n\nAfter\n"
  end

  def test_local_validation_reports_pre_recording_template
    status = AppStoreReviewNotes.validate_template!(
      PRE_RECORDING_TEMPLATE,
      allow_recording_placeholders: true
    )

    assert_equal :pre_recording, status
  end

  def test_sync_validation_rejects_pre_recording_template
    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_template!(
        PRE_RECORDING_TEMPLATE,
        allow_recording_placeholders: false
      )
    end

    assert_includes error.message, "pre-recording template"
    assert_includes error.message, "{{ATTACHED_VIDEO_FILENAME}}"
    assert_includes error.message, "{{ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS}}"
  end

  def test_sync_validation_accepts_completed_template
    status = AppStoreReviewNotes.validate_template!(
      COMPLETE_TEMPLATE,
      allow_recording_placeholders: false
    )

    assert_equal :complete_candidate, status
  end

  def test_recording_placeholders_must_be_completed_together
    partial = PRE_RECORDING_TEMPLATE.gsub(
      "{{ATTACHED_VIDEO_FILENAME}}",
      "BuddyStudy-review.mov"
    )

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_template!(
        partial,
        allow_recording_placeholders: true
      )
    end

    assert_includes error.message, "completed together"
  end

  def test_demo_credentials_must_remain_placeholders_in_source
    leaked = COMPLETE_TEMPLATE.gsub("{{DEMO_ACCOUNT_PASSWORD}}", "not-a-placeholder")

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_template!(
        leaked,
        allow_recording_placeholders: false
      )
    end

    assert_includes error.message, "must retain App Store Connect demo-account placeholders"
  end

  def test_rendered_notes_reject_any_unresolved_placeholder
    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_rendered!("Video: {{ATTACHED_VIDEO_FILENAME}}")
    end

    assert_includes error.message, "Refusing to upload"
  end

  def test_unknown_and_malformed_placeholders_are_rejected
    unknown = COMPLETE_TEMPLATE + "Unknown: {{SOMETHING_ELSE}}\n"
    malformed = COMPLETE_TEMPLATE + "Malformed: {{replace me}}\n"

    assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_template!(unknown, allow_recording_placeholders: false)
    end
    assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_template!(malformed, allow_recording_placeholders: false)
    end
  end

  def test_resolution_reply_status_rejects_partial_and_malformed_placeholders
    pre_recording_reply = <<~TEXT
      Video: {{ATTACHED_VIDEO_FILENAME}}
      Devices: {{ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS}}
    TEXT
    complete_reply = <<~TEXT
      Video: BuddyStudy-review.mov
      Devices: iPhone 16 Pro, iOS 26.6, build 89
    TEXT

    assert_equal :pre_recording, AppStoreReviewNotes.recording_status!(pre_recording_reply)
    assert_equal :complete_candidate, AppStoreReviewNotes.recording_status!(complete_reply)

    assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.recording_status!("Video: {{ATTACHED_VIDEO_FILENAME}}")
    end
    assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.recording_status!("Video: {{video filename}}")
    end
  end

  def test_byte_limit_counts_non_ascii_as_utf8_bytes
    assert_equal 4_000, AppStoreReviewNotes.validate_byte_limit!("a" * 4_000)

    over_limit = "가" * 1_334
    assert_equal 4_002, over_limit.bytesize
    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_byte_limit!(over_limit)
    end
    assert_includes error.message, "4,000 UTF-8 bytes"
  end

  def test_review_package_allows_pre_recording_locally_but_blocks_sync
    reply = resolution_reply(
      video: "{{ATTACHED_VIDEO_FILENAME}}",
      devices: "{{ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS}}"
    )
    guide = guide_for(reply)

    local = AppStoreReviewNotes.validate_review_package!(
      notes: PRE_RECORDING_TEMPLATE,
      reply: reply,
      guide: guide,
      allow_recording_placeholders: true
    )
    assert_equal :pre_recording, local.fetch(:status)

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: PRE_RECORDING_TEMPLATE,
        reply: reply,
        guide: guide,
        allow_recording_placeholders: false
      )
    end
    assert_includes error.message, "pre-recording template"
  end

  def test_review_package_accepts_matching_complete_evidence
    reply = resolution_reply(video: COMPLETE_VIDEO, devices: COMPLETE_DEVICES)
    result = AppStoreReviewNotes.validate_review_package!(
      notes: COMPLETE_TEMPLATE,
      reply: reply,
      guide: guide_for(reply),
      allow_recording_placeholders: false
    )

    assert_equal :complete_candidate, result.fetch(:status)
    assert_equal COMPLETE_TEMPLATE.bytesize, result.fetch(:notes_bytes)
    assert_equal reply.bytesize, result.fetch(:reply_bytes)
  end

  def test_deleting_evidence_placeholders_without_values_is_rejected
    blank_notes = PRE_RECORDING_TEMPLATE
      .gsub("{{ATTACHED_VIDEO_FILENAME}}", "")
      .gsub("{{ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS}}", "")
    blank_reply = resolution_reply(video: "", devices: "")

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: blank_notes,
        reply: blank_reply,
        guide: guide_for(blank_reply),
        allow_recording_placeholders: true
      )
    end
    assert_includes error.message, "Review Notes video must not be empty"
  end

  def test_blank_resolution_device_evidence_is_rejected
    reply = resolution_reply(video: COMPLETE_VIDEO, devices: "")

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: COMPLETE_TEMPLATE,
        reply: reply,
        guide: guide_for(reply),
        allow_recording_placeholders: false
      )
    end
    assert_includes error.message, "Resolution reply device/OS/build must not be empty"
  end

  def test_blank_review_notes_device_evidence_is_rejected
    notes = COMPLETE_TEMPLATE.sub(COMPLETE_DEVICES, "")
    reply = resolution_reply(video: COMPLETE_VIDEO, devices: COMPLETE_DEVICES)

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: notes,
        reply: reply,
        guide: guide_for(reply),
        allow_recording_placeholders: false
      )
    end
    assert_includes error.message, "Review Notes device/OS/build must not be empty"
  end

  def test_blank_resolution_video_evidence_is_rejected
    reply = resolution_reply(video: "", devices: COMPLETE_DEVICES)

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: COMPLETE_TEMPLATE,
        reply: reply,
        guide: guide_for(reply),
        allow_recording_placeholders: false
      )
    end
    assert_includes error.message, "Resolution reply attached video must not be empty"
  end

  def test_completed_device_evidence_rejects_iphone_only_verification
    iphone_only = "iPhone 16 Pro, iOS 26.6, build 89"
    notes = COMPLETE_TEMPLATE.sub(COMPLETE_DEVICES, iphone_only)
    reply = resolution_reply(video: COMPLETE_VIDEO, devices: iphone_only)

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: notes,
        reply: reply,
        guide: guide_for(reply),
        allow_recording_placeholders: false
      )
    end
    assert_includes error.message, "physical iPad model"
    assert_includes error.message, "exact iPadOS version"
  end

  def test_review_package_rejects_guide_mismatch_and_missing_section
    reply = resolution_reply(video: COMPLETE_VIDEO, devices: COMPLETE_DEVICES)
    mismatched_guide = guide_for(reply.sub("Details.", "Different."))
    mismatch = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: COMPLETE_TEMPLATE,
        reply: reply,
        guide: mismatched_guide,
        allow_recording_placeholders: false
      )
    end
    assert_includes mismatch.message, "resubmission guide differ"

    missing_section = reply.sub(/^8\. IN-APP PURCHASES\nDetails\.\n/m, "")
    section_error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: COMPLETE_TEMPLATE,
        reply: missing_section,
        guide: guide_for(missing_section),
        allow_recording_placeholders: false
      )
    end
    assert_includes section_error.message, "exactly sections 1 through 8"
  end

  def test_review_package_requires_exact_evidence_equality
    reply = resolution_reply(video: "different.mov", devices: COMPLETE_DEVICES)

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: COMPLETE_TEMPLATE,
        reply: reply,
        guide: guide_for(reply),
        allow_recording_placeholders: false
      )
    end
    assert_includes error.message, "filename differs"
  end

  def test_review_package_applies_utf8_byte_limit
    oversized_notes = COMPLETE_TEMPLATE + ("가" * 1_334)
    reply = resolution_reply(video: COMPLETE_VIDEO, devices: COMPLETE_DEVICES)

    error = assert_raises(AppStoreReviewNotes::ValidationError) do
      AppStoreReviewNotes.validate_review_package!(
        notes: oversized_notes,
        reply: reply,
        guide: guide_for(reply),
        allow_recording_placeholders: false
      )
    end
    assert_includes error.message, "4,000 UTF-8 bytes"
  end
end
