# frozen_string_literal: true

module AppStoreReviewNotes
  class ValidationError < StandardError; end

  MAX_BYTES = 4_000
  DEMO_ACCOUNT_PLACEHOLDERS = %w[
    DEMO_ACCOUNT_NAME
    DEMO_ACCOUNT_PASSWORD
  ].freeze
  RECORDING_PLACEHOLDERS = %w[
    ATTACHED_VIDEO_FILENAME
    ACTUALLY_TESTED_DEVICE_MODELS_AND_IOS_VERSIONS
  ].freeze
  ALLOWED_PLACEHOLDERS = (
    DEMO_ACCOUNT_PLACEHOLDERS + RECORDING_PLACEHOLDERS
  ).freeze
  PLACEHOLDER_PATTERN = /\{\{([^{}\r\n]*)\}\}/.freeze
  PLACEHOLDER_NAME_PATTERN = /\A[A-Z0-9_]+\z/.freeze
  NOTES_VIDEO_LINE_PATTERN = /^- Video:[ \t]*([^\r\n]*)\r?$/.freeze
  NOTES_DEVICES_LINE_PATTERN =
    /^- Physical models, exact OS versions, selected build:[ \t]*([^\r\n]*)\r?$/.freeze
  REPLY_VIDEO_LINE_PATTERN =
    /^1\. COMPLETE PHYSICAL-DEVICE VIDEO\r?\nAttached video:[ \t]*([^\r\n]*)\r?$/.freeze
  REPLY_DEVICES_LINE_PATTERN =
    /^2\. TESTED DEVICES AND OS VERSIONS\r?\n([^\r\n]*)\r?$/.freeze
  INCOMPLETE_VALUE_PATTERN = /\A(?:tbd|todo|pending|none|n\/a|-+)\z/i.freeze
  EXACT_IOS_PATTERN = /\biOS\s+\d+(?:\.\d+)+\b/i.freeze
  EXACT_IPADOS_PATTERN = /\biPadOS\s+\d+(?:\.\d+)+\b/i.freeze
  EXACT_BUILD_PATTERN = /\bbuild\s+\d+\b/i.freeze
  IPHONE_PATTERN = /\biPhone\b/i.freeze
  IPAD_PATTERN = /\biPad\b/i.freeze

  module_function

  def validate_template!(text, allow_recording_placeholders:)
    raise ValidationError, "Review notes are empty" if text.strip.empty?

    names = extract_placeholder_names!(text)
    unknown = names.uniq - ALLOWED_PLACEHOLDERS
    unless unknown.empty?
      raise ValidationError,
            "Review notes contain unexpected placeholders: #{format_placeholders(unknown)}"
    end

    duplicates = names.tally.select { |_name, count| count != 1 }.keys
    unless duplicates.empty?
      raise ValidationError,
            "Review-note placeholders must appear exactly once: #{format_placeholders(duplicates)}"
    end

    missing_demo_placeholders = DEMO_ACCOUNT_PLACEHOLDERS - names
    unless missing_demo_placeholders.empty?
      raise ValidationError,
            "Review notes must retain App Store Connect demo-account placeholders: " \
            "#{format_placeholders(missing_demo_placeholders)}"
    end

    recording_placeholders = names & RECORDING_PLACEHOLDERS
    if recording_placeholders.any? && recording_placeholders.sort != RECORDING_PLACEHOLDERS.sort
      missing = RECORDING_PLACEHOLDERS - recording_placeholders
      raise ValidationError,
            "Recording placeholders must be completed together; missing " \
            "#{format_placeholders(missing)}"
    end

    if recording_placeholders.any? && !allow_recording_placeholders
      raise ValidationError,
            "Review notes are still a pre-recording template. Replace " \
            "#{format_placeholders(RECORDING_PLACEHOLDERS)} with the attached video filename " \
            "and the physical device/OS/build list before App Store Connect sync"
    end

    recording_placeholders.empty? ? :complete_candidate : :pre_recording
  end

  def validate_rendered!(text)
    raise ValidationError, "Review notes are empty" if text.strip.empty?

    names = extract_placeholder_names!(text)
    return if names.empty?

    raise ValidationError,
          "Refusing to upload review notes with unresolved placeholders: " \
          "#{format_placeholders(names.uniq)}"
  end

  def validate_byte_limit!(text, label: "Review notes")
    byte_count = text.bytesize
    return byte_count if byte_count <= MAX_BYTES

    raise ValidationError, "#{label} exceed 4,000 UTF-8 bytes"
  end

  def recording_status!(text)
    raise ValidationError, "Review text is empty" if text.strip.empty?

    names = extract_placeholder_names!(text)
    unknown = names.uniq - RECORDING_PLACEHOLDERS
    unless unknown.empty?
      raise ValidationError,
            "Review text contains unexpected placeholders: #{format_placeholders(unknown)}"
    end

    duplicates = names.tally.select { |_name, count| count != 1 }.keys
    unless duplicates.empty?
      raise ValidationError,
            "Recording placeholders must appear exactly once: #{format_placeholders(duplicates)}"
    end

    if names.any? && names.sort != RECORDING_PLACEHOLDERS.sort
      missing = RECORDING_PLACEHOLDERS - names
      raise ValidationError,
            "Recording placeholders must be completed together; missing " \
            "#{format_placeholders(missing)}"
    end

    names.empty? ? :complete_candidate : :pre_recording
  end

  def validate_review_package!(notes:, reply:, guide:, allow_recording_placeholders:)
    notes_status = validate_template!(notes, allow_recording_placeholders: true)
    reply_status = recording_status!(reply)
    unless notes_status == reply_status
      raise ValidationError,
            "Review notes and Resolution Center reply have inconsistent recording status"
    end

    notes_bytes = validate_byte_limit!(notes)
    reply_bytes = validate_byte_limit!(reply, label: "Resolution Center reply")
    validate_resolution_sections!(reply)
    validate_embedded_reply!(reply, guide)
    validate_evidence!(notes, reply, notes_status)

    if notes_status == :pre_recording && !allow_recording_placeholders
      raise ValidationError,
            "Review content is still a pre-recording template. Replace " \
            "#{format_placeholders(RECORDING_PLACEHOLDERS)} with the attached video filename " \
            "and the physical device/OS/build list before App Store Connect sync"
    end

    {
      status: notes_status,
      notes_bytes: notes_bytes,
      reply_bytes: reply_bytes
    }
  end

  def validate_resolution_sections!(reply)
    sections = reply.scan(/^(\d+)\. /).flatten
    return if sections == %w[1 2 3 4 5 6 7 8]

    raise ValidationError,
          "Resolution Center reply must contain exactly sections 1 through 8"
  end

  def validate_embedded_reply!(reply, guide)
    embedded_blocks = guide.scan(/```text\r?\n(.*?)\r?\n```/m).flatten
    unless embedded_blocks.length == 1
      raise ValidationError,
            "Resubmission guide must contain exactly one canonical text reply block"
    end
    return if embedded_blocks.first.strip == reply.strip

    raise ValidationError, "Resolution Center reply and resubmission guide differ"
  end

  def validate_evidence!(notes, reply, status)
    notes_video = extract_line_value!(notes, NOTES_VIDEO_LINE_PATTERN, "Review Notes video")
    notes_devices = extract_line_value!(notes, NOTES_DEVICES_LINE_PATTERN, "Review Notes device/OS/build")
    reply_video = extract_line_value!(reply, REPLY_VIDEO_LINE_PATTERN, "Resolution reply attached video")
    reply_devices = extract_line_value!(reply, REPLY_DEVICES_LINE_PATTERN, "Resolution reply device/OS/build")

    if status == :pre_recording
      require_placeholder_value!(notes_video, RECORDING_PLACEHOLDERS.fetch(0), "Review Notes video")
      require_placeholder_value!(reply_video, RECORDING_PLACEHOLDERS.fetch(0), "Resolution reply video")
      require_placeholder_value!(notes_devices, RECORDING_PLACEHOLDERS.fetch(1), "Review Notes devices")
      require_placeholder_value!(reply_devices, RECORDING_PLACEHOLDERS.fetch(1), "Resolution reply devices")
      return
    end

    unless notes_video == reply_video
      raise ValidationError, "Attached video filename differs between Review Notes and Resolution reply"
    end
    unless notes_devices == reply_devices
      raise ValidationError, "Device/OS/build evidence differs between Review Notes and Resolution reply"
    end

    validate_completed_video!(notes_video)
    validate_completed_devices!(notes_devices)
  end
  private_class_method :validate_evidence!

  def extract_line_value!(text, pattern, label)
    matches = text.scan(pattern).flatten
    unless matches.length == 1
      raise ValidationError, "#{label} must appear exactly once in its canonical location"
    end

    value = matches.first.strip
    raise ValidationError, "#{label} must not be empty" if value.empty?

    value
  end
  private_class_method :extract_line_value!

  def require_placeholder_value!(value, placeholder_name, label)
    expected = "{{#{placeholder_name}}}"
    return if value == expected

    raise ValidationError, "#{label} must use #{expected} until evidence is complete"
  end
  private_class_method :require_placeholder_value!

  def validate_completed_video!(value)
    if INCOMPLETE_VALUE_PATTERN.match?(value) || !value.match?(/[[:alnum:]]/)
      raise ValidationError, "Attached video filename is not complete evidence"
    end
  end
  private_class_method :validate_completed_video!

  def validate_completed_devices!(value)
    missing = []
    missing << "physical iPhone model" unless IPHONE_PATTERN.match?(value)
    missing << "exact iOS version" unless EXACT_IOS_PATTERN.match?(value)
    missing << "physical iPad model" unless IPAD_PATTERN.match?(value)
    missing << "exact iPadOS version" unless EXACT_IPADOS_PATTERN.match?(value)
    missing << "build number" unless EXACT_BUILD_PATTERN.match?(value)
    return if missing.empty? && !INCOMPLETE_VALUE_PATTERN.match?(value)

    raise ValidationError,
          "Device evidence must include a #{missing.empty? ? "meaningful value" : missing.join(", ")}"
  end
  private_class_method :validate_completed_devices!

  def extract_placeholder_names!(text)
    raw_names = text.scan(PLACEHOLDER_PATTERN).flatten
    invalid = raw_names.reject { |name| PLACEHOLDER_NAME_PATTERN.match?(name) }
    unless invalid.empty?
      rendered = invalid.map { |name| "{{#{name}}}" }.uniq.sort.join(", ")
      raise ValidationError, "Review notes contain malformed placeholders: #{rendered}"
    end

    residue = text.gsub(PLACEHOLDER_PATTERN, "")
    if residue.include?("{{") || residue.include?("}}")
      raise ValidationError, "Review notes contain an unmatched placeholder delimiter"
    end

    raw_names
  end
  private_class_method :extract_placeholder_names!

  def format_placeholders(names)
    names.uniq.sort.map { |name| "{{#{name}}}" }.join(", ")
  end
  private_class_method :format_placeholders
end
