from __future__ import annotations

import asyncio
import logging

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
        self._stream_publisher = None
        self._stream_mode = False

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
            self._stream_publisher = None
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
        if self._stream_publisher is None:
            return
        normalized_question_id = str(question_id).strip()
        if not normalized_question_id:
            return
        fields = {
            "questionId": normalized_question_id,
            "eventType": event_type,
        }
        if user_id is not None:
            fields["userId"] = str(user_id)
        self._stream_publisher.publish(
            partition_key=normalized_question_id,
            fields=fields,
            max_len=self.settings.reaction_stream_xadd_max_len,
        )

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

            stream_app = RedisStreamCoordinator(
                coordinator_base_url=self.settings.reaction_stream_coordinator_base_url,
                redis_client=self._redis_client(),
                coordinator_client=coordinator_client,
            )

            @stream_app.stream_listener(
                stream_prefix=self.settings.reaction_stream_prefix,
                group_id=self.settings.reaction_stream_group_id,
                concurrency=self.settings.reaction_stream_concurrency,
                poll_batch_size=self.batch_size,
                poll_timeout=float(self.poll_seconds),
            )
            def handle_reaction_changed(message):
                question_id = str(message.fields.get("questionId") or "").strip()
                try:
                    if question_id:
                        self.database.reconcile_question_stats(question_ids=[int(question_id)])
                    message.ack()
                except Exception:
                    logger.exception("question reaction stream message failed question_id=%s", question_id)

            self._stream_publisher = stream_app.publisher(
                self.settings.reaction_stream_prefix,
                self.settings.reaction_stream_group_id,
                xadd_max_len=self.settings.reaction_stream_xadd_max_len,
            )
            self._stream_publisher.routing_cache.metadata(force_refresh=True)
            stream_app.start()
            self._stream_app = stream_app
            self._stream_mode = True
            logger.info(
                "started question reaction Redis Stream coordinator stream=%s group=%s",
                self.settings.reaction_stream_prefix,
                self.settings.reaction_stream_group_id,
            )
            return True
        except Exception:
            logger.exception("failed to start question reaction Redis Stream coordinator; falling back to DB events")
            self._stream_app = None
            self._stream_publisher = None
            self._stream_mode = False
            return False

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
