# frozen_string_literal: true

module AppStoreBuildSelection
  module_function

  def editable_version(versions, editable_states:, explicit_id: nil, version_string: nil)
    requested = version_string.to_s.strip
    candidates = versions.select { |version| editable_states.include?(version.dig("attributes", "appStoreState")) }
    candidates = candidates.select { |version| version.fetch("id") == explicit_id } if explicit_id
    candidates = candidates.select { |version| version.dig("attributes", "versionString") == requested } unless requested.empty?
    raise ArgumentError, "No exact editable iOS App Store version found for #{requested.empty? ? explicit_id || 'request' : requested}" unless candidates.one?

    candidates.first
  end

  def matching_build(pairs, version_string: nil)
    requested = version_string.to_s.strip
    candidates = pairs.select do |build, prerelease|
      build.dig("attributes", "processingState") == "VALID" &&
        prerelease.dig("attributes", "platform") == "IOS" &&
        (requested.empty? || prerelease.dig("attributes", "version") == requested)
    end
    raise ArgumentError, "No unique valid iOS build matches the requested marketing version" unless candidates.one?

    candidates.first
  end
end
