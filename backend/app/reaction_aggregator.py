from __future__ import annotations

import asyncio
from datetime import UTC, datetime
import hashlib
import logging
import uuid

from .apns import APNsClient, APNsQuestion
from .config import Settings
from .db import Database


logger = logging.getLogger(__name__)


class QuestionReactionAggregator:
    def __init__(self, settings: Settings, database: Database):
        self.settings = settings
        self.database = database
        self._task: asyncio.Task | None = None
        self._stopping = asyncio.Event()
        self._stream_app = None
        self._stream_publishers: dict[str, object] = {}
        self._redis = None
        self._stream_mode = False
        self.apns: APNsClient | None = None

    @property
    def uses_stream(self) -> bool:
        return self._stream_mode

    @property
    def poll_seconds(self) -> int:
        return max(1, self.settings.reaction_aggregation_poll_seconds)

    @property
    def batch_size(self) -> int:
        return max(1, self.settings.reaction_aggregation_batch_size)

    def start(self) -> None:
        if self._try_start_stream_mode():
            return
        if self._task is None:
            self._task = asyncio.create_task(self._run_loop())

    async def stop(self) -> None:
        self._stopping.set()
        if self._stream_app is not None:
            await asyncio.to_thread(self._stream_app.stop)
            self._stream_app = None
            self._stream_publishers = {}
            self._redis = None
            self._stream_mode = False
        if self._task is not None:
            await self._task

    async def run_once(self) -> int:
        if self._stream_mode:
            return await asyncio.to_thread(
                self.database.reconcile_question_stats,
                None,
                self.batch_size,
            )
        return await asyncio.to_thread(
            self.database.aggregate_question_reaction_events,
            self.batch_size,
        )

    async def reconcile_once(self) -> int:
        return await asyncio.to_thread(self.database.reconcile_question_stats)

    def publish_question_changed(self, question_id: str | int, event_type: str, user_id: int | None = None) -> None:
        normalized_question_id = str(question_id).strip()
        if not normalized_question_id:
            return
        publisher = self._stream_publishers.get("action")
        if publisher is None:
            return
        fields = {
            "questionId": normalized_question_id,
            "eventType": event_type,
            "eventId": str(uuid.uuid4()),
        }
        if user_id is not None:
            fields["userId"] = str(user_id)
        publisher.publish(
            partition_key=normalized_question_id,
            fields=fields,
            max_len=self.settings.reaction_stream_xadd_max_len,
        )

    def publish_question_viewed(self, question_id: str | int, user_id: int | None = None) -> None:
        normalized_question_id = str(question_id).strip()
        if not normalized_question_id:
            return
        publisher = self._stream_publishers.get("view")
        if publisher is None:
            self.database.increment_question_view_count(normalized_question_id, 1)
            return
        event_id = str(uuid.uuid4())
        fields = {
            "questionId": normalized_question_id,
            "eventType": "CONTENT_VIEWED",
            "eventId": event_id,
            "minuteBucket": str(self._current_minute_bucket()),
        }
        if user_id is not None:
            fields["userId"] = str(user_id)
        publisher.publish(
            partition_key=normalized_question_id,
            fields=fields,
            max_len=self.settings.reaction_stream_xadd_max_len,
        )

    def publish_push_question(self, fields: dict[str, str | int | None]) -> bool:
        publisher = self._stream_publishers.get("push")
        if publisher is None:
            return False
        normalized_fields = {
            key: str(value)
            for key, value in fields.items()
            if value is not None and str(value).strip()
        }
        normalized_fields["eventId"] = str(uuid.uuid4())
        normalized_fields["eventType"] = "QUESTION_PUSH_REQUESTED"
        partition_key = normalized_fields.get("topic") or normalized_fields.get("recordId") or normalized_fields["eventId"]
        publisher.publish(
            partition_key=partition_key,
            fields=normalized_fields,
            max_len=self.settings.reaction_stream_xadd_max_len,
        )
        return True

    async def _run_loop(self) -> None:
        cycles_since_reconcile = 0
        while not self._stopping.is_set():
            try:
                processed = await self.run_once()
                cycles_since_reconcile += 1
                if processed > 0:
                    logger.info("aggregated question reaction events count=%s", processed)
                if cycles_since_reconcile >= max(1, self.settings.reaction_reconcile_every_cycles):
                    reconciled = await self.reconcile_once()
                    cycles_since_reconcile = 0
                    if reconciled > 0:
                        logger.info("reconciled question stats count=%s", reconciled)
            except Exception:
                logger.exception("question reaction aggregation iteration failed")

            try:
                await asyncio.wait_for(self._stopping.wait(), timeout=self.poll_seconds)
            except TimeoutError:
                pass

    def _try_start_stream_mode(self) -> bool:
        if not self.settings.reaction_stream_enabled:
            return False
        if not self.settings.reaction_stream_coordinator_base_url or not self.settings.redis_host:
            return False

        try:
            from redisstream import RedisStreamCoordinator
            from redisstream.client import CoordinatorClient

            if self.settings.redis_cluster:
                self._patch_redisstream_cluster_reader()

            coordinator_client = CoordinatorClient(
                self.settings.reaction_stream_coordinator_base_url,
                bearer_token=self.settings.reaction_stream_coordinator_token,
            )
            if (
                not self.settings.reaction_stream_coordinator_token
                and self.settings.reaction_stream_coordinator_username
                and self.settings.reaction_stream_coordinator_password
            ):
                coordinator_client.login(
                    self.settings.reaction_stream_coordinator_username,
                    self.settings.reaction_stream_coordinator_password,
                )

            redis_client = self._redis_client()
            stream_app = RedisStreamCoordinator(
                coordinator_base_url=self.settings.reaction_stream_coordinator_base_url,
                redis_client=redis_client,
                coordinator_client=coordinator_client,
            )

            @stream_app.stream_listener(
                stream_prefix=self.settings.push_stream_prefix,
                group_id=self.settings.push_stream_group_id,
                concurrency=self.settings.push_stream_concurrency,
                poll_batch_size=self.batch_size,
                poll_timeout=float(self.poll_seconds),
            )
            def handle_push(message):
                fields = message.fields
                record_id = str(fields.get("recordId") or "").strip()
                device_id = str(fields.get("deviceId") or "").strip()
                topic = str(fields.get("topic") or "").strip()
                try:
                    payload = APNsQuestion(
                        record_id=record_id,
                        created_at=str(fields.get("createdAt") or ""),
                        device_token=str(fields.get("deviceToken") or ""),
                        environment=str(fields.get("environment") or "production"),
                        question=str(fields.get("question") or ""),
                        expected_answer_hint=str(fields.get("expectedAnswerHint") or "") or None,
                        topic=topic,
                        difficulty_level=int(str(fields.get("difficultyLevel") or "1")),
                        language=str(fields.get("language") or "ko"),
                        sound=str(fields.get("sound") or "") or None,
                    )
                    if self.apns is None:
                        self.apns = APNsClient(self.settings)
                    asyncio.run(self.apns.send_question(payload))
                    self.database.mark_scheduled_delivery(
                        device_id=device_id,
                        record_id=record_id,
                        interval_minutes=int(str(fields.get("intervalMinutes") or "1")),
                        user_id=self._optional_int(fields.get("userId")),
                        topic=topic,
                    )
                    message.ack()
                except Exception as error:
                    if record_id and device_id:
                        self.database.mark_scheduled_question_created_without_delivery(
                            device_id=device_id,
                            record_id=record_id,
                            interval_minutes=max(1, self._optional_int(fields.get("intervalMinutes")) or 1),
                            error=str(error),
                            user_id=self._optional_int(fields.get("userId")),
                            topic=topic,
                        )
                    logger.exception("question push stream message failed record_id=%s", record_id)

            @stream_app.stream_listener(
                stream_prefix=self.settings.view_stream_prefix,
                group_id=self.settings.view_stream_group_id,
                concurrency=self.settings.view_stream_concurrency,
                poll_batch_size=self.batch_size,
                poll_timeout=float(self.poll_seconds),
            )
            def handle_view(message):
                question_id = str(message.fields.get("questionId") or "").strip()
                try:
                    if question_id:
                        self._aggregate_view_event(
                            question_id=question_id,
                            event_id=str(message.fields.get("eventId") or message.record_id),
                            minute_bucket=int(str(message.fields.get("minuteBucket") or self._current_minute_bucket())),
                        )
                    message.ack()
                except Exception:
                    logger.exception("question view stream message failed question_id=%s", question_id)

            @stream_app.stream_listener(
                stream_prefix=self.settings.action_stream_prefix,
                group_id=self.settings.action_stream_group_id,
                concurrency=self.settings.action_stream_concurrency,
                poll_batch_size=self.batch_size,
                poll_timeout=float(self.poll_seconds),
            )
            def handle_action(message):
                question_id = str(message.fields.get("questionId") or "").strip()
                try:
                    if question_id:
                        self.database.reconcile_question_stats(question_ids=[int(question_id)])
                    message.ack()
                except Exception:
                    logger.exception("question action stream message failed question_id=%s", question_id)

            stream_app.start()
            self._stream_app = stream_app
            self._redis = redis_client
            self._stream_publishers = {
                "push": stream_app.publisher(
                    self.settings.push_stream_prefix,
                    self.settings.push_stream_group_id,
                    xadd_max_len=self.settings.reaction_stream_xadd_max_len,
                ),
                "view": stream_app.publisher(
                    self.settings.view_stream_prefix,
                    self.settings.view_stream_group_id,
                    xadd_max_len=self.settings.reaction_stream_xadd_max_len,
                ),
                "action": stream_app.publisher(
                    self.settings.action_stream_prefix,
                    self.settings.action_stream_group_id,
                    xadd_max_len=self.settings.reaction_stream_xadd_max_len,
                ),
            }
            for publisher in self._stream_publishers.values():
                publisher.routing_cache.metadata(force_refresh=True)
            self._stream_mode = True
            logger.info(
                "started BuddyStuddy Redis Stream coordinator streams push=%s view=%s action=%s",
                self.settings.push_stream_prefix,
                self.settings.view_stream_prefix,
                self.settings.action_stream_prefix,
            )
            return True
        except Exception:
            logger.exception("failed to start question reaction Redis Stream coordinator; falling back to DB events")
            self._stream_app = None
            self._stream_publishers = {}
            self._redis = None
            self._stream_mode = False
            return False

    def _aggregate_view_event(self, question_id: str, event_id: str, minute_bucket: int) -> None:
        if self._redis is None:
            self.database.increment_question_view_count(question_id, 1)
            return

        shard_count = max(1, self.settings.view_counter_shard_count)
        shard_no = self._stable_shard(event_id, shard_count)
        ttl = max(60, self.settings.view_counter_ttl_seconds)
        counter_key = f"viewcounter:{question_id}:{minute_bucket}:{shard_no}"
        applied_key = f"viewcounter-applied:{question_id}:{minute_bucket}"
        lock_key = f"viewcounter-flush-lock:{question_id}:{minute_bucket}"

        self._redis.incr(counter_key)
        self._redis.expire(counter_key, ttl)
        lock_acquired = bool(self._redis.set(lock_key, "1", nx=True, ex=10))
        if not lock_acquired:
            return

        try:
            total = 0
            for index in range(shard_count):
                raw_value = self._redis.get(f"viewcounter:{question_id}:{minute_bucket}:{index}")
                total += int(raw_value or 0)
            applied = int(self._redis.get(applied_key) or 0)
            delta = max(0, total - applied)
            if delta > 0:
                self.database.increment_question_view_count(question_id, delta)
                self._redis.set(applied_key, total, ex=ttl)
        finally:
            self._redis.delete(lock_key)

    @staticmethod
    def _current_minute_bucket() -> int:
        return int(datetime.now(tz=UTC).timestamp() // 60)

    @staticmethod
    def _stable_shard(value: str, shard_count: int) -> int:
        digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
        return int(digest[:16], 16) % max(1, shard_count)

    @staticmethod
    def _optional_int(value) -> int | None:
        try:
            if value is None or str(value).strip() == "":
                return None
            return int(str(value))
        except (TypeError, ValueError):
            return None

    def _redis_client(self):
        import redis

        kwargs = {
            "host": self.settings.redis_host,
            "port": self.settings.redis_port,
            "password": self.settings.redis_password,
            "ssl": self.settings.redis_ssl,
            "socket_connect_timeout": 5,
            "socket_timeout": 5,
            "decode_responses": True,
        }
        if self.settings.redis_cluster:
            from redis.cluster import RedisCluster

            return RedisCluster(**kwargs)

        kwargs["db"] = self.settings.redis_db
        return redis.Redis(**kwargs)

    @staticmethod
    def _patch_redisstream_cluster_reader() -> None:
        from redisstream.redis_stream import RedisStreamReader

        if getattr(RedisStreamReader, "_buddystuddy_cluster_safe", False):
            return

        original = RedisStreamReader.poll_round_robin

        def poll_single_hash_slot(self, stream_keys: list[str], *, count: int, timeout_ms: int):
            if not stream_keys:
                return []
            cursor = self._cursor % len(stream_keys)
            stream_key = stream_keys[cursor]
            self._cursor = (cursor + 1) % len(stream_keys)
            return self.commands.xreadgroup(
                [stream_key],
                self.consumer_group,
                self.consumer_name,
                count=count,
                block_ms=timeout_ms,
            )

        RedisStreamReader._buddystuddy_original_poll_round_robin = original
        RedisStreamReader.poll_round_robin = poll_single_hash_slot
        RedisStreamReader._buddystuddy_cluster_safe = True
