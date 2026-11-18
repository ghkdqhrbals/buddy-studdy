# frozen_string_literal: true

require "minitest/autorun"
require_relative "../lib/app_store_build_selection"

class AppStoreBuildSelectionTest < Minitest::Test
  def version(id, number, state = "PREPARE_FOR_SUBMISSION")
    { "id" => id, "attributes" => { "versionString" => number, "appStoreState" => state } }
  end

  def pair(number, platform = "IOS")
    [{ "attributes" => { "processingState" => "VALID" } },
     { "attributes" => { "version" => number, "platform" => platform } }]
  end

  def test_pins_editable_version_even_when_another_version_is_first
    selected = AppStoreBuildSelection.editable_version([version("other", "2.0"), version("target", "1.3.1")],
      editable_states: ["PREPARE_FOR_SUBMISSION"], version_string: "1.3.1")
    assert_equal "target", selected.fetch("id")
  end

  def test_explicit_id_cannot_select_a_different_or_published_version
    assert_raises(ArgumentError) do
      AppStoreBuildSelection.editable_version([version("other", "2.0")],
        editable_states: ["PREPARE_FOR_SUBMISSION"], explicit_id: "other", version_string: "1.3.1")
    end
    assert_raises(ArgumentError) do
      AppStoreBuildSelection.editable_version([version("live", "1.3.1", "READY_FOR_SALE")],
        editable_states: ["PREPARE_FOR_SUBMISSION"], version_string: "1.3.1")
    end
  end

  def test_repeated_build_number_must_match_exact_ios_marketing_version
    selected = pair("1.3.1")
    assert_equal selected, AppStoreBuildSelection.matching_build([pair("2.0"), pair("1.3.1", "MAC_OS"), selected], version_string: "1.3.1")
    assert_raises(ArgumentError) { AppStoreBuildSelection.matching_build([pair("2.0")], version_string: "1.3.1") }
    assert_raises(ArgumentError) { AppStoreBuildSelection.matching_build([selected, selected], version_string: "1.3.1") }
  end
end
