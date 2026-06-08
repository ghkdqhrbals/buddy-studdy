from dataclasses import replace

from app.config import Settings, _load_aws_secret_values
from app.reaction_aggregator import QuestionReactionAggregator
from app.storage.repository import Database


class FakeRedis:
    def __init__(self):
        self.values: dict[str, int | str] = {}
        self.expirations: dict[str, int] = {}

    def incr(self, key: str) -> int:
        next_value = int(self.values.get(key, 0)) + 1
        self.values[key] = next_value
        return next_value

    def expire(self, key: str, ttl: int) -> None:
        self.expirations[key] = ttl

    def set(self, key: str, value, nx: bool = False, ex: int | None = None):
        if nx and key in self.values:
            return False
        self.values[key] = value
        if ex is not None:
            self.expirations[key] = ex
        return True

    def get(self, key: str):
        return self.values.get(key)

    def delete(self, key: str) -> None:
        self.values.pop(key, None)


class FakePublisher:
    def __init__(self):
        self.messages: list[dict] = []

    def publish(self, **kwargs) -> None:
        self.messages.append(kwargs)


def _settings(monkeypatch) -> Settings:
    _load_aws_secret_values.cache_clear()
    monkeypatch.setenv("ALLOW_SQLITE_FALLBACK", "true")
    settings = Settings.load()
    return replace(
        settings,
        view_counter_shard_count=3,
        view_counter_ttl_seconds=600,
        view_dedupe_ttl_seconds=600,
    )


def _public_question(db: Database) -> str:
    device_id, client_secret = db.register_device(
        apns_token="token-content-stream",
        platform="ios",
        apns_environment="production",
        language="en",
        timezone="UTC",
    )
    assert db.authenticate_device(device_id, client_secret)
    author = db.link_google_user_to_device(
        device_id=device_id,
        google_sub="content-stream-author",
        email="content-stream-author@example.com",
        display_name="Author",
    )
    assert author is not None
    question = db.create_question(
        device_id=device_id,
        user_id=int(author["id"]),
        topic="Swift",
        difficulty_level=7,
        question="What is actor isolation?",
        expected_answer_hint="Actors isolate mutable state.",
        is_public=True,
    )
    db.grade_record(
        device_id=device_id,
        record_id=question["id"],
        answer="It serializes access to actor state.",
        score=95,
        is_correct=True,
        feedback="ok",
        explanation="ok",
        user_id=int(author["id"]),
    )
    return question["id"]


def test_view_events_increment_sharded_redis_counters_and_flush_delta(monkeypatch, db: Database):
    question_id = _public_question(db)
    aggregator = QuestionReactionAggregator(settings=_settings(monkeypatch), database=db)
    fake_redis = FakeRedis()
    aggregator._redis = fake_redis

    aggregator._aggregate_view_event(question_id=question_id, event_id="event-a", minute_bucket=12345)
    aggregator._aggregate_view_event(question_id=question_id, event_id="event-b", minute_bucket=12345)

    detail = db.get_public_question(question_id)
    assert detail is not None
    assert detail["viewCount"] == 2
    counter_keys = [key for key in fake_redis.values if key.startswith(f"viewcounter:{question_id}:12345:")]
    assert len(counter_keys) >= 1
    assert fake_redis.values[f"viewcounter-applied:{question_id}:12345"] == 2


def test_logged_in_view_publish_is_deduped_per_user(monkeypatch, db: Database):
    question_id = _public_question(db)
    aggregator = QuestionReactionAggregator(settings=_settings(monkeypatch), database=db)
    fake_redis = FakeRedis()
    fake_publisher = FakePublisher()
    aggregator._redis = fake_redis
    aggregator._stream_publishers["view"] = fake_publisher

    aggregator.publish_question_viewed(question_id=question_id, user_id=10)
    aggregator.publish_question_viewed(question_id=question_id, user_id=10)
    aggregator.publish_question_viewed(question_id=question_id, user_id=11)

    assert len(fake_publisher.messages) == 2
    assert fake_redis.values[f"viewdedupe:{question_id}:user:10"] == "1"
    assert fake_redis.expirations[f"viewdedupe:{question_id}:user:10"] == 600


def test_view_flush_skips_when_lock_is_held(monkeypatch, db: Database):
    question_id = _public_question(db)
    aggregator = QuestionReactionAggregator(settings=_settings(monkeypatch), database=db)
    fake_redis = FakeRedis()
    fake_redis.values[f"viewcounter-flush-lock:{question_id}:12345"] = "1"
    aggregator._redis = fake_redis

    aggregator._aggregate_view_event(question_id=question_id, event_id="event-a", minute_bucket=12345)

    detail = db.get_public_question(question_id)
    assert detail is not None
    assert detail["viewCount"] == 0


def test_cluster_reader_patch_reads_one_stream_key_per_xreadgroup():
    from redisstream.redis_stream import RedisStreamReader

    class FakeCommands:
        def __init__(self):
            self.calls = []

        def xreadgroup(self, stream_keys, consumer_group, consumer_name, *, count, block_ms):
            self.calls.append(list(stream_keys))
            return []

    QuestionReactionAggregator._patch_redisstream_cluster_reader()
    commands = FakeCommands()
    reader = RedisStreamReader(commands, "group", "consumer")

    reader.poll_round_robin(["stream:0", "stream:1", "stream:2"], count=10, timeout_ms=1000)
    reader.poll_round_robin(["stream:0", "stream:1", "stream:2"], count=10, timeout_ms=1000)

    assert commands.calls == [["stream:0"], ["stream:1"]]
