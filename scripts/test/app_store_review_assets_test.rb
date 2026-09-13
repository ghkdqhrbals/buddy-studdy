require 'minitest/autorun'
require_relative '../sync-app-store-review-assets'

class AppStoreReviewAssetsTest < Minitest::Test
  class Client
    attr_reader :calls
    attr_accessor :detach_failure
    def initialize
      @calls = []
    end
    def request(method, path, **arguments)
      @calls << [method, path, arguments]
      raise 'detach failed' if method == :delete && detach_failure
      {}
    end
  end

  def make_sync(client, fail_upload: false)
    sync = AppStoreReviewAssets::Sync.new(client: client, apply: true)
    sync.instance_variable_set(:@target_items, [
      { subscription_id: 'plus', item_id: 'item', version_id: 'version' }
    ])
    sync.define_singleton_method(:replace_subscription_screenshot) do |*|
      raise 'upload failed' if fail_upload
    end
    sync
  end

  def test_replacement_reattaches_the_same_product_version
    client = Client.new
    sync = make_sync(client)
    sync.send(:replace_draft_subscription_screenshot, 'draft', {'appStoreConnectId' => 'plus'}, nil, 'image')
    assert_equal [:delete, :post], client.calls.map(&:first)
    relationships = client.calls.last[2].dig(:body, :data, :relationships)
    assert_equal 'draft', relationships.dig(:reviewSubmission, :data, :id)
    assert_equal 'version', relationships.dig(:subscriptionVersion, :data, :id)
  end

  def test_failed_media_upload_still_restores_review_item
    client = Client.new
    error = assert_raises(RuntimeError) do
      make_sync(client, fail_upload: true).send(:replace_draft_subscription_screenshot,
        'draft', {'appStoreConnectId' => 'plus'}, nil, 'image')
    end
    assert_equal 'upload failed', error.message
    assert_equal [:delete, :post], client.calls.map(&:first)
  end

  def test_failed_detach_does_not_duplicate_review_item
    client = Client.new
    client.detach_failure = true
    assert_raises(RuntimeError) do
      make_sync(client).send(:replace_draft_subscription_screenshot,
        'draft', {'appStoreConnectId' => 'plus'}, nil, 'image')
    end
    assert_equal [:delete], client.calls.map(&:first)
  end
end
